import type { InjectionKey, Ref } from 'vue'

/**
 * The conversation is sending or checking something, so actions that would send again wait.
 * Injected rather than passed through every message: a change re-renders only the replies that
 * show such an action, not the whole history.
 */
export const conversationBusy: InjectionKey<Readonly<Ref<boolean>>> = Symbol('conversationBusy')

/**
 * What an exchange's own actions wait for. Injected for the same reason: sending a message must not
 * re-render every exchange of a long history, only those that show such an action.
 */
export interface ExchangeActions {
  /** Restoring or checking would put text in the composer: it is busy or not ready yet. */
  busy: Readonly<Ref<boolean>>
  /** Exchanges whose stop or withdrawal is being sent. */
  controlling: Readonly<Ref<ReadonlySet<string>>>
}
export const exchangeActions: InjectionKey<ExchangeActions> = Symbol('exchangeActions')

/**
 * The earlier answer whose actions a tap showed on a touch screen, as a hover does with a mouse.
 * One at a time: tapping another answer moves them there.
 */
export const revealedReply: InjectionKey<Ref<string | null>> = Symbol('revealedReply')
