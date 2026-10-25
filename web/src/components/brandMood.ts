import { shallowRef } from 'vue'

/**
 * The assistant's state as a mouth of the Bro) mark: B) when idle, B⋮ while a turn runs, BD for
 * a few seconds after a turn that confirmed a write, B( for a minute after a turn failed (not
 * after a stop or a withdrawal the person asked for). Code derives it from the conversation's own
 * state; no model output decides it. The desk screen follows the same rules on the server
 * (server/conversation/mood.py).
 */
export type Mood = 'ok' | 'thinking' | 'done' | 'oops'

export const DONE_MS = 10_000
export const OOPS_MS = 60_000
/** How long the thinking dots take to flow into ")" once an answer is ready. */
export const MERGE_MS = 320

/** What every brand mark shows; the chat screen sets it while it is open. */
export const assistantMood = shallowRef<Mood>('ok')

export interface MoodExchange {
  id: string
  status: 'pending' | 'done' | 'failed' | 'clarification'
  controlState?: string | null
  replies: readonly { committed: boolean }[]
}

export class MoodTracker {
  private readonly seen = new Map<string, MoodExchange['status']>()
  private started = false
  private flash: { mood: 'done' | 'oops'; until: number } | null = null

  /**
   * `exchanges` in chronological order, newest last; `working` while a send is on its way.
   * Only an outcome seen to arrive flashes: history that loads already finished stays B).
   */
  update(
    exchanges: readonly MoodExchange[],
    working: boolean,
    now: number,
  ): { mood: Mood; until: number | null } {
    const newest = exchanges.at(-1)?.id
    for (const exchange of exchanges) {
      // A newest exchange that first appears already finished was sent from here a moment ago.
      const before =
        this.seen.get(exchange.id) ?? (this.started && exchange.id === newest ? 'pending' : null)
      this.seen.set(exchange.id, exchange.status)
      if (before !== 'pending' || exchange.status === 'pending') continue
      if (exchange.status === 'failed' && !exchange.controlState)
        this.flash = { mood: 'oops', until: now + OOPS_MS }
      else if (exchange.status === 'done' && exchange.replies.some((reply) => reply.committed))
        this.flash = { mood: 'done', until: now + DONE_MS }
      else this.flash = null
    }
    this.started = true
    if (working || exchanges.some((exchange) => exchange.status === 'pending'))
      return { mood: 'thinking', until: null }
    if (this.flash && this.flash.until > now)
      return { mood: this.flash.mood, until: this.flash.until }
    this.flash = null
    return { mood: 'ok', until: null }
  }
}
