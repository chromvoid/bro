/** Disposable progress plus separately identified, durable server confirmations. */
import { SseParser, openEventResponse } from '@/core/events'
import { z } from '@/core/zod'
import { toolProgress } from './progress'
import { UsageField } from './usage'

const Step = z.object({
  id: z.string(),
  kind: z.enum(['model', 'tool', 'compaction']),
  status: z.enum(['running', 'completed', 'failed']),
  label: z.string(),
  started_at: z.string(),
  duration_ms: z.number().finite().nonnegative().nullable(),
})
const Confirmed = z.object({
  id: z.string().min(1).max(200),
  service: z.string().max(100),
  // The server bounds 16,384 Unicode code points; JS lengths count UTF-16 units.
  text: z.string().max(32_768),
})
export type Confirmed = z.infer<typeof Confirmed>
export const Activity = z.object({
  message_id: z.string(),
  turn_id: z.string().nullish(),
  run_id: z.string().nullish(),
  seq: z.number().int().nonnegative(),
  status: z.enum(['active', 'completed', 'failed', 'unavailable']),
  phase: z.enum(['waiting_model', 'thinking', 'generating', 'tool', 'compacting']).nullish(),
  preview_text: z.string().max(128_000),
  source: z.enum(['assistant', 'compaction']).nullish(),
  started_at: z.string().nullish(),
  updated_at: z.string(),
  elapsed_ms: z.number().finite().nonnegative(),
  model_call: z.number().int().nonnegative(),
  attempt: z.number().int().nonnegative(),
  steps: z.array(Step).max(128),
  usage: UsageField,
  // Optional for older servers; an explicit [] is an authoritative empty projection.
  confirmed: z.array(Confirmed).max(128).optional(),
})
export type Activity = z.infer<typeof Activity>
export type ActivityConnection =
  'connecting' | 'connected' | 'reconnecting' | 'offline' | 'ended' | 'unavailable' | 'denied'

/** Only a public assistant text segment may become a preview, never compaction/reasoning. */
export function publicPreview(activity: Activity | null): string {
  return activity?.status === 'active' && activity.source === 'assistant'
    ? activity.preview_text
    : ''
}

export function stepLabel(step: z.infer<typeof Step>): string {
  if (step.kind === 'compaction') return 'assistant.activity.compacting'
  if (step.kind === 'model') return 'assistant.activity.model'
  const stage = toolProgress(step.label)
  return stage !== 'processing' ? `assistant.progress.${stage}` : 'assistant.activity.tool'
}

interface Options {
  messageId: string
  onSnapshot(snapshot: Activity): void
  onConnection(state: ActivityConnection): void
  onTerminal(status: Activity['status']): void
  fetcher?: typeof fetch
  wait?: (ms: number, signal: AbortSignal) => Promise<void>
  idleMs?: number
}

/** Dedicated read-only stream: no admission, recovery, durable cursor or query invalidation. */
export class ActivityStream {
  private controller = new AbortController()
  private run: string | null = null
  private sequence = -1
  private retired = new Set<string>()
  private terminal = false
  private confirmations = ''

  constructor(private readonly options: Options) {}

  stop(): void {
    this.controller.abort()
  }

  async start(): Promise<void> {
    const { signal } = this.controller
    const fetcher = this.options.fetcher ?? fetch
    const wait = this.options.wait ?? pause
    for (let retry = 0; retry < 6 && !signal.aborted && !this.terminal; retry++) {
      this.options.onConnection(retry ? 'reconnecting' : 'connecting')
      try {
        const response = await openEventResponse(
          `/v1/conversation/messages/${encodeURIComponent(this.options.messageId)}/activity`,
          {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'text/event-stream' },
          },
          signal,
          fetcher,
          this.options.idleMs ?? 20_000,
        )
        if (signal.aborted) {
          await response.body?.cancel().catch(() => undefined)
          return
        }
        if ([401, 403].includes(response.status)) {
          await response.body?.cancel()
          this.options.onConnection('denied')
          return
        }
        if ([404, 410].includes(response.status)) {
          await response.body?.cancel()
          // Missing and inaccessible messages share HTTP 404; no prior receipt
          // remains authorized by an unreadable response. A 200 unavailable
          // snapshot still carries its independently authorized confirmations.
          this.options.onConnection('denied')
          return
        }
        if (!response.ok || !response.body) {
          await response.body?.cancel().catch(() => undefined)
          throw new Error('activity_unavailable')
        }
        await this.read(response.body, signal)
      } catch {
        if (signal.aborted) return
      }
      if (signal.aborted || this.terminal) return
      this.options.onConnection('reconnecting')
      if (retry < 5) await wait(Math.min(30_000, 1000 * 2 ** retry), signal).catch(() => undefined)
    }
    if (!signal.aborted && !this.terminal) this.options.onConnection('offline')
  }

  private async read(body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader()
    const parser = new SseParser()
    const decoder = new TextDecoder()
    let first = true
    const cancel = () => {
      void reader.cancel().catch(() => undefined)
    }
    let idle: ReturnType<typeof setTimeout> | undefined
    signal.addEventListener('abort', cancel, { once: true })
    try {
      while (!signal.aborted && !this.terminal) {
        // Heartbeat comments still arrive as chunks: a blackholed connection must not
        // leave a stale answer looking live forever.
        clearTimeout(idle)
        idle = setTimeout(cancel, this.options.idleMs ?? 20_000)
        const { value, done } = await reader.read()
        if (done || signal.aborted) return
        for (const event of parser.push(decoder.decode(value, { stream: true }))) {
          if (event.event !== 'activity') continue
          let data: unknown
          try {
            data = JSON.parse(event.data)
          } catch {
            continue
          }
          const parsed = Activity.safeParse(data)
          if (!parsed.success || signal.aborted) continue
          const snapshot = parsed.data
          if (snapshot.message_id !== this.options.messageId) continue
          // A running durable message can precede its in-memory activity by a moment,
          // or be owned by another process. Retry only this read, with the same bound.
          if (snapshot.status === 'unavailable') {
            this.options.onSnapshot(snapshot)
            if (signal.aborted) return
            this.options.onConnection('unavailable')
            return
          }
          if (snapshot.status === 'active' && !snapshot.run_id) continue
          const run = snapshot.run_id ?? ''
          if (this.retired.has(run)) continue
          if (run !== this.run) {
            if (this.run !== null) this.retired.add(this.run)
            this.run = run
            this.sequence = -1
          }
          const confirmations = JSON.stringify(snapshot.confirmed ?? null)
          // A domain commit can update the durable projection between model events.
          if (
            snapshot.seq < this.sequence ||
            (snapshot.seq === this.sequence && !first && confirmations === this.confirmations)
          )
            continue
          first = false
          this.sequence = snapshot.seq
          this.confirmations = confirmations
          this.options.onSnapshot(snapshot)
          if (signal.aborted) return
          this.options.onConnection('connected')
          if (snapshot.status !== 'active') {
            this.terminal = true
            this.options.onConnection('ended')
            this.options.onTerminal(snapshot.status)
            return
          }
        }
      }
    } finally {
      clearTimeout(idle)
      signal.removeEventListener('abort', cancel)
      await reader.cancel().catch(() => undefined)
      reader.releaseLock()
    }
  }
}

function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const finish = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    const timer = setTimeout(finish, ms)
    if (signal.aborted) finish()
    else signal.addEventListener('abort', finish, { once: true })
  })
}
