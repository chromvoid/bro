/** Conversation history and durable submission recovery; composer state lives separately. */
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, ref, shallowRef, toRaw, watch, type Ref } from 'vue'
import { api } from '@/core/client'
import type { Submission, SubmissionJournal } from '@/core/journal'
import { useSession } from '@/stores/session'
import { History, toExchange, type Exchange, type Message } from './model'
import { deliverMessage, ENDPOINT, type MessageBody } from './delivery'
import { draftOwnsSubmission } from './useComposerDraft'
import { shareMessages } from './historyCache'
import { HistorySync, type HistoryTarget } from './historySync'
import { admittedId, isAdmitted, pendingSend, type PendingSend } from './pending'
export type { PendingSend } from './pending'

/** Where a page continues from: older or newer than a message; null is the first page. */
export type Cursor = { before: string } | { after: string } | null

/** Pages of the window kept in memory and on screen; loading more drops the far end. */
export const MAX_PAGES = 6

function page(anchor: string | null, cursor: Cursor): string {
  const query = new URLSearchParams()
  if (cursor && 'before' in cursor) query.set('before', cursor.before)
  else if (cursor) query.set('after', cursor.after)
  // A message opened from search comes with the messages around it.
  else if (anchor) query.set('around', anchor)
  const suffix = query.toString()
  return suffix ? `${ENDPOINT}?${suffix}` : ENDPOINT
}

/** One exchange by exact identity: a user message, or the one a reply answers. */
const exact = (id: string) => `${ENDPOINT}?message_id=${encodeURIComponent(id)}`

export function useConversation(anchor: Ref<string | null>) {
  const session = useSession()
  const queries = useQueryClient()
  const journal = computed<SubmissionJournal | null>(() => session.account?.journal ?? null)
  let disposed = false
  onBeforeUnmount(() => {
    disposed = true
  })

  const history = useInfiniteQuery({
    queryKey: computed(() => ['conversation', 'history', anchor.value]),
    initialPageParam: null as Cursor,
    queryFn: async ({ pageParam, signal }): Promise<History> =>
      History.parse(await api.get(page(anchor.value, pageParam), signal)),
    // Pages run newest first: "next" is older, "previous" newer.
    getNextPageParam: (last: History): Cursor | undefined =>
      last.before ? { before: last.before } : undefined,
    getPreviousPageParam: (first: History): Cursor | undefined =>
      first.after ? { after: first.after } : undefined,
    maxPages: MAX_PAGES,
    // By message id: a new message shifts every page, which index-based sharing would treat as new.
    structuralSharing: shareMessages,
  })

  // Change events refresh the exchanges they name, not every loaded page (historySync.ts).
  const sync = new HistorySync({
    client: queries,
    key: () => ['conversation', 'history', anchor.value],
    read: async (messageId, signal) =>
      History.parse(await api.get(messageId ? exact(messageId) : ENDPOINT, signal)),
    // New messages join the window only while it reaches the newest one.
    atHead: () => !history.hasPreviousPage.value,
  })
  watch(anchor, () => sync.reset())
  watch(
    () => session.account,
    (account, _, onCleanup) => {
      const release = account?.changes.claim('conversation', (event) => sync.handle(event))
      onCleanup(() => release?.())
    },
    { immediate: true },
  )
  onBeforeUnmount(() => sync.dispose())

  // Unchanged messages stay identical (structural sharing above), and so their exchanges:
  // only the messages that changed render again, not every reply on the screen.
  const built = new WeakMap<Message, Exchange>()
  function exchangeOf(message: Message): Exchange {
    const raw = toRaw(message)
    let exchange = built.get(raw)
    if (!exchange) built.set(raw, (exchange = toExchange(raw)))
    return exchange
  }
  /** Oldest first: older pages are prepended. */
  const exchanges = computed<Exchange[]>(() =>
    [...(history.data.value?.pages ?? [])]
      .reverse()
      .flatMap((part) => part.messages.map(exchangeOf)),
  )
  const known = computed(() => new Set(exchanges.value.map((exchange) => exchange.id)))

  // Journal entries of this conversation, kept in sync with this and other tabs.
  const entries = shallowRef<Submission[]>([])
  const sending = ref(false)
  const activeSends = shallowRef(new Set<string>())
  const confirmed = shallowRef(new Set<string>())
  let reloadVersion = 0
  async function reloadEntries() {
    const version = ++reloadVersion
    const account = session.account
    const active = journal.value
    const all = (await active?.pending()) ?? []
    if (
      disposed ||
      version !== reloadVersion ||
      account !== session.account ||
      active !== journal.value
    )
      return
    entries.value = all
      .filter((entry) => entry.endpoint === ENDPOINT)
      .sort((a, b) => a.createdAt - b.createdAt)
  }
  let unsubscribe: (() => void) | undefined
  let unregisterRecovery: (() => void) | undefined
  watch(
    journal,
    (current) => {
      unsubscribe?.()
      unregisterRecovery?.()
      entries.value = []
      sending.value = false
      activeSends.value = new Set()
      confirmed.value = new Set()
      unsubscribe = current?.subscribe(() => void reloadEntries().catch(() => undefined))
      const account = session.account
      const isCurrent = () => !disposed && session.account === account && journal.value === current
      unregisterRecovery = account?.reconciliation.register(ENDPOINT, (entry, signal) =>
        isCurrent() ? verifyAdmission(entry, signal) : Promise.resolve(false),
      )
      void reloadEntries().catch(() => undefined)
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    unsubscribe?.()
    unregisterRecovery?.()
  })

  const pending = computed<PendingSend[]>(() =>
    entries.value
      // An admitted message shows up in history by itself; its entry is no longer needed.
      .filter((entry) => !(isAdmitted(entry) && inHistory(admittedId(entry))))
      .map((entry) => pendingSend(entry, activeSends.value.has(entry.key))),
  )

  function inHistory(id: string | null): boolean {
    return !!id && (known.value.has(id) || confirmed.value.has(id))
  }

  // Confirm delivery by exact identity even when the original message is outside the loaded page.
  // This reads history only; an admission receipt is never interpreted as current queue status.
  async function verifyAdmission(entry: Submission, signal: AbortSignal): Promise<boolean> {
    const account = session.account
    const id = admittedId(entry)
    signal.throwIfAborted()
    if (!account || !id || disposed) return false
    if (inHistory(id)) {
      await acknowledgeVisible()
      return true
    }
    const result = History.parse(await api.get(exact(id), signal))
    signal.throwIfAborted()
    if (disposed || session.account !== account || !result.messages.some((item) => item.id === id))
      return false
    confirmed.value = new Set([...confirmed.value, id])
    await acknowledgeVisible()
    void sync.deliver(result.messages)
    return true
  }

  // A durable history message owns an admitted send, after the composer handoff is also durable.
  async function acknowledgeVisible() {
    const account = session.account
    if (!account || disposed) return
    for (const entry of entries.value) {
      const id = admittedId(entry)
      if (
        isAdmitted(entry) &&
        id &&
        inHistory(id) &&
        !(await draftOwnsSubmission(account.store, entry.key)) &&
        session.account === account &&
        !disposed
      )
        await account.journal.acknowledge(entry.key).catch(() => undefined)
    }
  }
  watch([entries, known], () => {
    void acknowledgeVisible().catch(() => undefined)
  })

  function accountGuard() {
    const account = session.account
    if (!account) throw new Error('No session')
    return {
      account,
      beforeSend: () => {
        if (disposed || session.account !== account) throw new Error('Conversation account changed')
      },
    }
  }

  async function send(
    body: MessageBody,
    photo: Blob | null,
    key: string = crypto.randomUUID(),
    sourceGuard?: () => void,
  ) {
    const { account, beforeSend } = accountGuard()
    sending.value = true
    activeSends.value = new Set([...activeSends.value, key])
    try {
      const outcome = await deliverMessage(
        account.journal,
        api,
        body,
        photo,
        key,
        () => {
          beforeSend()
          sourceGuard?.()
        },
        account.store.userId,
      )
      const id = admittedId(outcome.submission)
      void sync.touch(id ? { messages: [id] } : { head: true })
      return outcome
    } finally {
      if (!disposed && session.account === account) {
        activeSends.value = new Set([...activeSends.value].filter((item) => item !== key))
        sending.value = false
      }
    }
  }

  const check = async (key: string) => {
    const { account, beforeSend } = accountGuard()
    await account.reconciliation.check(key)
    beforeSend()
    void sync.touch({ head: true })
  }
  const resend = async (key: string) => {
    const { account, beforeSend } = accountGuard()
    activeSends.value = new Set([...activeSends.value, key])
    try {
      await account.journal.retry(key, beforeSend, account.store.userId)
      void sync.touch({ head: true })
    } finally {
      if (!disposed && session.account === account)
        activeSends.value = new Set([...activeSends.value].filter((item) => item !== key))
    }
  }
  const dismiss = async (key: string): Promise<boolean> => {
    const { account, beforeSend } = accountGuard()
    const entry = (await account.journal.pending()).find((entry) => entry.key === key)
    beforeSend()
    if (!entry) return true
    if (isAdmitted(entry) && (await draftOwnsSubmission(account.store, key))) return false
    beforeSend()
    await account.journal.acknowledge(key)
    return true
  }
  const abandon = (key: string) => journal.value?.abandon(key)

  /** Settles a turn with an unknown outcome; the server looks it up and never re-executes it. */
  async function checkTurn(turnId: string) {
    await api.get(`/v1/conversation/turns/${encodeURIComponent(turnId)}`)
    await sync.touch({ turns: [turnId] })
  }

  /** Reads the named exchanges (or the newest page) again, without reloading the rest. */
  const refresh = (target: HistoryTarget) => sync.touch(target)

  return {
    history,
    exchanges,
    pending,
    sending,
    send,
    check,
    resend,
    dismiss,
    abandon,
    checkTurn,
    refresh,
    /** Moves the window back to the newest messages. */
    latest: () => sync.latest(),
    acknowledgeVisible,
  }
}
