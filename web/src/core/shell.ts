import { ref } from 'vue'
import { z } from './zod'

/** Only the iOS shell has a bridge; Android uses the native app. */
export const SHELL_API = 1

export const ShellCapabilities = z.looseObject({
  shell_api: z.number().int(),
  platform: z.literal('ios'),
  /** System notifications through the shell (docs/notifications-spec.md §6); absent in older shells. */
  notifications: z
    .looseObject({ push: z.enum(['fcm', 'apns']).nullable(), local: z.boolean() })
    .optional(),
  /** screen.wake / screen.release keep the screen on during a call; absent in older shells. */
  screen_wake: z.boolean().optional(),
  /** theme.set matches system bar icons to the page's resolved theme; absent in older shells. */
  system_bars: z.boolean().optional(),
  /** Explicit native background microphone capture; absent in older shells and browsers. */
  ambient_audio: z.boolean().optional(),
  /** location.status / .request / .current, and background recording when `background`; absent in older shells. */
  location: z.object({ background: z.boolean() }).optional(),
})
export type ShellCapabilities = z.infer<typeof ShellCapabilities>

const Reply = z.union([
  z.object({ id: z.string(), ok: z.literal(true), result: z.unknown() }),
  z.object({ id: z.string(), ok: z.literal(false), error: z.string() }),
])
type Reply = z.infer<typeof Reply>

/** Something the shell tells the page on its own: a tapped notification, a new push token. */
const ShellEvent = z.object({
  event: z.string().regex(/^[a-z]+(\.[a-z]+)+$/),
  payload: z.record(z.string(), z.unknown()).default({}),
})
export type ShellEvent = z.infer<typeof ShellEvent>

export class ShellError extends Error {}

/** iOS: a WKScriptMessageHandlerWithReply; the reply is the resolved value. */
interface ApplePort {
  postMessage(message: string): Promise<unknown>
}

export class ShellBridge {
  private sequence = 0
  private readonly pending = new Map<string, (reply: Reply) => void>()
  private readonly listeners = new Set<(event: ShellEvent) => void>()

  constructor(
    private readonly post: (message: string) => void,
    private readonly timeout = 5_000,
  ) {}

  /** `timeout`: longer for a request that waits for the person, like a system permission dialog. */
  request(
    type: string,
    payload: Record<string, unknown> = {},
    timeout = this.timeout,
  ): Promise<unknown> {
    const id = String(++this.sequence)
    const message = JSON.stringify({ id, type, payload })
    const schedule = type === 'notifications.schedule'
    if (
      new TextEncoder().encode(message).byteLength > (schedule ? 128 * 1024 : 16 * 1024) ||
      (schedule && (!Array.isArray(payload.items) || payload.items.length > 60))
    )
      return Promise.reject(new ShellError('too_large'))
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new ShellError('timeout'))
      }, timeout)
      this.pending.set(id, (reply) => {
        clearTimeout(timer)
        if (reply.ok) resolve(reply.result)
        else reject(new ShellError(reply.error))
      })
      try {
        this.post(message)
      } catch {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(new ShellError('unavailable'))
      }
    })
  }

  /** Listens to the shell's own events; returns the way to stop. */
  on(listener: (event: ShellEvent) => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  /** A reply or an event from the shell; anything malformed or unexpected is ignored. */
  received(data: unknown): void {
    if (typeof data !== 'string') return
    let value: unknown
    try {
      value = JSON.parse(data)
    } catch {
      return
    }
    const event = ShellEvent.safeParse(value)
    if (event.success) {
      for (const listener of this.listeners) listener(event.data)
      return
    }
    const parsed = Reply.safeParse(value)
    if (!parsed.success) return
    const reply = parsed.data
    const settle = this.pending.get(reply.id)
    if (!settle) return
    this.pending.delete(reply.id)
    settle(reply)
  }

  /** Null for a shell speaking another bridge version: its features are not offered. */
  async capabilities(): Promise<ShellCapabilities | null> {
    const parsed = ShellCapabilities.safeParse(await this.request('shell.capabilities'))
    return parsed.success && parsed.data.shell_api === SHELL_API ? parsed.data : null
  }
}

export function connectShell(scope: object = globalThis, timeout?: number): ShellBridge | null {
  const apple = (
    scope as { webkit?: { messageHandlers?: { AssistantShell?: Partial<ApplePort> } } }
  ).webkit?.messageHandlers?.AssistantShell
  if (typeof apple?.postMessage === 'function') {
    const port = apple as ApplePort
    const bridge: ShellBridge = new ShellBridge((message) => {
      port.postMessage(message).then(
        (reply) => bridge.received(reply),
        () => undefined,
      )
    }, timeout)
    // WKWebView has no channel back: the shell calls this with a JSON event (evaluateJavaScript).
    ;(scope as { __assistantShellEvent?: (data: unknown) => void }).__assistantShellEvent = (
      data,
    ) => bridge.received(data)
    return bridge
  }
  return null
}

const capabilities = ref<ShellCapabilities | null>(null)
let bridge: ShellBridge | null | undefined
let ready: Promise<ShellCapabilities | null> | undefined

/** The shell's capabilities, asked once per page; null in a browser. */
export function useShell() {
  if (bridge === undefined) {
    bridge = connectShell()
    ready = bridge
      ? bridge
          .capabilities()
          .then((value) => (capabilities.value = value))
          .catch(() => null)
      : Promise.resolve(null)
  }
  return {
    capabilities,
    ready: () => ready!,
    request: (type: string, payload?: Record<string, unknown>, timeout?: number) =>
      bridge
        ? bridge.request(type, payload, timeout)
        : Promise.reject(new ShellError('unavailable')),
    on: (listener: (event: ShellEvent) => void) => bridge?.on(listener) ?? (() => undefined),
  }
}
