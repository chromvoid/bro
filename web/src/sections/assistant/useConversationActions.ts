/**
 * What the person does to the conversation from this screen: sends what is written, answers a
 * reply or a proposal, moves a queued message into the running request, checks a clarification,
 * and takes a message back into the composer (a failed send, a request, a queued message to edit,
 * an unsent draft). Each action leaves off once the screen closed or the account changed.
 */
import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import { dailyLimitReached } from '@/sections/settings/limits'
import { useSession } from '@/stores/session'
import { moveToRun, readHistoryClarification, type ClarificationItem } from './clarifications'
import { originalEnvelope } from './controls'
import { RejectedDelivery } from './delivery'
import type { Exchange, Reply } from './model'
import type { PendingSend } from './pending'
import type { DraftSubmission, useComposerDraft } from './useComposerDraft'
import type { useConversation } from './useConversation'
import type { useConversationControls } from './useConversationControls'

/** What the actions need of the composer on screen. */
interface ComposerField {
  focus(): void
  submitted(transcriptionId: string | null): void
}

export interface ActionsOptions {
  conversation: ReturnType<typeof useConversation>
  composerDraft: ReturnType<typeof useComposerDraft>
  controls: ReturnType<typeof useConversationControls>
  composer: Readonly<Ref<ComposerField | null>>
  /** An action of this screen is under way. */
  busy: Ref<boolean>
  sendBlocked: Readonly<Ref<boolean>>
  /** Checks the chat's availability again: a send was refused as unavailable. */
  recheckAvailability: () => unknown
  showQuestion: () => Promise<unknown>
  showLatest: () => Promise<unknown>
}

interface Composed {
  text: string
  transcriptionId: string | null
}

export function useConversationActions({
  conversation,
  composerDraft,
  controls,
  composer,
  busy,
  sendBlocked,
  recheckAvailability,
  showQuestion,
  showLatest,
}: ActionsOptions) {
  const { t } = useI18n()
  const session = useSession()
  const { exchanges } = conversation
  const { draft, replyTo, photo } = composerDraft
  let disposed = false
  onBeforeUnmount(() => {
    disposed = true
  })

  /** The request «Отправить сейчас» moves a queued message into: the running one that takes it. */
  const running = computed(() =>
    exchanges.value.findLast(
      (exchange) =>
        exchange.status === 'pending' &&
        !exchange.queued &&
        exchange.canClarify &&
        !!exchange.generation,
    ),
  )
  const historyChecking = ref<ReadonlySet<string>>(new Set())
  const clarificationReads = new Map<string, AbortController>()
  watch(
    () => session.account,
    () => {
      for (const controller of clarificationReads.values()) controller.abort()
      clarificationReads.clear()
      historyChecking.value = new Set()
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    for (const controller of clarificationReads.values()) controller.abort()
  })
  /**
   * The daily assistant limit ran out (S10); the draft stays in the field until the user sends it.
   */
  const limitReached = ref(false)

  async function checkClarification(item: ClarificationItem) {
    const account = session.account
    if (!account || historyChecking.value.has(item.key)) return
    const controller = new AbortController()
    clarificationReads.set(item.key, controller)
    historyChecking.value = new Set([...historyChecking.value, item.key])
    try {
      await readHistoryClarification(api, item, controller.signal)
      if (!disposed && session.account === account)
        await conversation.refresh({
          messages: [item.target.messageId, item.receipt.source_message_id],
        })
    } catch (error) {
      if (!controller.signal.aborted && !disposed && session.account === account)
        toast.error(errorText(error, t))
    } finally {
      if (!disposed && session.account === account) {
        clarificationReads.delete(item.key)
        historyChecking.value = new Set(
          [...historyChecking.value].filter((key) => key !== item.key),
        )
      }
    }
  }
  const repliesById = computed(() => {
    const map = new Map<string, string>()
    for (const exchange of exchanges.value)
      for (const reply of exchange.replies)
        if (reply.messageId) map.set(reply.messageId, reply.text || t('assistant.previousReply'))
    return map
  })

  /** Written while a request runs, a message waits in the queue (sendNow moves it into the run). */
  async function send({ transcriptionId }: Composed) {
    if (sendBlocked.value || busy.value || conversation.sending.value) return
    busy.value = true
    let snapshot: DraftSubmission | null = null
    let journaled = false
    try {
      snapshot = await composerDraft.prepare(transcriptionId)
      const original = snapshot
      const guard = () => {
        if (!composerDraft.belongs(original)) throw new Error('Composer account changed')
      }
      guard()
      await conversation.send(snapshot.body, snapshot.photo, snapshot.key, guard)
      journaled = true
      if (!composerDraft.belongs(snapshot)) return
      await composerDraft.delivered(snapshot)
      composer.value?.submitted(transcriptionId)
      await showQuestion()
    } catch (error) {
      if (snapshot && !composerDraft.belongs(snapshot)) return
      if (snapshot) {
        await composerDraft
          .failed(snapshot, journaled || error instanceof RejectedDelivery)
          .catch(() => undefined)
        if (error instanceof RejectedDelivery && composerDraft.owns(snapshot))
          await conversation.dismiss(error.submission.key)
      }
      await sendingFailed(error instanceof RejectedDelivery ? error.reason : error)
    } finally {
      busy.value = false
    }
  }

  /** Keys of «Отправить сейчас» without an answer yet: trying again repeats the same request. */
  const moveKeys = new Map<string, string>()

  /** «Отправить сейчас» moves a queued message of text alone into the running request. */
  function movable(exchange: Exchange) {
    return (
      !!running.value &&
      exchange.queued &&
      !exchange.clarification &&
      !exchange.replyTo &&
      !exchange.images.length &&
      !!exchange.text.trim()
    )
  }

  /**
   * «Отправить сейчас»: the queued message goes into the running request instead of waiting for it
   * to end. A refusal (the request ended meanwhile) leaves it in the queue; after any outcome the
   * history shows which.
   */
  async function sendNow(exchange: Exchange) {
    const target = running.value
    const account = session.account
    if (!target?.generation || !account || !movable(exchange) || busy.value) return
    const key = moveKeys.get(exchange.id) ?? crypto.randomUUID()
    moveKeys.set(exchange.id, key)
    busy.value = true
    try {
      await moveToRun(
        api,
        { message_id: target.id, generation: target.generation, queued_message_id: exchange.id },
        key,
        {
          expectedUser: account.store.userId,
          beforeSend: () => {
            if (disposed || session.account !== account)
              throw new Error('Conversation account changed')
          },
        },
      )
      moveKeys.delete(exchange.id)
    } catch (error) {
      if (disposed || session.account !== account) return
      // A refusal changed nothing; without an answer the move may have happened.
      if (error instanceof ApiError && error.status < 500) moveKeys.delete(exchange.id)
      toast.error(errorText(error, t))
    } finally {
      busy.value = false
    }
    if (!disposed && session.account === account)
      await conversation.refresh({ messages: [exchange.id, target.id] }).catch(() => undefined)
  }

  async function sendingFailed(error: unknown) {
    if (
      error instanceof ApiError &&
      error.status === 429 &&
      (await dailyLimitReached('assistant', () => api.get('/v1/me/limits')))
    )
      limitReached.value = true
    else if (error instanceof ApiError && error.code === 'chat_unavailable')
      void recheckAvailability()
    else toast.error(errorText(error, t))
  }

  function answer(reply: Reply) {
    if (!reply.messageId) return
    replyTo.value = {
      messageId: reply.messageId,
      preview: reply.text || t('assistant.previousReply'),
    }
    composer.value?.focus()
  }

  async function confirm(reply: Reply, accept: boolean) {
    if (sendBlocked.value || !reply.messageId || busy.value || conversation.sending.value) return
    const account = session.account
    // The consent is bound to this proposal by answering its reply.
    busy.value = true
    try {
      await conversation.send(
        {
          text: accept ? t('assistant.confirmText') : t('assistant.declineText'),
          reply_to_message_id: reply.messageId,
        },
        null,
      )
      if (disposed || session.account !== account) return
      await showLatest()
    } catch (error) {
      if (disposed || session.account !== account) return
      await sendingFailed(error instanceof RejectedDelivery ? error.reason : error)
    } finally {
      busy.value = false
    }
  }

  async function run(task: () => Promise<unknown>) {
    busy.value = true
    try {
      await task()
    } catch (error) {
      toast.error(errorText(error, t))
    } finally {
      busy.value = false
    }
  }

  async function restorePending(entry: PendingSend) {
    const account = session.account
    const restored = await recoverDraft(() =>
      composerDraft.restore(
        entry.body,
        repliesById.value.get(entry.body.reply_to_message_id ?? '') ?? t('assistant.previousReply'),
      ),
    )
    if (!restored || disposed || session.account !== account) return
    await conversation.dismiss(entry.key)
    if (!disposed && session.account === account) composer.value?.focus()
  }

  async function dismissPending(key: string) {
    if (!(await conversation.dismiss(key))) toast.info(t('assistant.pendingSaving'))
  }

  async function restoreExchange(exchange: Exchange) {
    if (!exchange.canRestore || composerDraft.recoveredExchanges.value.has(exchange.id)) return
    const account = session.account
    const restored = await recoverDraft(() =>
      composerDraft.restore(
        originalEnvelope(exchange),
        repliesById.value.get(exchange.replyTo ?? '') ?? t('assistant.previousReply'),
        null,
        exchange.id,
      ),
    )
    if (restored && !disposed && session.account === account) composer.value?.focus()
  }

  async function editQueued(exchange: Exchange) {
    if (!composerDraft.ready.value || draft.value || photo.value || replyTo.value) {
      toast.info(t('assistant.draftOccupied'))
      return
    }
    await controls.submit(
      exchange,
      'withdraw',
      true,
      repliesById.value.get(exchange.replyTo ?? '') ?? t('assistant.previousReply'),
    )
  }

  async function restoreUnsent(key: string) {
    const account = session.account
    if (
      (await recoverDraft(() => composerDraft.restoreUnsent(key))) &&
      !disposed &&
      session.account === account
    )
      composer.value?.focus()
  }

  async function recoverDraft(task: () => Promise<boolean>): Promise<boolean> {
    const account = session.account
    try {
      const restored = await task()
      if (disposed || session.account !== account) return false
      if (!restored) toast.info(t('assistant.draftOccupied'))
      return restored
    } catch {
      if (!disposed && session.account === account) toast.error(t('assistant.draftRestoreError'))
      return false
    }
  }

  return {
    limitReached,
    /** Clarifications of earlier messages being checked, by key. */
    historyChecking,
    /** The text of each reply by its message id, for the requests that answer one. */
    repliesById,
    run,
    send,
    answer,
    confirm,
    movable,
    sendNow,
    checkClarification,
    restorePending,
    dismissPending,
    restoreExchange,
    editQueued,
    restoreUnsent,
  }
}
