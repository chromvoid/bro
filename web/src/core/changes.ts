/**
 * A screen that keeps a domain's data current itself claims that domain's change events; every
 * other domain an event names is invalidated as before. A claim that declines or fails leaves its
 * domain to invalidation, so a broken screen never stops data from refreshing.
 */
import type { ChangeEvent } from './events'

/** Returns true when the event is fully taken care of, false to leave it to invalidation. */
export type ChangeHandler = (event: ChangeEvent) => boolean

export class ChangeClaims {
  private readonly handlers = new Map<string, Set<ChangeHandler>>()

  claim(domain: string, handler: ChangeHandler): () => void {
    let handlers = this.handlers.get(domain)
    if (!handlers) this.handlers.set(domain, (handlers = new Set()))
    handlers.add(handler)
    return () => {
      handlers.delete(handler)
      if (!handlers.size && this.handlers.get(domain) === handlers) this.handlers.delete(domain)
    }
  }

  /** The domains of this event that no claim took over: every claim of a domain must accept it. */
  unclaimed(event: ChangeEvent, domains: readonly string[]): string[] {
    return domains.filter((domain) => {
      const handlers = this.handlers.get(domain)
      if (!handlers?.size) return true
      let handled = true
      for (const handler of [...handlers]) {
        try {
          if (!handler(event)) handled = false
        } catch {
          handled = false
        }
      }
      return !handled
    })
  }
}
