/** Controls own their lost receipt and edit envelope until a durable composer handoff. */
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { isKnownUnadmitted, type Submission } from '@/core/journal'
import { useSession } from '@/stores/session'
import type { Exchange } from './model'
import type { MessageBody } from './delivery'
import {
  CONTROL_ENDPOINT,
  CONTROL_LOOKUP,
  controlReceipt,
  controlTarget,
  originalEnvelope,
} from './controls'

export function useConversationControls(
  restore: (body: MessageBody, preview: string, messageId: string) => Promise<boolean>,
  /** Reads the controlled exchange again once its control has an outcome. */
  refresh: (messageId: string) => unknown,
) {
  const session = useSession()
  const entries = shallowRef<Submission[]>([])
  const active = shallowRef(new Set<string>())
  const journal = computed(() => session.account?.journal ?? null)
  let disposed = false
  let version = 0
  let unsubscribe: (() => void) | undefined
  let unregister: (() => void) | undefined

  async function reload() {
    const start = ++version
    const account = session.account
    const pending = await account?.journal.pending()
    if (!disposed && account === session.account && start === version)
      entries.value = (pending ?? []).filter((entry) => entry.endpoint === CONTROL_ENDPOINT)
  }

  async function finish(entry: Submission, signal?: AbortSignal) {
    const account = session.account
    signal?.throwIfAborted()
    if (
      !account ||
      disposed ||
      (entry.context.expectedUser && entry.context.expectedUser !== account.store.userId)
    )
      return false
    const current = await account.journal.check(
      entry.key,
      (value) => {
        const receipt = controlReceipt({ ...entry, response: value })
        return receipt?.state === 'settled' || receipt?.state === 'rejected'
          ? 'resolved'
          : 'accepted'
      },
      signal,
    )
    signal?.throwIfAborted()
    if (disposed || account !== session.account) return false
    const receipt = controlReceipt(current)
    const controlled = receipt?.message_id ?? controlTarget(entry)
    if (controlled) refresh(controlled)
    if (!receipt || receipt.state !== 'settled') return receipt?.state === 'rejected'
    if (entry.context.purpose === 'edit') {
      // Only a confirmed withdrawal may put the original envelope back in the
      // field. Persisted source body is retained during every network failure.
      if (
        receipt.action !== 'withdraw' ||
        receipt.execution?.effects !== 'none' ||
        !receipt.execution.safe_to_resubmit ||
        !receipt.message_body ||
        !(await restore(receipt.message_body, entry.context.preview ?? '', receipt.message_id))
      )
        return false
    }
    signal?.throwIfAborted()
    if (!disposed && account === session.account) await account.journal.acknowledge(entry.key)
    return true
  }

  watch(
    journal,
    (current) => {
      unsubscribe?.()
      unregister?.()
      entries.value = []
      active.value = new Set()
      const account = session.account
      const isCurrent = () => !disposed && account === session.account && current === journal.value
      unsubscribe = current?.subscribe(() => void reload().catch(() => undefined))
      unregister = account?.reconciliation.register(CONTROL_ENDPOINT, (entry, signal) =>
        isCurrent() ? finish(entry, signal) : Promise.resolve(false),
      )
      void reload().catch(() => undefined)
    },
    { immediate: true },
  )
  onBeforeUnmount(() => {
    disposed = true
    unsubscribe?.()
    unregister?.()
  })

  async function submit(
    exchange: Exchange,
    action: 'stop' | 'withdraw',
    edit = false,
    preview = '',
  ) {
    const account = session.account
    if (!account || !exchange.generation || exchange.status !== 'pending') return
    const key = crypto.randomUUID()
    active.value = new Set([...active.value, exchange.id])
    try {
      await account.journal.submit({
        endpoint: CONTROL_ENDPOINT,
        lookupPrefix: CONTROL_LOOKUP,
        found: 'accepted',
        key,
        body: { message_id: exchange.id, generation: exchange.generation, action },
        // This complete envelope is written ahead of the withdrawal POST.
        context: {
          purpose: edit ? 'edit' : action,
          envelope: JSON.stringify(originalEnvelope(exchange)),
          preview,
          expectedUser: account.store.userId,
        },
        beforeSend: () => {
          if (disposed || account !== session.account)
            throw new Error('Conversation account changed')
        },
      })
    } catch (error) {
      const entry = (await account.journal.pending()).find((item) => item.key === key)
      // Once the command may have left, its journal and lookup own recovery.
      // A lost network answer is not proof that the stop/withdraw was refused.
      if (!entry || isKnownUnadmitted(entry)) throw error
    } finally {
      active.value = new Set([...active.value].filter((id) => id !== exchange.id))
      if (!disposed && account === session.account) {
        const entry = (await account.journal.pending()).find((item) => item.key === key)
        if (entry) await finish(entry).catch(() => false)
        refresh(exchange.id)
      }
    }
  }

  async function check(key: string) {
    const account = session.account
    if (!account) return
    await account.reconciliation.check(key)
    await reload()
  }
  async function retry(key: string) {
    const account = session.account
    if (!account) return
    const entry = await account.journal.retry(
      key,
      () => {
        if (disposed || account !== session.account) throw new Error('Conversation account changed')
      },
      account.store.userId,
    )
    if (!disposed && account === session.account) await finish(entry)
  }
  async function dismiss(key: string) {
    const entry = entries.value.find((item) => item.key === key)
    const receipt = entry && controlReceipt(entry)
    if (entry && (receipt?.state === 'rejected' || isKnownUnadmitted(entry)))
      await journal.value?.acknowledge(key)
  }
  return { entries, active, submit, check, retry, dismiss }
}
