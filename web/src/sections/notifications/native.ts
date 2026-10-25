/** Native notifications belong to one verified installation, user and login device. */
import { ref } from 'vue'
import { api } from '@/core/client'
import { ApiError, type Api } from '@/core/http'
import { useShell, type ShellEvent } from '@/core/shell'
import { z } from '@/core/zod'
import { appLink } from './model'
import type { WebPushState } from './push'

export const ClientConfig = z.looseObject({
  instance_id: z.string().min(1),
  name: z.string(),
  client_api: z.number().int().optional(),
  support_url: z.string().nullable().optional(),
  privacy_url: z.string().nullable().optional(),
  providers: z.array(z.string()).optional(),
})

const NativeState = z.object({
  permission: z.enum(['granted', 'denied', 'not-determined']),
  enabled: z.boolean(),
  push_available: z.boolean().optional(),
})
const Registration = z.object({
  kind: z.literal('apns'),
  address: z.string().min(1),
  public_key: z.string().min(1),
  auth_secret: z.string().min(1),
  send_capability: z.string().min(1),
})
const PendingAck = z.object({
  instance_id: z.string(),
  user_id: z.string(),
  device_id: z.string(),
  expires_at: z.string(),
  snapshot_id: z.string(),
  installed: z.array(z.string()).max(60),
})
const ACK_STORAGE = 'assistant.native.schedule-ack'
export const Schedule = z.object({
  snapshot_id: z.string().min(1),
  server_now: z.string().datetime({ offset: true }),
  expires_at: z.string().datetime({ offset: true }),
  items: z
    .array(
      z.object({
        key: z.string().min(1),
        revision: z.union([z.number().int(), z.string()]),
        due_at: z.string().datetime({ offset: true }),
        title: z.string(),
        body: z.string(),
        link: z.string().nullable(),
      }),
    )
    .max(60),
})

interface Identity {
  id: string
  device_id?: string | null
  session_expires_at?: string | null
}
interface Binding {
  instance_id: string
  user_id: string
  device_id: string
  expires_at: string
}
interface NativePort {
  request(type: string, payload?: Record<string, unknown>, timeout?: number): Promise<unknown>
  available(): Promise<boolean>
}

export class NativeNotifications {
  readonly state = ref<WebPushState>('unsupported')
  readonly config = ref<z.infer<typeof ClientConfig> | null>(null)
  readonly pushAvailable = ref(false)
  readonly opened = ref<{ link: string; sequence: number } | null>(null)
  readonly schedulePending = ref(false)
  private openedSequence = 0
  private generation = 0
  private binding: Binding | null = null
  private bindingReady: Promise<void> = Promise.resolve()
  private sessionQueue: Promise<void> = Promise.resolve()
  private syncing: Promise<void> | null = null
  private dirty = false
  private pendingAck: z.infer<typeof PendingAck> | null = null
  private retryTimer: ReturnType<typeof setTimeout> | undefined

  constructor(
    private readonly port: NativePort,
    private readonly transport: Pick<Api, 'get' | 'post'>,
    private readonly wait: (milliseconds: number) => Promise<void> = (milliseconds) =>
      new Promise((resolve) => setTimeout(resolve, milliseconds)),
  ) {}

  /** Invalidate synchronously, before any late permission, token or schedule can write. */
  account(identity: Identity | null): Promise<void> {
    const current = ++this.generation
    this.binding = null
    this.state.value = 'unsupported'
    this.config.value = null
    this.opened.value = null
    clearTimeout(this.retryTimer)
    this.pendingAck = null
    this.schedulePending.value = false
    if (!identity) this.saveAck(null)
    this.bindingReady = (async () => {
      if (!(await this.port.available())) return
      if (current !== this.generation) return
      if (!identity) {
        await this.setSession({ clear: true }, current)
        return
      }
      if (!identity.device_id || !identity.session_expires_at || current !== this.generation) return
      const config = ClientConfig.parse(await this.transport.get('/v1/client/config'))
      if (current !== this.generation) return
      const binding = {
        instance_id: config.instance_id,
        user_id: identity.id,
        device_id: identity.device_id,
        expires_at: identity.session_expires_at,
      }
      const result = await this.setSession(binding, current)
      if (current !== this.generation) return
      this.binding = binding
      this.config.value = config
      try {
        const saved = PendingAck.safeParse(JSON.parse(localStorage.getItem(ACK_STORAGE) ?? 'null'))
        if (
          saved.success &&
          saved.data.instance_id === binding.instance_id &&
          saved.data.user_id === binding.user_id &&
          saved.data.device_id === binding.device_id &&
          Date.parse(saved.data.expires_at) === Date.parse(binding.expires_at) &&
          Date.parse(saved.data.expires_at) > Date.now()
        )
          this.pendingAck = saved.data
        else this.saveAck(null)
      } catch {
        /* In-memory acknowledgements still work when storage is unavailable. */
      }
      this.schedulePending.value = this.pendingAck !== null
      const pending = z
        .looseObject({ pending_opened: z.record(z.string(), z.unknown()).optional() })
        .safeParse(result)
      if (pending.success && pending.data.pending_opened)
        await this.event({ event: 'notifications.opened', payload: pending.data.pending_opened })
      await this.refreshState()
      if (current === this.generation) await this.sync()
      if (current === this.generation && this.state.value === 'on' && this.pushAvailable.value) {
        try {
          const registration = Registration.parse(
            await this.port.request('notifications.register', {}, 90_000),
          )
          await this.register(registration, current)
        } catch {
          /* Local schedules remain active during relay failures. */
        }
      }
    })()
    return this.bindingReady
  }

  private setSession(payload: Record<string, unknown>, current: number): Promise<unknown> {
    const result = this.sessionQueue
      .catch(() => undefined)
      .then(async () => {
        if (current === this.generation)
          return this.port.request('notifications.session', payload, 60_000)
        return undefined
      })
    this.sessionQueue = result.then(
      () => undefined,
      () => undefined,
    )
    return result
  }

  async refreshState(): Promise<WebPushState> {
    const current = this.generation
    if (!(await this.port.available())) return 'unsupported'
    const native = NativeState.parse(await this.port.request('notifications.state'))
    if (current !== this.generation) return this.state.value
    this.pushAvailable.value = native.push_available !== false
    this.state.value = native.permission === 'denied' ? 'denied' : native.enabled ? 'on' : 'off'
    return this.state.value
  }

  async enable(): Promise<void> {
    await this.bindingReady
    const current = this.generation
    this.assertCurrent(current)
    const permission = z
      .object({ granted: z.boolean() })
      .parse(await this.port.request('notifications.permission', {}, 60_000))
    this.assertCurrent(current)
    await this.refreshState()
    if (!permission.granted) throw new Error('denied')
    // Local delivery remains useful when the relay is unavailable or not configured.
    await this.sync()
    if (this.pushAvailable.value) {
      const registration = Registration.parse(
        await this.port.request('notifications.register', {}, 90_000),
      )
      this.assertCurrent(current)
      await this.register(registration, current)
    }
  }

  async disable(): Promise<void> {
    await this.bindingReady
    const current = this.generation
    this.assertCurrent(current)
    // Cancel device notifications even if removal from the server cannot be delivered.
    await this.port.request('notifications.unregister')
    this.assertCurrent(current)
    this.state.value = 'off'
    this.saveAck(null)
    clearTimeout(this.retryTimer)
    await this.transport.post('/v1/push/channels/remove', {}, this.options(current))
  }

  private assertCurrent(current: number): void {
    if (current !== this.generation || !this.binding)
      throw new DOMException('Session changed', 'AbortError')
  }

  private options(current: number) {
    return { expectedUser: this.binding?.user_id, beforeSend: () => this.assertCurrent(current) }
  }

  private async register(value: z.infer<typeof Registration>, current: number) {
    this.assertCurrent(current)
    await this.transport.post('/v1/push/channels', value, this.options(current))
  }

  private saveAck(value: z.infer<typeof PendingAck> | null) {
    this.pendingAck = value
    this.schedulePending.value = value !== null
    if (!value) clearTimeout(this.retryTimer)
    try {
      if (value) localStorage.setItem(ACK_STORAGE, JSON.stringify(value))
      else localStorage.removeItem(ACK_STORAGE)
    } catch {
      /* Foreground retries remain available without durable browser storage. */
    }
  }

  private async acknowledge(current: number): Promise<void> {
    const pending = this.pendingAck
    if (!pending) return
    for (let attempt = 0; attempt < 3; attempt++) {
      this.assertCurrent(current)
      const controller = new AbortController()
      const timeout = setTimeout(
        () => controller.abort(new DOMException('Receipt timeout', 'TimeoutError')),
        5_000,
      )
      try {
        await this.transport.post(
          '/v1/notifications/schedule/ack',
          { snapshot_id: pending.snapshot_id, installed: pending.installed },
          { ...this.options(current), signal: controller.signal },
        )
        this.assertCurrent(current)
        this.saveAck(null)
        return
      } catch (error) {
        this.assertCurrent(current)
        if (error instanceof ApiError && error.status < 500) throw error
        if (attempt < 2) await this.wait(500 * (attempt + 1))
        else {
          clearTimeout(this.retryTimer)
          this.retryTimer = setTimeout(() => {
            if (current === this.generation && this.state.value === 'on')
              void this.sync().catch(() => undefined)
          }, 30_000)
          throw error
        }
      } finally {
        clearTimeout(timeout)
      }
    }
  }

  /** Changes coalesce; one snapshot completes before the next replaces it. */
  sync(): Promise<void> {
    this.dirty = true
    if (this.syncing) return this.syncing
    this.syncing = (async () => {
      let conflicts = 0
      while (this.dirty) {
        this.dirty = false
        const current = this.generation
        if (!this.binding || this.state.value !== 'on') continue
        try {
          if (this.pendingAck) {
            try {
              await this.acknowledge(current)
            } catch (error) {
              if (!(error instanceof ApiError && error.status === 409) || conflicts++ >= 1)
                throw error
              this.saveAck(null)
            }
          }
          const snapshot = Schedule.parse(await this.transport.get('/v1/notifications/schedule'))
          this.assertCurrent(current)
          if (snapshot.items.some((item) => item.link !== null && !appLink(item.link)))
            throw new Error('invalid_notification_link')
          const result = z
            .object({ installed: z.array(z.string()).max(60) })
            .parse(await this.port.request('notifications.schedule', snapshot))
          this.assertCurrent(current)
          const keys = new Set(snapshot.items.map((item) => item.key))
          if (
            new Set(result.installed).size !== result.installed.length ||
            result.installed.some((key) => !keys.has(key))
          )
            throw new Error('invalid_schedule_receipt')
          this.saveAck({
            ...this.binding!,
            snapshot_id: snapshot.snapshot_id,
            installed: result.installed,
          })
          try {
            await this.acknowledge(current)
          } catch (error) {
            if (!(error instanceof ApiError && error.status === 409) || conflicts++ >= 1)
              throw error
            this.assertCurrent(current)
            this.saveAck(null)
            this.dirty = true
          }
        } catch (error) {
          // A new account may already be waiting on this shared synchronization promise.
          if (
            current !== this.generation &&
            this.dirty &&
            this.binding &&
            this.state.value === 'on'
          )
            continue
          throw error
        }
      }
    })().finally(() => {
      this.syncing = null
    })
    return this.syncing
  }

  async event(event: ShellEvent): Promise<string | null> {
    const binding = this.binding
    if (
      !binding ||
      event.payload.instance_id !== binding.instance_id ||
      event.payload.user_id !== binding.user_id ||
      event.payload.device_id !== binding.device_id
    )
      return null
    if (event.event === 'notifications.token' && this.state.value === 'on') {
      const value = Registration.parse(event.payload)
      await this.register(value, this.generation)
    }
    if (event.event === 'notifications.opened') {
      const link = appLink(typeof event.payload.link === 'string' ? event.payload.link : null)
      if (link) this.opened.value = { link, sequence: ++this.openedSequence }
      return link
    }
    return null
  }
}

let native: NativeNotifications | undefined
export function nativeNotifications(): NativeNotifications {
  if (!native) {
    const shell = useShell()
    native = new NativeNotifications(
      {
        request: shell.request,
        available: async () => (await shell.ready())?.notifications?.local === true,
      },
      api,
    )
  }
  return native
}
