/** The shell owns capture, its durable upload queue and microphone permission. */
import { ref } from 'vue'
import { ShellError, useShell, type ShellEvent } from '@/core/shell'
import { z } from '@/core/zod'

export const AmbientState = z.object({
  recording: z.boolean(),
  queued_batches: z.number().int().nonnegative(),
  queued_bytes: z.number().int().nonnegative(),
  last_uploaded_at: z.string().nullable(),
  error: z.string().nullable(),
})
export type AmbientState = z.infer<typeof AmbientState>

interface Identity {
  id: string
  device_id?: string | null
  session_expires_at?: string | null
}
interface Binding {
  user_id: string
  device_id: string
  expires_at: string
}
interface NativePort {
  available(): Promise<boolean>
  request(type: string, payload?: Record<string, unknown>, timeout?: number): Promise<unknown>
}

/** Unknown native errors never become user-visible strings. */
export function ambientErrorKey(code: string | null | undefined): string {
  if (['permission', 'permission_denied', 'microphone_denied', 'denied'].includes(code ?? ''))
    return 'ambientAudio.errors.permission'
  if (
    ['session_expired', 'session', 'expired', 'unauthorized', 'not_authenticated'].includes(
      code ?? '',
    )
  )
    return 'ambientAudio.errors.session'
  if (['queue_full', 'storage_full', 'full'].includes(code ?? '')) return 'ambientAudio.errors.full'
  if (['interrupted', 'audio_interrupted', 'microphone_busy'].includes(code ?? ''))
    return 'ambientAudio.errors.interrupted'
  if (
    ['offline', 'upload_failed', 'network', 'network_unavailable', 'not_connected'].includes(
      code ?? '',
    )
  )
    return 'ambientAudio.errors.upload'
  if (['queue_corrupt', 'batch_expired'].includes(code ?? '')) return 'ambientAudio.errors.queue'
  if (['invalid_receipt', 'server_rejected'].includes(code ?? ''))
    return 'ambientAudio.errors.server'
  if (code === 'codec_unavailable') return 'ambientAudio.errors.codec'
  if (code === 'vad_unavailable') return 'ambientAudio.errors.vad'
  if (code === 'capture_failed') return 'ambientAudio.errors.capture'
  return 'ambientAudio.errors.unknown'
}

/**
 * The recorder's problem worth showing. An older shell reports an ended session on every account
 * rebind, also with nothing recorded or queued; signed in and idle, there is nothing to act on.
 */
export function visibleAmbientFailure(
  state: AmbientState | null,
  failure: string | null,
  signedIn: boolean,
): string | null {
  const code = failure ?? state?.error ?? null
  if (!code) return null
  const nothingLost = !state?.recording && !state?.queued_batches
  return ambientErrorKey(code) === 'ambientAudio.errors.session' && signedIn && nothingLost
    ? null
    : code
}

export class NativeAmbientAudio {
  readonly supported = ref(false)
  readonly state = ref<AmbientState | null>(null)
  readonly failure = ref<string | null>(null)
  readonly busy = ref(false)
  private generation = 0
  private stateSequence = 0
  private binding: Binding | null = null
  private accountReady: Promise<void> = Promise.resolve()

  constructor(private readonly port: NativePort) {}

  /** Synchronously detach web state before logout or another identity can admit a late reply. */
  account(identity: Identity | null, options: { preserveQueue?: boolean } = {}): Promise<void> {
    const current = ++this.generation
    const previous = this.binding
    this.binding = null
    this.state.value = null
    this.failure.value = null
    this.busy.value = false
    // Revoke native authority immediately; automatic auth loss retains finalized local batches.
    if (!identity && this.supported.value) {
      this.accountReady = this.port
        .request('ambient.account', {
          user_id: null,
          preserve_queue: options.preserveQueue === true,
        })
        .then(() => undefined)
      return this.accountReady
    }
    if (
      this.supported.value &&
      previous &&
      identity &&
      (previous.user_id !== identity.id || previous.device_id !== identity.device_id)
    )
      void this.port
        .request('ambient.account', { user_id: null, preserve_queue: false })
        .catch(() => undefined)
    this.accountReady = this.bind(identity, current, options.preserveQueue === true)
    return this.accountReady
  }

  private async bind(
    identity: Identity | null,
    current: number,
    preserveQueue: boolean,
  ): Promise<void> {
    try {
      const supported = await this.port.available()
      if (current !== this.generation) return
      this.supported.value = supported
      if (!supported) return
      if (!identity?.device_id || !identity.session_expires_at) {
        await this.port.request('ambient.account', { user_id: null, preserve_queue: preserveQueue })
        return
      }
      const binding = {
        user_id: identity.id,
        device_id: identity.device_id,
        expires_at: identity.session_expires_at,
      }
      // This is a lifetime hint. The shell verifies its own cookie session before starting.
      await this.port.request('ambient.account', binding)
      if (current !== this.generation) return
      this.binding = binding
      await this.readState(current)
    } catch (error) {
      if (current === this.generation) this.failure.value = this.errorCode(error)
    }
  }

  private errorCode(error: unknown): string {
    return error instanceof ShellError ? error.message : 'unavailable'
  }

  private assertCurrent(current: number): void {
    if (current !== this.generation || !this.binding)
      throw new DOMException('Session changed', 'AbortError')
  }

  private async readState(current: number): Promise<void> {
    this.assertCurrent(current)
    const sequence = ++this.stateSequence
    try {
      const result = AmbientState.parse(await this.port.request('ambient.state'))
      this.assertCurrent(current)
      if (sequence !== this.stateSequence) return
      this.state.value = result
      this.failure.value = null
    } catch (error) {
      if (sequence === this.stateSequence) throw error
    }
  }

  async refreshState(): Promise<void> {
    const current = this.generation
    await this.accountReady.catch(() => undefined)
    if (current !== this.generation || !this.binding) return
    try {
      await this.readState(current)
    } catch (error) {
      if (current === this.generation) this.failure.value = this.errorCode(error)
    }
  }

  /** Events have no account identifier, so read the current native binding instead. */
  event(event: ShellEvent): Promise<void> {
    return event.event === 'ambient.state' ? this.refreshState() : Promise.resolve()
  }

  /** Only opens native controls; the person explicitly starts capture there. */
  open(): Promise<void> {
    return this.control('ambient.open', 60_000)
  }

  stop(): Promise<void> {
    return this.control('ambient.stop')
  }

  private async control(type: string, timeout?: number): Promise<void> {
    const current = this.generation
    await this.accountReady.catch(() => undefined)
    this.assertCurrent(current)
    if (this.busy.value) return
    this.busy.value = true
    this.failure.value = null
    try {
      await this.port.request(type, {}, timeout)
      this.assertCurrent(current)
      await this.readState(current)
    } catch (error) {
      if (current === this.generation) this.failure.value = this.errorCode(error)
    } finally {
      if (current === this.generation) this.busy.value = false
    }
  }
}

let native: NativeAmbientAudio | undefined
export function nativeAmbientAudio(): NativeAmbientAudio {
  if (!native) {
    const shell = useShell()
    native = new NativeAmbientAudio({
      request: shell.request,
      available: async () => (await shell.ready())?.ambient_audio === true,
    })
  }
  return native
}
