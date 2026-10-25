<script setup lang="ts">
import { Bot, CornerDownRight, Phone, PhoneMissed, Send, X } from '@lucide/vue'
import { useQueryClient } from '@tanstack/vue-query'
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { toast } from 'vue-sonner'
import { calls } from '@/calls/store'
import { useCallsEnabled } from '@/calls/useCalls'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnknownOutcome from '@/components/state/UnknownOutcome.vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { dayIn, dayLabel, timeIn } from '@/core/dates'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import type { Submission } from '@/core/journal'
import { useDraft } from '@/core/useDraft'
import { useSession } from '@/stores/session'
import { audienceOf, callLine, type Message } from './api'
import GoneState from './GoneState.vue'
import ReportButton from './ReportButton.vue'
import { isGone, useChannels, useMessages, useSpaces } from './useCircle'
import { useChannelRead } from './useChannelRead'

/**
 * G07/G08: a chat with its audience always in view. G09/G22: the assistant answers only when
 * asked with the separate button, after the user sees where the answer will appear. Each chat
 * keeps its own draft.
 */
const { t } = useI18n()
const route = useRoute()
const session = useSession()
const queries = useQueryClient()
const channelId = computed(() => String(route.params.channel ?? ''))
const channels = useChannels()
const spaces = useSpaces()
const history = useMessages(channelId)
const channel = computed(
  () => channels.data.value?.channels.find((item) => item.id === channelId.value) ?? null,
)
const spaceName = computed(
  () =>
    spaces.data.value?.spaces.find((space) => space.id === channel.value?.space_id)?.name ?? null,
)
const audience = computed(() => (channel.value ? audienceOf(channel.value, spaceName.value) : null))
const back = computed(() =>
  channel.value?.space_id
    ? { name: 'circle-space', params: { space: channel.value.space_id } }
    : { name: 'circle-friends' },
)
const zone = computed(() => session.me?.timezone ?? 'UTC')
// A direct chat calls the other person; the tap itself asks for the microphone.
const callsEnabled = useCallsEnabled()
const dial = () => calls.dial({ channelId: channelId.value, peerName: audience.value?.title ?? '' })
const today = computed(() => dayIn(zone.value))
const gone = computed(() => isGone(history.error.value))

const draft = useDraft(
  computed(() => (channelId.value ? `channel:${channelId.value}` : null)),
  () => ({ text: '' }),
)
const replyTo = shallowRef<Message | null>(null)
const messages = computed(() => history.data.value?.messages ?? [])
const byId = computed(() => new Map(messages.value.map((message) => [message.id, message])))
const author = (message: Message) =>
  message.author === 'assistant'
    ? t('circle.assistant')
    : message.actor_user_id === session.me?.id
      ? t('circle.you')
      : (message.name ?? t('circle.participant'))

// Keep the newest messages in view; reading them marks the chat read.
const list = useTemplateRef<HTMLElement>('list')
const readMarker = useTemplateRef<HTMLElement>('readMarker')
const lastMessage = computed(() => messages.value.at(-1)?.id ?? null)
watch(
  lastMessage,
  async () => {
    await nextTick()
    list.value?.scrollTo?.({ top: list.value.scrollHeight })
  },
  { immediate: true },
)
useChannelRead(channelId, lastMessage, readMarker, {
  root: list,
  enabled: () => !gone.value && history.data.value?.channel?.id === channelId.value,
  onRead: () => {
    void channels.refetch()
    void queries.invalidateQueries({ queryKey: ['tasks'] })
  },
})

// Sending: the exact request is journaled; the server treats a repeated key as the same message.
const endpoint = computed(() => `/v1/channels/${encodeURIComponent(channelId.value)}/messages`)
const journal = computed(() => session.account?.journal ?? null)
const unsent = shallowRef<Submission[]>([])
async function reloadUnsent() {
  unsent.value = ((await journal.value?.pending()) ?? []).filter(
    (entry) => entry.endpoint === endpoint.value && entry.state === 'uncertain',
  )
}
let unsubscribe: (() => void) | undefined
watch(
  [journal, endpoint],
  () => {
    unsubscribe?.()
    unsubscribe = journal.value?.subscribe(() => void reloadUnsent().catch(() => undefined))
    void reloadUnsent().catch(() => undefined)
  },
  { immediate: true },
)
onBeforeUnmount(() => unsubscribe?.())

const sending = ref(false)
const asking = ref(false)
async function send(assistant: boolean) {
  const text = draft.value.value.text.trim()
  const active = journal.value
  if (!text || !active || sending.value) return
  const body: Record<string, unknown> = { text }
  if (assistant) body.assistant = true
  if (replyTo.value) body.reply_to_id = replyTo.value.id
  const key = crypto.randomUUID()
  sending.value = true
  asking.value = false
  // The text now lives in the journal entry until the server has it.
  draft.value.value.text = ''
  replyTo.value = null
  try {
    await active.submit({ endpoint: endpoint.value, lookupPrefix: null, body, key })
    await active.acknowledge(key)
    await draft.clear()
    void history.refetch()
  } catch (error) {
    if (error instanceof ApiError && error.status < 500) {
      // Refused: nothing was sent; the text goes back to the field with the reason.
      await active.acknowledge(key).catch(() => undefined)
      draft.value.value.text = text
      toast.error(error.status === 403 ? t('circle.friendsOnly') : errorText(error, t))
    } else if (error instanceof ApiError && error.status === 503) {
      await active.abandon(key).catch(() => undefined)
      draft.value.value.text = text
      toast.error(error.serverMessage ?? errorText(error, t))
    }
    // Otherwise the outcome is unknown: the card below offers to send the same message again.
  } finally {
    sending.value = false
  }
}

async function resend(key: string) {
  try {
    await journal.value?.retry(key)
    await journal.value?.acknowledge(key)
    void history.refetch()
  } catch (error) {
    if (error instanceof ApiError) toast.error(errorText(error, t))
  }
}
/** No lookup exists for chat messages: the history itself shows whether the message arrived. */
async function check(entry: Submission) {
  const text = unsentText(entry)
  const fresh = (await history.refetch()).data?.messages ?? []
  const arrived = fresh.some(
    (message) =>
      message.actor_user_id === session.me?.id &&
      message.text === text &&
      Date.parse(message.created_at) >= entry.createdAt - 60_000,
  )
  if (arrived) await journal.value?.abandon(entry.key)
  else toast(t('circle.notArrived'))
}
const unsentText = (entry: Submission) => {
  try {
    return String((JSON.parse(entry.body) as { text?: unknown }).text ?? '')
  } catch {
    return ''
  }
}
const when = (message: Message) => {
  const day = dayIn(zone.value, new Date(message.created_at))
  const time = timeIn(zone.value, message.created_at)
  return day === today.value ? time : `${dayLabel(day, today.value)}, ${time}`
}
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col">
    <PageHeader
      :title="audience?.title ?? t('circle.chat')"
      :description="
        audience
          ? audience.kind === 'direct'
            ? t('circle.onlyYouAnd', { name: audience.title })
            : t('circle.everyoneIn', { name: audience.title })
          : undefined
      "
      :back="back"
    >
      <template #actions>
        <ReportButton
          v-if="channel?.audience_resource_id"
          target-type="resource"
          :target-id="channel.audience_resource_id"
        />
        <Button
          v-if="channel?.kind === 'direct' && channel.peer_id && callsEnabled"
          variant="ghost"
          size="icon"
          :aria-label="t('calls.callTo', { name: audience?.title ?? '' })"
          :disabled="calls.busy.value"
          data-testid="call"
          @click="dial"
        >
          <Phone class="size-5" />
        </Button>
      </template>
    </PageHeader>
    <GoneState v-if="gone" />
    <template v-else>
      <div ref="list" class="min-h-0 flex-1 overflow-y-auto px-4 md:px-8" data-testid="messages">
        <div class="mx-auto max-w-3xl space-y-3 py-2">
          <LoadingState v-if="history.isPending.value" :rows="3" />
          <ErrorState
            v-else-if="history.isLoadingError.value"
            :error="history.error.value"
            @retry="history.refetch()"
          />
          <p v-else-if="!messages.length" class="text-muted-foreground py-8 text-center">
            {{ t('circle.startTalking') }}
          </p>
          <template v-for="message in messages" :key="message.id">
            <!-- A call is a line in the conversation, not a message: no author, bubble or reply. -->
            <p
              v-if="message.call"
              class="text-muted-foreground flex items-center justify-center gap-2 text-sm"
              data-testid="call-line"
            >
              <PhoneMissed
                v-if="message.call.outcome === 'missed' || message.call.outcome === 'cancelled'"
                class="size-4 shrink-0"
              />
              <Phone v-else class="size-4 shrink-0" />
              <span>{{ callLine(message, session.me?.id, t) }} · {{ when(message) }}</span>
            </p>
            <article
              v-else
              :class="[
                'group flex flex-col gap-1',
                message.actor_user_id === session.me?.id && message.author !== 'assistant'
                  ? 'items-end'
                  : 'items-start',
              ]"
              data-testid="message"
            >
              <p class="text-muted-foreground px-1 text-xs">
                {{ author(message) }} · {{ when(message) }}
              </p>
              <div
                :class="[
                  'max-w-[85%] rounded-2xl px-4 py-2.5',
                  message.author === 'assistant'
                    ? 'bg-card border'
                    : message.actor_user_id === session.me?.id
                      ? 'bg-accent/60 rounded-tr-md'
                      : 'bg-muted rounded-tl-md',
                ]"
              >
                <p
                  v-if="message.reply_to_id && byId.get(message.reply_to_id)"
                  class="text-muted-foreground mb-1 flex items-center gap-1 text-xs"
                >
                  <CornerDownRight class="size-3" />
                  {{ byId.get(message.reply_to_id)!.text?.slice(0, 80) }}
                </p>
                <p class="break-words whitespace-pre-wrap">{{ message.text }}</p>
              </div>
              <Button
                variant="ghost"
                size="sm"
                class="text-muted-foreground h-7 px-2 text-xs focus-visible:opacity-100 md:opacity-0 md:group-hover:opacity-100"
                @click="replyTo = message"
              >
                {{ t('circle.reply') }}
              </Button>
              <ReportButton
                v-if="channel?.kind !== 'personal' && message.actor_user_id !== session.me?.id"
                target-type="message"
                :target-id="message.id"
              />
            </article>
          </template>
          <span
            v-if="lastMessage"
            :key="lastMessage"
            ref="readMarker"
            class="block h-px"
            aria-hidden="true"
          />

          <div v-for="entry in unsent" :key="entry.key" class="space-y-2" data-testid="unsent">
            <p class="bg-accent/60 ml-auto max-w-[85%] rounded-2xl px-4 py-2.5 whitespace-pre-wrap">
              {{ unsentText(entry) }}
            </p>
            <UnknownOutcome
              can-resend
              :description="t('circle.unsentText')"
              @check="check(entry)"
              @resend="resend(entry.key)"
            />
            <Button size="sm" variant="ghost" @click="journal?.abandon(entry.key)">
              {{ t('circle.dontSend') }}
            </Button>
          </div>
        </div>
      </div>

      <form
        class="bg-background border-t px-4 pt-3 pb-3 md:px-8"
        novalidate
        @submit.prevent="send(false)"
      >
        <div class="mx-auto max-w-3xl space-y-2">
          <!-- Beside the composer, where a reader of the newest messages sees it. -->
          <RefreshFailed :query="history" />
          <p v-if="replyTo" class="text-muted-foreground flex items-center gap-2 text-sm">
            <CornerDownRight class="size-4 shrink-0" />
            <span class="min-w-0 flex-1 truncate">{{ replyTo.text }}</span>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              :aria-label="t('circle.cancelReply')"
              @click="replyTo = null"
            >
              <X class="size-4" />
            </Button>
          </p>
          <Textarea
            v-model="draft.value.value.text"
            maxlength="4000"
            rows="2"
            class="max-h-40 min-h-11 resize-none"
            :aria-label="
              audience ? t('circle.messageTo', { name: audience.title }) : t('circle.message')
            "
            :placeholder="
              audience ? t('circle.messageTo', { name: audience.title }) : t('circle.message')
            "
          />
          <div class="flex flex-wrap gap-2">
            <Button type="submit" :disabled="sending || !draft.value.value.text.trim()">
              <Send />{{ t('circle.send') }}
            </Button>
            <Button
              type="button"
              variant="outline"
              :disabled="sending || !draft.value.value.text.trim()"
              @click="asking = true"
            >
              <Bot />{{ t('circle.askAssistant') }}
            </Button>
          </div>
        </div>
      </form>
    </template>
  </div>

  <Panel v-model:open="asking" :title="t('circle.askTitle')">
    <Alert data-testid="ask-audience">
      <AlertTitle>{{
        audience?.kind === 'direct'
          ? t('circle.answerDirect', { name: audience.title })
          : t('circle.answerSpace', { name: audience?.title ?? '' })
      }}</AlertTitle>
      <AlertDescription>{{ t('circle.answerText') }}</AlertDescription>
    </Alert>
    <p class="bg-muted rounded-2xl px-4 py-3 whitespace-pre-wrap">{{ draft.value.value.text }}</p>
    <div class="grid grid-cols-1 gap-2">
      <Button size="lg" :disabled="sending" @click="send(true)">
        {{
          audience?.kind === 'direct'
            ? t('circle.askHereDirect', { name: audience.title })
            : t('circle.askHere')
        }}
      </Button>
      <Button variant="ghost" as-child>
        <RouterLink :to="{ name: 'assistant' }">{{ t('circle.askPrivately') }}</RouterLink>
      </Button>
    </div>
  </Panel>
</template>
