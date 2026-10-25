/**
 * Ledger operations of any domain, sent through the submission journal: the exact request is
 * kept until its outcome is known, a refusal is dismissed at once, an unknown outcome stays
 * visible until the user checks or resends it.
 */
import { useQueryClient, type QueryClient } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { useSession } from '@/stores/session'
import { ApiError } from './http'
import type { Submission, SubmissionJournal } from './journal'

export interface Operation {
  kind: string
  payload: Record<string, unknown>
  expected_revisions?: Record<string, number>
  /** A shared resource the operation writes into (a space's task list). */
  target_resource_id?: string
}

export interface Ledger {
  /** e.g. /v1/nutrition/operations */
  endpoint: string
  /** e.g. /v1/nutrition/operations/by-key/ */
  lookupPrefix: string
  /** Query key root to refresh after a write. */
  domain: string
  /**
   * Puts what a receipt returned into the cached reads at once. The next write can follow before
   * their refetch arrives, and it is sent with the revisions the receipt gave.
   */
  remember?: (queries: QueryClient, receipt: unknown) => void
}

export function useLedger(ledger: Ledger) {
  const session = useSession()
  const queries = useQueryClient()
  const journal = computed<SubmissionJournal | null>(() => session.account?.journal ?? null)
  const pendingKeys = new Set<string>()
  const resolvedHandlers = new Set<(entry: Submission) => void>()

  /** Sends one operation; resolves with its receipt. Refusals (409/422…) reject with ApiError. */
  async function operate(operation: Operation): Promise<unknown> {
    const active = journal.value
    if (!active) throw new Error('No session')
    const key = crypto.randomUUID()
    pendingKeys.add(key)
    try {
      const entry = await active.submit({
        endpoint: ledger.endpoint,
        lookupPrefix: ledger.lookupPrefix,
        body: { expected_revisions: {}, ...operation },
        key,
        context: { kind: operation.kind },
      })
      // The receipt is shown right away; the entry has done its job.
      await active.acknowledge(key)
      ledger.remember?.(queries, entry.response)
      return entry.response
    } catch (error) {
      if (error instanceof ApiError) await active.acknowledge(key).catch(() => undefined)
      throw error
    } finally {
      void queries.invalidateQueries({ queryKey: [ledger.domain] })
    }
  }

  // Writes of this ledger whose outcome is still unknown (lost response, closed tab).
  const entries = shallowRef<Submission[]>([])
  async function reload() {
    const all = (await journal.value?.pending()) ?? []
    entries.value = all.filter(
      (entry) => entry.endpoint === ledger.endpoint && entry.state !== 'resolved',
    )
  }
  let unsubscribe: (() => void) | undefined
  watch(
    journal,
    (current) => {
      unsubscribe?.()
      unsubscribe = current?.subscribe(({ entry, removed }) => {
        if (entry.endpoint === ledger.endpoint && pendingKeys.has(entry.key)) {
          if (!removed && entry.state === 'resolved') {
            pendingKeys.delete(entry.key)
            for (const handler of resolvedHandlers)
              void Promise.resolve()
                .then(() => handler(entry))
                .catch(() => undefined)
          } else if (removed || entry.state === 'rejected') pendingKeys.delete(entry.key)
        }
        void reload().catch(() => undefined)
      })
      // A journal the browser refused to open cannot be listed; its writes reject on their own.
      void reload().catch(() => undefined)
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    unsubscribe?.()
    pendingKeys.clear()
    resolvedHandlers.clear()
  })
  const unresolved = computed(() => entries.value.filter((entry) => entry.state === 'uncertain'))

  async function settle<T>(
    task: (active: SubmissionJournal) => Promise<T>,
  ): Promise<T | undefined> {
    const active = journal.value
    if (!active) return
    const result = await task(active)
    // The receipt settles a write; slow read refreshes must not delay its recovery controls.
    void queries.invalidateQueries({ queryKey: [ledger.domain] })
    return result
  }

  return {
    operate,
    /** Optional draft completion, including automatic receipt recovery for this instance's writes. */
    onResolved: (handler: (entry: Submission) => void) => {
      resolvedHandlers.add(handler)
      return () => resolvedHandlers.delete(handler)
    },
    unresolved,
    check: (key: string) =>
      settle(async (active) => {
        const entry = await active.check(key)
        if (entry.state === 'resolved') await active.acknowledge(key)
        return entry
      }),
    resend: (key: string) =>
      settle(async (active) => {
        const entry = await active.retry(key)
        if (entry.state === 'resolved') await active.acknowledge(key)
        return entry
      }),
    abandon: (key: string) => settle((active) => active.abandon(key)),
  }
}

/** The operation kind of a journal entry, for describing it to the user. */
export function operationKind(entry: Submission): string {
  try {
    return String((JSON.parse(entry.body) as { kind?: unknown }).kind ?? '')
  } catch {
    return ''
  }
}
