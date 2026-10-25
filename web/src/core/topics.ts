/**
 * Query keys start with their domain (['nutrition', 'day', date], ['tasks', ...]), so a change
 * event invalidates exactly that domain. Unknown or missing topics refresh everything.
 */
const DOMAINS: Record<string, readonly string[]> = {
  // The assistant also answers in shared chats.
  'assistant.changed': ['conversation', 'community'],
  'nutrition.changed': ['nutrition'],
  // The administrator's food catalog: its releases and refresh job.
  'nutrition.catalog': ['catalog'],
  'personal.changed': ['personal'],
  'tasks.changed': ['tasks'],
  'timers.changed': ['timers'],
  'workers.changed': ['workers'],
  'accounts.changed': ['accounts'],
  'challenges.changed': ['challenges'],
  'notifications.changed': ['notifications'],
  // Only a wake-up: the overlay reads the call itself from /v1/calls/current.
  'calls.changed': ['calls'],
  // Shared resources: chats, space task lists, challenges and personal sections.
  'resource.changed': ['community', 'shared', 'tasks', 'challenges'],
}

export function affectedDomains(topic: string | null): readonly string[] | 'all' {
  if (topic === null || topic === 'access.changed') return 'all'
  return DOMAINS[topic] ?? 'all'
}

interface Queries {
  isFetching(filters?: { queryKey: readonly unknown[] }): number
  invalidateQueries(filters?: { queryKey: readonly unknown[] }): Promise<void>
}

/**
 * Turns change events into refetches without starving them: events arriving together are merged,
 * a reload already running is never cancelled, and changes that arrive during it are followed by
 * one more reload once it finishes. (Cancelling on every event kept a busy screen from ever
 * finishing its reload.)
 */
export class Invalidator {
  private readonly pending = new Set<string>()
  private everything = false
  private timer: ReturnType<typeof setTimeout> | undefined

  constructor(
    private readonly queries: Queries,
    private readonly delayMs = 250,
  ) {}

  add(domains: readonly string[] | 'all'): void {
    if (domains === 'all') this.everything = true
    else for (const domain of domains) this.pending.add(domain)
    this.schedule()
  }

  stop(): void {
    clearTimeout(this.timer)
    this.timer = undefined
  }

  private schedule(): void {
    this.timer ??= setTimeout(() => this.flush(), this.delayMs)
  }

  private flush(): void {
    this.timer = undefined
    if (this.everything) {
      if (this.queries.isFetching() > 0) return this.schedule()
      this.everything = false
      this.pending.clear()
      void this.queries.invalidateQueries()
      return
    }
    for (const domain of [...this.pending]) {
      if (this.queries.isFetching({ queryKey: [domain] }) > 0) continue
      this.pending.delete(domain)
      void this.queries.invalidateQueries({ queryKey: [domain] })
    }
    if (this.pending.size) this.schedule()
  }
}
