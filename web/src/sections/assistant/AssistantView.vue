<script setup lang="ts">
import { ArrowDown, Search } from '@lucide/vue'
import { computed, onBeforeUnmount, provide, ref, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import { Button } from '@/components/ui/button'
import NotificationCard from '@/sections/notifications/NotificationCard.vue'
import LimitNotice from '@/sections/settings/LimitNotice.vue'
import { registerComposerDictation } from '@/app/voice'
import { useSession } from '@/stores/session'
import { cn } from '@/lib/utils'
import { useRoomFor } from '@/core/layout'
import { useMinuteNow } from '@/core/useCalendarDay'
import TodayRail from '@/sections/today/TodayRail.vue'
import Composer from './Composer.vue'
import { useAvailability } from './availability'
import AvailabilityNotice from './AvailabilityNotice.vue'
import { conversationBusy, exchangeActions, revealedReply } from './busy'
import ExchangeItem from './ExchangeItem.vue'
import { conversationScroller } from './follow'
import PendingSend from './PendingSend.vue'
import SearchSheet from './SearchSheet.vue'
import { quiet } from './daySuggestions'
import Suggestions from './Suggestions.vue'
import { useConversation } from './useConversation'
import { useComposerDraft } from './useComposerDraft'
import { conversationDays } from './timeline'
import { feedRows } from './feedRows'
import { useConversationControls } from './useConversationControls'
import PendingControl from './PendingControl.vue'
import { useAssistantMood } from './useAssistantMood'
import { useChatNotices } from './useChatNotices'
import { useComposerPhoto } from './useComposerPhoto'
import { useConversationActions } from './useConversationActions'
import { useFeedVirtualizer } from './useFeedVirtualizer'

const { t, tm, rt } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
// The conversation's column plus the day beside it, counted in the root font's rem: at large text
// sizes the rail gives way to the conversation.
const layout = useTemplateRef<HTMLElement>('layout')
const wide = useRoomFor(layout, 62)

// A message opened from search is shown in its own page of history.
const anchor = computed(() =>
  typeof route.query.message === 'string' ? route.query.message : null,
)
const availability = useAvailability()
// Availability is a status, not content: any failed check is shown, even over an earlier answer,
// and does not block sending.
// eslint-disable-next-line no-restricted-syntax -- a failed refresh counts here
const availabilityFailed = computed(() => availability.isError.value)
// Busy is the first check or one the person asked for; the 15-second poll stays silent instead
// of dimming the button and adding «Проверяю…» every time.
const availabilityRefreshing = ref(false)
const availabilityBusy = computed(
  () =>
    availabilityRefreshing.value ||
    (availability.isFetching.value && !availability.data.value && !availabilityFailed.value),
)
async function refreshAvailability() {
  availabilityRefreshing.value = true
  try {
    await availability.refetch()
  } finally {
    availabilityRefreshing.value = false
  }
}
const sendBlocked = computed(
  () =>
    !availabilityFailed.value &&
    availability.data.value?.state !== undefined &&
    availability.data.value.state !== 'configured',
)
const conversation = useConversation(anchor)
const { history, exchanges, pending } = conversation
const composerDraft = useComposerDraft(
  computed(() => session.account?.store ?? null),
  computed(() => session.account?.journal ?? null),
)
const { draft, replyTo, photo } = composerDraft
const controls = useConversationControls(
  async (body, preview, messageId) => {
    if (composerDraft.recoveredExchanges.value.has(messageId)) return true
    const restored = await composerDraft.restore(body, preview, null, messageId)
    if (restored) composer.value?.focus()
    return restored
  },
  (messageId) => conversation.refresh({ messages: [messageId] }),
)
watch(composerDraft.persistedVersion, () => {
  void conversation.acknowledgeVisible().catch(() => undefined)
})
const { preparingPhoto, uploadingPhoto, attach, removePhoto } = useComposerPhoto(composerDraft)

const composer = useTemplateRef<InstanceType<typeof Composer>>('composer')
// The tab bar's microphone records into this composer while the screen is open.
onBeforeUnmount(registerComposerDictation(() => composer.value?.dictate() ?? false))
const scroller = useTemplateRef<HTMLElement>('scroller')
const content = useTemplateRef<HTMLElement>('content')
const lead = useTemplateRef<HTMLElement>('lead')
const feed = useTemplateRef<HTMLElement>('feed')
const searchOpen = ref(false)
const busy = ref(false)
provide(conversationBusy, busy)
// On a touch screen a tap shows an earlier answer's actions, one answer at a time (ReplyBlock).
provide(revealedReply, ref<string | null>(null))
// Read only by the exchanges that show such an action: a send no longer re-renders every exchange.
provide(exchangeActions, {
  busy: computed(() => busy.value || !composerDraft.ready.value),
  controlling: controls.active,
})
provide(conversationScroller, scroller)
useAssistantMood(exchanges, pending, busy, conversation.sending)
const examples = computed(() =>
  (tm('assistant.examples') as unknown[]).map((item) => rt(item as never)),
)

/** The loaded window reaches the newest message (pages further back may have been dropped). */
const atHead = computed(() => !history.hasPreviousPage.value)
const notices = useChatNotices(anchor, exchanges, history.hasNextPage, atHead)
// This browser's own sends belong at the newest end, shown only while the window reaches it.
const groups = computed(() =>
  conversationDays(exchanges.value, notices.value, atHead.value ? pending.value : [], zone.value),
)
const undated = computed(() =>
  atHead.value ? pending.value.filter((entry) => !entry.createdAt) : [],
)
/** Loaded, even if a later refresh failed: the conversation stays on screen. */
const loaded = computed(() => !history.isPending.value && !history.isLoadingError.value)
const rows = computed(() => feedRows(groups.value))
/** Only the newest answer shows its actions; away from the newest end, none does. */
const latestReply = computed(() => {
  if (!atHead.value) return null
  for (let index = exchanges.value.length - 1; index >= 0; index--) {
    const replies = exchanges.value[index]!.replies
    if (replies.length) return replies.at(-1)!.turnId
  }
  return null
})

const {
  virtualizer,
  visible,
  measure,
  scrollMargin,
  questionRoom,
  floatingDay,
  earlierFailed,
  away,
  unseen,
  showLatest,
  showQuestion,
  loadNewer,
  loadEarlier,
} = useFeedVirtualizer({ scroller, content, lead, feed, rows, anchor, atHead, conversation })
const {
  limitReached,
  historyChecking,
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
} = useConversationActions({
  conversation,
  composerDraft,
  controls,
  composer,
  busy,
  sendBlocked,
  recheckAvailability: () => availability.refetch(),
  showQuestion,
  showLatest,
})

/** The request being answered now: the composer stops it. */
const answering = computed(() =>
  exchanges.value.findLast(
    (exchange) => exchange.status === 'pending' && !exchange.queued && !!exchange.generation,
  ),
)
const stopping = computed(
  () =>
    !!answering.value &&
    (answering.value.controlState === 'accepted' || controls.active.value.has(answering.value.id)),
)
function stop() {
  const exchange = answering.value
  if (exchange) void run(() => controls.submit(exchange, 'stop'))
}

// Starting points above the field (Suggestions) on a fresh visit to the newest messages: not while
// anything is written, sent or answered, nor within half an hour of the last message.
const now = useMinuteNow()
const suggesting = computed(() => {
  const last = exchanges.value.at(-1)
  return (
    loaded.value &&
    atHead.value &&
    !anchor.value &&
    composerDraft.ready.value &&
    !sendBlocked.value &&
    !limitReached.value &&
    !draft.value.trim() &&
    !photo.value &&
    !replyTo.value &&
    !busy.value &&
    !conversation.sending.value &&
    !pending.value.length &&
    last?.status !== 'pending' &&
    quiet(last?.createdAt, now.value)
  )
})
const firstVisit = computed(() => !exchanges.value.length && !pending.value.length)

function openMessage(messageId: string) {
  return router.push({ name: 'assistant', query: { message: messageId } })
}

/** A suggested question is sent as it reads, like a typed one. */
async function ask(text: string) {
  if (!suggesting.value) return
  draft.value = text
  await send({ text, transcriptionId: null })
}

/** A suggested beginning waits in the field for the rest, typed or dictated. */
function start(text: string) {
  draft.value = text
  composer.value?.focus()
}

function retryDraftStorage() {
  // A denied initial database open left this account deliberately read-only.
  // Reloading retries the real store; it never substitutes an empty in-memory journal.
  if (session.account?.store.available === false) window.location.reload()
  else composerDraft.retryLoad()
}
</script>

<template>
  <!-- On a wide screen the day sits beside the conversation (TodayRail). -->
  <div ref="layout" class="flex min-h-0 flex-1">
    <div class="flex min-h-0 min-w-0 flex-1 flex-col">
      <PageHeader :title="t('assistant.title')" compact>
        <template #actions>
          <Button
            variant="ghost"
            size="icon"
            :aria-label="t('assistant.search')"
            @click="searchOpen = true"
          >
            <Search class="size-5" />
          </Button>
        </template>
      </PageHeader>

      <div
        ref="scroller"
        class="min-h-0 flex-1 overflow-y-auto px-4 md:px-6"
        data-testid="conversation"
      >
        <!-- While the reader goes back through history, the day of the topmost message stays in view. -->
        <div
          class="pointer-events-none sticky top-2 z-10 flex h-0 justify-center"
          aria-hidden="true"
        >
          <span
            :class="
              cn(
                'bg-muted text-muted-foreground h-fit rounded-full border px-3 py-1 text-xs font-medium shadow-sm transition-opacity duration-300 motion-reduce:transition-none',
                floatingDay.visible ? 'opacity-100' : 'opacity-0',
              )
            "
            data-testid="floating-day"
            >{{ floatingDay.label }}</span
          >
        </div>
        <div ref="content" class="relative mx-auto max-w-3xl pt-2 pb-6">
          <div ref="lead" class="space-y-5 pb-5">
            <div
              v-if="anchor"
              class="bg-muted flex flex-wrap items-center justify-between gap-2 rounded-xl px-3 py-2 text-sm"
            >
              <span>{{ t('assistant.contextOf') }}</span>
              <Button size="sm" variant="ghost" @click="router.replace({ name: 'assistant' })">
                {{ t('assistant.latest') }}
              </Button>
            </div>

            <LoadingState v-if="history.isPending.value" />
            <!-- A failed refetch keeps the loaded conversation on screen (the notice below). -->
            <ErrorState
              v-else-if="history.isLoadingError.value"
              :error="history.error.value"
              @retry="history.refetch()"
            />
            <template v-else>
              <PendingControl
                v-for="entry in controls.entries.value"
                :key="entry.key"
                :entry="entry"
                :busy="busy"
                @check="run(() => controls.check(entry.key))"
                @retry="run(() => controls.retry(entry.key))"
                @dismiss="run(() => controls.dismiss(entry.key))"
              />
              <!-- Keeps its height once no earlier page is left, so the rows below never shift. -->
              <div class="flex min-h-8 flex-col items-center gap-2">
                <Button
                  v-if="history.hasNextPage.value"
                  variant="outline"
                  size="sm"
                  :disabled="history.isFetchingNextPage.value"
                  @click="loadEarlier"
                >
                  {{ t('assistant.earlier') }}
                </Button>
                <p
                  v-if="earlierFailed"
                  class="text-muted-foreground text-center text-sm"
                  role="alert"
                  data-testid="earlier-failed"
                >
                  {{ earlierFailed }}
                </p>
              </div>
              <section
                v-if="undated.length"
                class="space-y-4 rounded-xl border p-4"
                data-testid="undated-submissions"
              >
                <p class="text-muted-foreground text-sm">{{ t('assistant.undatedSubmissions') }}</p>
                <PendingSend
                  v-for="entry in undated"
                  :key="entry.key"
                  :send="entry"
                  :zone="zone"
                  :busy="busy"
                  @check="run(() => conversation.check(entry.key))"
                  @resend="run(() => conversation.resend(entry.key))"
                  @abandon="run(async () => conversation.abandon(entry.key))"
                  @restore="run(() => restorePending(entry))"
                  @dismiss="run(() => dismissPending(entry.key))"
                  @open="openMessage"
                />
              </section>
            </template>
          </div>

          <!-- Only the rows near the screen exist (TanStack Virtual); the list keeps its full height. -->
          <div
            v-if="loaded"
            ref="feed"
            role="feed"
            :aria-busy="history.isFetching.value"
            :data-exchanges="exchanges.length"
            class="relative"
            :style="{ height: `${virtualizer.getTotalSize()}px` }"
          >
            <div
              v-for="{ virtual, row } in visible"
              :key="row.key"
              :ref="measure"
              :data-index="virtual.index"
              :class="['absolute top-0 left-0 w-full', virtual.index > 0 && 'pt-5']"
              :style="{
                transform: `translateY(${virtual.start - scrollMargin}px)`,
                minHeight:
                  questionRoom && virtual.index === rows.length - 1
                    ? `${questionRoom}px`
                    : undefined,
              }"
            >
              <div v-if="row.kind === 'day'" class="flex justify-center" :data-day="row.day">
                <h2
                  class="bg-muted text-muted-foreground rounded-full px-3 py-1 text-xs font-medium"
                >
                  {{ row.label }}
                </h2>
              </div>
              <ExchangeItem
                v-else-if="row.kind === 'exchange'"
                :exchange="row.exchange"
                :zone="zone"
                :answered="row.exchange.replyTo ? repliesById.get(row.exchange.replyTo) : null"
                :highlighted="row.exchange.id === anchor"
                :highlighted-reply-id="anchor"
                :latest-reply="latestReply"
                :admin="session.me?.system_admin"
                :recovered="composerDraft.recoveredExchanges.value.has(row.exchange.id)"
                :clarification-checking="
                  !!row.exchange.clarification &&
                  historyChecking.has(row.exchange.clarification.key)
                "
                :movable="movable(row.exchange)"
                @answer="answer"
                @confirm="confirm"
                @check="(reply) => run(() => conversation.checkTurn(reply.turnId))"
                @restore="(exchange) => run(() => restoreExchange(exchange))"
                @activity-settled="(id) => conversation.refresh({ messages: [id] })"
                @control="(exchange, action) => run(() => controls.submit(exchange, action))"
                @edit-queued="(exchange) => run(() => editQueued(exchange))"
                @send-now="sendNow"
                @check-clarification="checkClarification"
                @open="openMessage"
              />
              <NotificationCard v-else-if="row.kind === 'notice'" :notification="row.notice" feed />
              <PendingSend
                v-else
                :send="row.pending"
                :zone="zone"
                :busy="busy"
                @check="run(() => conversation.check(row.pending.key))"
                @resend="run(() => conversation.resend(row.pending.key))"
                @abandon="run(async () => conversation.abandon(row.pending.key))"
                @restore="run(() => restorePending(row.pending))"
                @dismiss="run(() => dismissPending(row.pending.key))"
                @open="openMessage"
              />
            </div>
          </div>

          <!-- Away from the newest messages: the next page, or straight back to the newest. -->
          <div v-if="loaded && !atHead" class="flex flex-wrap justify-center gap-2 pt-5">
            <Button
              variant="outline"
              size="sm"
              :disabled="history.isFetchingPreviousPage.value"
              @click="loadNewer"
            >
              {{ t('assistant.newer') }}
            </Button>
            <Button v-if="!anchor" variant="ghost" size="sm" @click="showLatest()">
              {{ t('assistant.latest') }}
            </Button>
          </div>

          <div class="space-y-5">
            <template v-if="loaded">
              <!-- Examples of what to write stand above the field (Suggestions). -->
              <EmptyState
                v-if="firstVisit && !notices.length"
                brand
                :title="t('assistant.emptyTitle')"
                :description="t('assistant.emptyText')"
              />
              <div
                v-for="entry in composerDraft.unsent.value"
                :key="entry.key"
                class="border-destructive/30 space-y-2 rounded-2xl border p-4"
                data-testid="unsent-draft"
              >
                <p class="font-medium">{{ t('assistant.unsentDraft') }}</p>
                <p class="whitespace-pre-wrap">{{ entry.body.text || '📷' }}</p>
                <div class="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    :disabled="busy"
                    @click="run(() => restoreUnsent(entry.key))"
                    >{{ t('assistant.editAgain') }}</Button
                  >
                  <Button
                    size="sm"
                    variant="ghost"
                    :disabled="busy"
                    @click="run(() => composerDraft.dismissUnsent(entry.key))"
                    >{{ t('common.close') }}</Button
                  >
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- Away from the newest messages: back down, with the answers that came meanwhile. -->
      <div v-if="away" class="pointer-events-none relative z-10 mx-auto h-0 w-full max-w-3xl">
        <div class="absolute right-4 bottom-3 md:right-6">
          <Button
            variant="secondary"
            size="icon"
            class="pointer-events-auto relative rounded-full border shadow-md"
            :aria-label="
              unseen
                ? t('assistant.newAnswers', { count: unseen }, unseen)
                : t('assistant.newMessages')
            "
            data-testid="new-messages"
            @click="showLatest()"
          >
            <ArrowDown class="size-5" />
            <span
              v-if="unseen"
              class="bg-primary text-primary-foreground absolute -top-1.5 -right-1.5 min-w-5 rounded-full px-1.5 text-center text-xs leading-5 font-semibold tabular-nums"
              aria-hidden="true"
              data-testid="new-answers"
              >{{ unseen }}</span
            >
          </Button>
        </div>
      </div>

      <div
        v-if="history.isRefetchError.value"
        class="mx-auto w-full max-w-3xl px-4 pb-2 md:px-6"
        data-testid="history-refresh-failed"
      >
        <div
          class="bg-muted flex flex-wrap items-center gap-2 rounded-xl p-3 text-sm"
          role="status"
        >
          <p class="min-w-0 flex-1">{{ t('assistant.refreshFailed') }}</p>
          <Button
            size="sm"
            variant="outline"
            :disabled="history.isFetching.value"
            @click="history.refetch()"
            >{{ t('common.retry') }}</Button
          >
        </div>
      </div>
      <div v-if="limitReached" class="mx-auto w-full max-w-3xl px-4 pb-2 md:px-6">
        <LimitNotice kind="assistant" @close="limitReached = false" />
      </div>
      <div
        v-if="availability.data.value?.state !== 'configured' || availabilityFailed"
        class="mx-auto w-full max-w-3xl px-4 pb-2 md:px-6"
      >
        <AvailabilityNotice
          :availability="availability.data.value"
          :failed="availabilityFailed"
          :busy="availabilityBusy"
          @refresh="refreshAvailability"
        />
      </div>
      <Composer
        ref="composer"
        v-model="draft"
        :reply-to="replyTo?.preview"
        :photo="photo?.url"
        :photo-pending="preparingPhoto"
        :photo-uploading="uploadingPhoto"
        :sending="busy || conversation.sending.value"
        :disabled="!session.account || !composerDraft.ready.value"
        :send-blocked="sendBlocked"
        :stoppable="!!answering"
        :stopping="stopping"
        @send="send"
        @stop="stop"
        @attach="attach"
        @clear-reply="replyTo = null"
        @remove-photo="removePhoto"
      >
        <template #suggestions>
          <Suggestions
            v-if="suggesting"
            :examples="firstVisit ? examples : []"
            @ask="ask"
            @start="start"
          />
        </template>
      </Composer>
      <div
        v-if="composerDraft.storageError.value && !composerDraft.ready.value"
        class="p-3 text-sm"
        role="alert"
      >
        {{ t('assistant.draftStorageError') }}
        <Button size="sm" variant="outline" @click="retryDraftStorage">{{
          t('common.retry')
        }}</Button>
      </div>
      <SearchSheet
        v-model:open="searchOpen"
        @pick="(id) => router.replace({ name: 'assistant', query: { message: id } })"
      />
    </div>
    <TodayRail v-if="wide" />
  </div>
</template>
