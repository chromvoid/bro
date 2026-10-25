<script setup lang="ts">
import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, onBeforeUnmount, ref, shallowRef, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnknownOutcome from '@/components/state/UnknownOutcome.vue'
import { Button } from '@/components/ui/button'
import LinkedText from '@/components/LinkedText'
import { Textarea } from '@/components/ui/textarea'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { ApiError } from '@/core/http'
import type { Submission } from '@/core/journal'
import { useDraft } from '@/core/useDraft'
import { History } from '@/sections/circle/api'
import { useChannelRead } from '@/sections/circle/useChannelRead'
import { useSession } from '@/stores/session'
const props = withDefaults(
  defineProps<{ channelId: string | null; error?: unknown; canWrite: boolean; active?: boolean }>(),
  { active: false },
)
const emit = defineEmits<{ retry: [] }>()
const { t } = useI18n()
const session = useSession()
const queries = useQueryClient()
const history = useInfiniteQuery({
  initialPageParam: null as string | null,
  queryKey: computed(() => ['community', 'messages', 'task', props.channelId]),
  enabled: computed(() => !!props.channelId),
  refetchInterval: 15_000,
  queryFn: async ({ signal, pageParam }) =>
    History.parse(
      await api.get(
        `/v1/channels/${encodeURIComponent(props.channelId!)}/messages${pageParam ? `?before=${encodeURIComponent(pageParam)}` : ''}`,
        signal,
      ),
    ),
  getNextPageParam: (page) => page.next_cursor,
})
const messages = computed(() =>
  [...(history.data.value?.pages ?? [])].reverse().flatMap((page) => page.messages),
)
// Only page zero is the newest fetched page. Loading an older page never advances this marker.
const latest = computed(() => history.data.value?.pages[0]?.messages.at(-1)?.id ?? null)
const readMarker = useTemplateRef<HTMLElement>('readMarker')
useChannelRead(() => props.channelId, latest, readMarker, {
  enabled: () =>
    props.active &&
    !props.error &&
    !history.isLoadingError.value &&
    history.data.value?.pages[0]?.channel?.id === props.channelId,
  onRead: () => {
    void queries.invalidateQueries({ queryKey: ['community', 'channels'] })
    void queries.invalidateQueries({ queryKey: ['tasks'] })
  },
})
const draft = useDraft(
  computed(() => (props.channelId ? `channel:${props.channelId}` : null)),
  () => ({ text: '' }),
)
const journal = computed(() => session.account?.journal ?? null)
const endpoint = computed(
  () => `/v1/channels/${encodeURIComponent(props.channelId ?? '')}/messages`,
)
const unsent = shallowRef<Submission[]>([])
const sending = ref(false)
let generation = 0
let mounted = true
watch(
  [() => props.channelId, journal],
  () => {
    generation++
    sending.value = false
    unsent.value = []
  },
  { flush: 'sync' },
)
/** An earlier channel's response never changes the discussion now on screen. */
function currentScope() {
  const started = generation
  return () => mounted && generation === started
}
let unsubscribe: (() => void) | undefined
async function reload() {
  const current = currentScope()
  const target = endpoint.value
  const entries = (await journal.value?.pending()) ?? []
  if (current())
    unsent.value = entries.filter(
      (entry) => entry.endpoint === target && entry.state === 'uncertain',
    )
}
watch(
  [journal, endpoint],
  () => {
    unsubscribe?.()
    unsubscribe = journal.value?.subscribe(() => void reload().catch(() => undefined))
    void reload().catch(() => undefined)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  mounted = false
  unsubscribe?.()
})
async function send(assistant: boolean) {
  const submittedDraft = draft.value.value
  const rawText = submittedDraft.text
  const text = rawText.trim()
  const active = journal.value
  if (!text || !active || !props.channelId || sending.value || !props.canWrite) return
  const target = endpoint.value
  const draftKey = `channel:${props.channelId}`
  const current = currentScope()
  const sameDraft = () => draft.value.value === submittedDraft && submittedDraft.text === rawText
  const key = crypto.randomUUID()
  sending.value = true
  try {
    await active.submit({
      endpoint: target,
      lookupPrefix: null,
      key,
      body: { text, ...(assistant ? { assistant: true } : {}) },
    })
    await active.acknowledge(key)
    if (!current()) return
    if (sameDraft()) {
      submittedDraft.text = ''
      await draft.clear(draftKey)
    }
    if (current()) void history.refetch()
  } catch (error) {
    if (error instanceof ApiError && error.status < 500) {
      await active.acknowledge(key).catch(() => undefined)
      if (current()) toast.error(errorText(error, t))
    } else if (current()) {
      if (sameDraft()) submittedDraft.text = ''
      toast.error(t('circle.unsentText'))
    }
  } finally {
    if (current()) sending.value = false
  }
}
async function resend(key: string) {
  const active = journal.value
  const current = currentScope()
  try {
    await active?.retry(key)
    await active?.acknowledge(key)
    if (current()) void history.refetch()
  } catch (error) {
    if (current()) toast.error(errorText(error, t))
  }
}
const unsentText = (entry: Submission) => {
  try {
    return String((JSON.parse(entry.body) as { text?: unknown }).text ?? '')
  } catch {
    return ''
  }
}
async function loadEarlier() {
  const current = currentScope()
  const result = await history.fetchNextPage()
  if (current() && result.isError) toast.error(errorText(result.error, t))
}
async function check() {
  const current = currentScope()
  await history.refetch()
  if (current()) toast(t('tasks.discussionChecked'))
}
</script>
<template>
  <section class="space-y-3">
    <p class="text-muted-foreground text-sm">{{ t('tasks.discussionAudience') }}</p>
    <ErrorState
      v-if="error || history.isLoadingError.value"
      :error="error || history.error.value"
      @retry="error ? emit('retry') : history.refetch()"
    />
    <LoadingState v-else-if="history.isPending.value" :rows="2" />
    <template v-else>
      <RefreshFailed :query="history" />
      <Button
        v-if="history.hasNextPage.value"
        variant="ghost"
        size="sm"
        :disabled="history.isFetchingNextPage.value"
        @click="loadEarlier"
        >{{ t('tasks.earlierMessages') }}</Button
      >
      <p v-if="!messages.length" class="text-muted-foreground text-sm">
        {{ t('circle.startTalking') }}
      </p>
      <article
        v-for="message in messages"
        :key="message.id"
        class="bg-muted/40 space-y-1 rounded-xl p-3"
      >
        <p class="text-muted-foreground text-xs">
          {{
            message.author === 'assistant'
              ? t('circle.assistant')
              : message.actor_user_id === session.me?.id
                ? t('circle.you')
                : (message.name ?? t('circle.participant'))
          }}
          ·
          {{
            new Date(message.created_at).toLocaleString('ru-RU', {
              timeZone: session.me?.timezone ?? 'UTC',
            })
          }}
        </p>
        <p class="break-words whitespace-pre-wrap">
          <LinkedText :text="message.text ?? ''" />
        </p>
      </article>
      <span
        v-if="latest"
        :key="latest"
        ref="readMarker"
        class="block h-px"
        aria-hidden="true"
        data-testid="task-discussion-read-marker"
      />
    </template>
    <div v-for="entry in unsent" :key="entry.key" class="space-y-2">
      <p class="text-sm whitespace-pre-wrap">{{ unsentText(entry) }}</p>
      <UnknownOutcome
        can-resend
        :description="t('circle.unsentText')"
        @check="check"
        @resend="resend(entry.key)"
      /><Button size="sm" variant="ghost" @click="journal?.abandon(entry.key)">{{
        t('circle.dontSend')
      }}</Button>
    </div>
    <form v-if="canWrite && channelId" class="space-y-2" @submit.prevent="send(false)">
      <Textarea
        v-model="draft.value.value.text"
        maxlength="4000"
        :disabled="sending"
        :aria-label="t('circle.message')"
        :placeholder="t('circle.message')"
      />
      <div class="flex flex-wrap gap-2">
        <Button type="submit" :disabled="sending || !draft.value.value.text.trim()">{{
          t('circle.send')
        }}</Button
        ><Button
          type="button"
          variant="outline"
          :disabled="sending || !draft.value.value.text.trim()"
          @click="send(true)"
          >{{ t('circle.askAssistant') }}</Button
        >
      </div>
    </form>
  </section>
</template>
