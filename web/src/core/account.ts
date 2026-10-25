/** Services bound to one signed-in account: its storage, write journal and change stream. */
import type { QueryClient } from '@tanstack/vue-query'
import { AccountEvents } from './accountEvents'
import { ChangeClaims } from './changes'
import { SubmissionReconciler } from './reconciliation'
import type { Api } from './http'
import { SubmissionJournal, keyLock, type JournalChange } from './journal'
import { AccountStore, persistStorage } from './storage'
import { Invalidator, affectedDomains } from './topics'

export interface AccountServices {
  store: AccountStore
  journal: SubmissionJournal
  stream: AccountEvents
  reconciliation: SubmissionReconciler
  /** Screens that keep a domain current themselves take its change events from invalidation. */
  changes: ChangeClaims
  close(): void
}

export interface AccountHooks {
  /** Rights or memberships changed: re-read the identity before trusting any screen. */
  accessChanged(): void
  /** The stream was refused (401/403). */
  denied(status: number): void
  /** Scheduled notifications may have changed; never replay domain actions to refresh them. */
  changed?(): void
}

const CURSOR = 'events.cursor'

export async function openAccount(
  userId: string,
  api: Api,
  queries: QueryClient,
  hooks: AccountHooks,
): Promise<AccountServices> {
  const store = await AccountStore.open(userId).catch(() => AccountStore.unavailable(userId))
  void persistStorage()
  const journal = new SubmissionJournal(store, api, keyLock(`assistant-journal/${userId}`))

  // Other tabs of the same account see journal changes; relayed changes are not sent back.
  let channel: BroadcastChannel | null = null
  try {
    if (typeof BroadcastChannel !== 'undefined')
      channel = new BroadcastChannel(`assistant-journal/${userId}`)
  } catch {
    // Private/browser storage policies may expose the API but refuse construction.
  }
  const unsubscribe = journal.subscribe((change: JournalChange) => {
    if (change.local) channel?.postMessage({ entry: change.entry, removed: change.removed })
  })
  if (channel) channel.onmessage = (message) => journal.relay(message.data)

  const invalidator = new Invalidator(queries)
  const changes = new ChangeClaims()
  const reconciliation = new SubmissionReconciler(journal)
  const stream = new AccountEvents({
    userId,
    cursor: {
      load: async () => (await store.value<number>(CURSOR)) ?? null,
      save: (value) => store.setValue(CURSOR, value),
    },
    onChange(event) {
      if (event.topic === 'access.changed') hooks.accessChanged()
      if (
        event.topic === null ||
        event.topic === 'resource.changed' ||
        /^(tasks|timers|notifications|access)\./.test(event.topic)
      )
        hooks.changed?.()
      const domains = affectedDomains(event.topic)
      if (domains === 'all') invalidator.add('all')
      else {
        const left = changes.unclaimed(event, domains)
        if (left.length) invalidator.add(left)
      }
    },
    onResync: () => {
      hooks.changed?.()
      invalidator.add('all')
      reconciliation.trigger()
    },
    onDenied: hooks.denied,
  })
  stream.start()

  return {
    store,
    journal,
    stream,
    reconciliation,
    changes,
    close() {
      stream.stop()
      reconciliation.close()
      invalidator.stop()
      unsubscribe()
      channel?.close()
      store.close()
    },
  }
}
