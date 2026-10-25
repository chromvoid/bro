<script setup lang="ts">
import { AudioLines, ChevronDown, ChevronUp, Trash2 } from '@lucide/vue'
import { useEventListener } from '@vueuse/core'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import DateField from '@/components/DateField.vue'
import { api } from '@/core/client'
import { dayIn, fullDayLabel, zoneNote } from '@/core/dates'
import { useDeveloperMode } from '@/core/developer'
import { formatBytes } from '@/core/numbers'
import { describeError } from '@/core/errors'
import { ApiError } from '@/core/http'
import { useSession } from '@/stores/session'
import { dateRange, recordingsClient, transcriptErrorKey, type Recording } from './api'
import { RecordingsArchive } from './archive'
import { conversationStatus, conversations, type Conversation } from './conversations'
import RecordingPlayer from './RecordingPlayer.vue'

const { t } = useI18n()
const session = useSession()
const developer = useDeveloperMode()
const archive = new RecordingsArchive(recordingsClient(api))
const date = ref('')
const zone = computed(() => session.me?.timezone ?? 'UTC')
/** Said only when the account's zone is not this device's. */
const zoneName = computed(() => zoneNote(zone.value))
const clock = computed(
  () =>
    new Intl.DateTimeFormat('ru', {
      timeZone: zone.value,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }),
)
const minutes = computed(
  () => new Intl.DateTimeFormat('ru', { timeZone: zone.value, hour: '2-digit', minute: '2-digit' }),
)
const owner = computed(() => (session.state === 'ready' ? (session.me?.id ?? null) : null))
const visible = ref(document.visibilityState !== 'hidden')
const confirmation = ref<'delete' | 'retry' | null>(null)
const playerRevision = ref(0)
let timer: ReturnType<typeof setTimeout> | undefined
// Fragments read as conversations (conversations.ts), by the day each one started.
const groups = computed(() => {
  const result = new Map<string, Conversation[]>()
  for (const talk of conversations(archive.items.value)) {
    const day = dayIn(zone.value, new Date(talk.start))
    const entries = result.get(day) ?? []
    entries.push(talk)
    result.set(day, entries)
  }
  return [...result.entries()]
})
/** The open conversation, by its first fragment: it stays open while new fragments arrive. */
const expanded = ref<string | null>(null)
const selected = computed(() =>
  archive.items.value.find((item) => item.id === archive.selected.value),
)
const retention = computed(() => {
  const days = archive.usage.value?.limits.retention_days
  return days == null || days === 0
    ? t('recordings.retentionUnlimited')
    : t('recordings.retention', { days })
})
const unavailable = (error: unknown) =>
  error instanceof ApiError && [404, 410].includes(error.status)
const failureText = (error: unknown) =>
  t(unavailable(error) ? 'recordings.gone' : describeError(error))
function duration(value: number) {
  const seconds = new Intl.NumberFormat('ru', { maximumFractionDigits: 1 }).format(value / 1000)
  return t('recordings.duration', { seconds })
}
function time(value: string | number) {
  return clock.value.format(new Date(value))
}
function end(item: Recording) {
  return time(Date.parse(item.started_at) + item.duration_ms)
}
// «09:04–09:12 · 8 мин» for a conversation; one minute reads «09:04 · 40 с».
function span(talk: Conversation) {
  const [from, to] = [minutes.value.format(talk.start), minutes.value.format(talk.end)]
  return from === to ? from : `${from}–${to}`
}
function length(talk: Conversation) {
  const span = talk.end - talk.start
  return span < 60_000
    ? t('recordings.duration', { seconds: Math.max(1, Math.round(span / 1000)) })
    : t('recordings.minutes', { minutes: Math.round(span / 60_000) })
}
function progress(talk: Conversation) {
  const status = conversationStatus(talk)
  return status ? t(`recordings.progress.${status.key}`, status) : null
}
function open(id: string) {
  if (archive.busy.value || archive.mutationUnknown.value) return
  confirmation.value = null
  void archive.select(archive.selected.value === id ? null : id)
}
// A conversation of one fragment opens straight to it; a longer one lists its fragments first.
function toggle(talk: Conversation) {
  if (archive.busy.value || archive.mutationUnknown.value) return
  confirmation.value = null
  const closing = expanded.value === talk.key
  expanded.value = closing ? null : talk.key
  const single = talk.fragments.length === 1 ? talk.key : null
  void archive.select(closing ? null : single)
}
function refresh() {
  if (archive.busy.value) return
  confirmation.value = null
  playerRevision.value++
  void archive.refresh()
}
async function confirm() {
  if (!selected.value || !confirmation.value) return
  if (await archive.mutate(confirmation.value, selected.value.id)) confirmation.value = null
}
function schedule() {
  clearTimeout(timer)
  if (owner.value && visible.value && archive.hasPending.value)
    timer = setTimeout(() => void archive.poll().finally(schedule), 10_000)
}
watch(
  () => [owner.value, zone.value, date.value, session.me?.device_id] as const,
  ([user, timezone, day]) => {
    confirmation.value = null
    archive.account(user, dateRange(day, timezone))
    if (user) void archive.refresh()
  },
  { immediate: true, flush: 'sync' },
)
watch(() => [visible.value, archive.hasPending.value] as const, schedule)
// Coming back to the page reads the list again: a recording made meanwhile shows up without a
// refresh button.
useEventListener(document, 'visibilitychange', () => {
  visible.value = document.visibilityState !== 'hidden'
  if (!visible.value) archive.cancelPolling()
  else if (owner.value && !archive.busy.value && !archive.loading.value) void archive.refresh()
  schedule()
})
onBeforeUnmount(() => {
  clearTimeout(timer)
  archive.account(null)
})
</script>

<template>
  <PageHeader :title="t('recordings.title')" />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <!-- Storage and retention are technical details: developer mode only. -->
    <section
      v-if="developer && archive.usage.value"
      class="bg-muted/50 space-y-1 rounded-2xl p-4 text-sm"
      data-testid="recordings-usage"
    >
      <p class="font-medium">
        {{
          t('recordings.usage', {
            used: formatBytes(archive.usage.value.used_bytes),
            maximum: formatBytes(archive.usage.value.limits.max_user_bytes),
          })
        }}
      </p>
      <p class="text-muted-foreground">{{ retention }}</p>
    </section>
    <p
      v-if="developer && archive.usageError.value"
      class="text-muted-foreground text-sm"
      role="status"
    >
      {{ t('recordings.usageFailed') }}
    </p>
    <div class="space-y-1.5">
      <Label for="recordings-date">{{ t('recordings.date') }}</Label>
      <DateField
        id="recordings-date"
        v-model="date"
        clearable
        :placeholder="t('recordings.allDates')"
        :disabled="archive.busy.value || archive.mutationUnknown.value"
        class="max-w-xs"
        :aria-describedby="zoneName ? 'recordings-zone' : undefined"
      />
      <p v-if="zoneName" id="recordings-zone" class="text-muted-foreground text-xs">
        {{ t('recordings.zone', { zone: zoneName }) }}
      </p>
    </div>
    <div
      v-if="archive.loaded.value && (archive.error.value || archive.pollError.value)"
      class="bg-muted space-y-2 rounded-2xl p-4 text-sm"
      role="status"
      data-state="refresh-failed"
    >
      <p>
        {{ t('state.refreshFailed') }}
        {{ failureText(archive.error.value ?? archive.pollError.value) }}
      </p>
      <Button variant="outline" size="sm" :disabled="archive.loading.value" @click="refresh">{{
        t('common.retry')
      }}</Button>
    </div>
    <LoadingState v-if="!archive.loaded.value && archive.loading.value" :rows="3" />
    <ErrorState
      v-else-if="!archive.loaded.value && archive.error.value"
      :error="archive.error.value"
      @retry="refresh"
    />
    <EmptyState
      v-else-if="archive.loaded.value && !archive.items.value.length"
      :icon="AudioLines"
      :title="t('recordings.emptyTitle')"
      :description="t('recordings.emptyText')"
    />
    <section
      v-for="[day, talks] in groups"
      :key="day"
      class="space-y-3"
      :aria-label="fullDayLabel(day)"
    >
      <h2 class="text-muted-foreground text-sm font-medium">{{ fullDayLabel(day) }}</h2>
      <article
        v-for="talk in talks"
        :key="talk.key"
        class="bg-card overflow-hidden rounded-3xl border"
        :data-recording="talk.key"
        :data-fragments="talk.fragments.length"
      >
        <button
          type="button"
          :disabled="archive.busy.value || archive.mutationUnknown.value"
          class="hover:bg-accent/40 flex w-full items-start gap-3 p-4 text-left"
          :aria-expanded="expanded === talk.key"
          :aria-controls="`conversation-${talk.key}`"
          @click="toggle(talk)"
        >
          <AudioLines class="text-muted-foreground mt-1 size-5 shrink-0" aria-hidden="true" />
          <span class="min-w-0 flex-1 space-y-1">
            <span class="block font-medium tabular-nums"
              >{{ span(talk) }}
              <span class="text-muted-foreground text-sm font-normal"
                >· {{ length(talk)
                }}<template v-if="talk.fragments.length > 1">
                  ·
                  {{
                    t(
                      'recordings.fragments',
                      { count: talk.fragments.length },
                      talk.fragments.length,
                    )
                  }}</template
                ></span
              ></span
            >
            <span v-if="progress(talk)" class="text-muted-foreground block text-sm">{{
              progress(talk)
            }}</span>
            <span v-if="talk.preview" class="line-clamp-3 text-sm break-words">{{
              talk.preview
            }}</span>
          </span>
          <ChevronUp
            v-if="expanded === talk.key"
            class="mt-1 size-5 shrink-0"
            aria-hidden="true"
          /><ChevronDown v-else class="mt-1 size-5 shrink-0" aria-hidden="true" />
        </button>
        <div
          v-if="expanded === talk.key && owner"
          :id="`conversation-${talk.key}`"
          class="border-t"
        >
          <template v-for="fragment in talk.fragments" :key="fragment.id">
            <button
              v-if="talk.fragments.length > 1"
              type="button"
              :disabled="archive.busy.value || archive.mutationUnknown.value"
              class="hover:bg-accent/40 flex w-full items-center gap-3 border-b px-4 py-3 text-left text-sm"
              :aria-expanded="archive.selected.value === fragment.id"
              :aria-controls="`recording-${fragment.id}`"
              :data-fragment="fragment.id"
              @click="open(fragment.id)"
            >
              <span class="min-w-0 flex-1">
                <span class="tabular-nums"
                  >{{ time(fragment.started_at) }}–{{ end(fragment) }}</span
                >
                <span class="text-muted-foreground"> · {{ duration(fragment.duration_ms) }}</span>
                <span class="text-muted-foreground block">{{
                  t(`recordings.status.${fragment.transcription.status}`)
                }}</span>
              </span>
              <ChevronUp
                v-if="archive.selected.value === fragment.id"
                class="size-4 shrink-0"
                aria-hidden="true"
              /><ChevronDown v-else class="size-4 shrink-0" aria-hidden="true" />
            </button>
            <p v-else class="text-muted-foreground px-4 pt-3 text-sm tabular-nums">
              {{ time(fragment.started_at) }}–{{ end(fragment) }} ·
              {{ duration(fragment.duration_ms) }}
            </p>
            <div
              v-if="archive.selected.value === fragment.id"
              :id="`recording-${fragment.id}`"
              class="space-y-4 border-b p-4 last:border-b-0"
            >
              <RecordingPlayer
                v-if="!unavailable(archive.detailError.value)"
                :key="`${owner}:${fragment.id}:${playerRevision}`"
                :recording="fragment"
                :owner="owner"
                @refresh="refresh"
              />
              <LoadingState
                v-if="archive.detailLoading.value && !archive.transcript.value"
                :rows="1"
              />
              <div v-else-if="archive.detailError.value" class="space-y-2 text-sm" role="alert">
                <p>{{ failureText(archive.detailError.value) }}</p>
                <Button variant="outline" size="sm" @click="archive.select(fragment.id)">{{
                  t('common.retry')
                }}</Button>
              </div>
              <div
                v-else-if="archive.transcript.value"
                class="space-y-2"
                data-testid="recording-transcript"
              >
                <h3 class="text-sm font-medium">{{ t('recordings.transcript') }}</h3>
                <p
                  v-if="archive.transcript.value.status === 'ready'"
                  class="text-sm break-words whitespace-pre-wrap"
                >
                  {{ archive.transcript.value.text }}
                </p>
                <p v-else class="text-muted-foreground text-sm" role="status">
                  {{ t(`recordings.status.${archive.transcript.value.status}`) }}
                </p>
                <p
                  v-if="archive.transcript.value.status === 'failed'"
                  class="text-muted-foreground text-sm"
                >
                  {{ t(transcriptErrorKey(archive.transcript.value.error_code)) }}
                </p>
                <Button
                  v-if="
                    ['failed', 'empty'].includes(archive.transcript.value.status) && !confirmation
                  "
                  variant="outline"
                  size="sm"
                  :disabled="archive.busy.value || archive.mutationUnknown.value"
                  @click="confirmation = 'retry'"
                  >{{ t('recordings.retry') }}</Button
                >
              </div>
              <p v-if="fragment.expires_at" class="text-muted-foreground text-xs">
                {{
                  t('recordings.expires', {
                    when: new Intl.DateTimeFormat('ru', {
                      timeZone: zone,
                      dateStyle: 'short',
                      timeStyle: 'short',
                    }).format(new Date(fragment.expires_at)),
                  })
                }}
              </p>
              <div
                v-if="confirmation"
                class="bg-muted space-y-3 rounded-2xl p-3 text-sm"
                role="alert"
                data-testid="recording-confirmation"
              >
                <p>
                  {{
                    t(
                      confirmation === 'delete'
                        ? 'recordings.deleteConfirm'
                        : archive.transcript.value?.retry_may_repeat
                          ? 'recordings.retryRepeatConfirm'
                          : 'recordings.retryConfirm',
                    )
                  }}
                </p>
                <div class="flex flex-wrap gap-2">
                  <Button
                    :variant="confirmation === 'delete' ? 'destructive' : 'default'"
                    size="sm"
                    :disabled="archive.busy.value || archive.mutationUnknown.value"
                    @click="confirm"
                    >{{
                      t(confirmation === 'delete' ? 'common.delete' : 'recordings.retry')
                    }}</Button
                  >
                  <Button
                    variant="ghost"
                    size="sm"
                    :disabled="archive.busy.value"
                    @click="confirmation = null"
                    >{{ t('common.cancel') }}</Button
                  >
                </div>
              </div>
              <div v-if="archive.mutationError.value" class="space-y-2 text-sm" role="alert">
                <p>{{ failureText(archive.mutationError.value) }}</p>
                <p v-if="archive.mutationUnknown.value">{{ t('recordings.mutationUnknown') }}</p>
                <Button variant="outline" size="sm" @click="refresh">{{
                  t('recordings.refresh')
                }}</Button>
              </div>
              <Button
                v-if="!confirmation"
                variant="ghost"
                size="sm"
                class="text-destructive"
                :disabled="archive.busy.value || archive.mutationUnknown.value"
                @click="confirmation = 'delete'"
                ><Trash2 />{{ t('recordings.delete') }}</Button
              >
            </div>
          </template>
        </div>
      </article>
    </section>
    <Button
      v-if="archive.next.value"
      variant="outline"
      class="w-full"
      :disabled="
        archive.loadingMore.value ||
        archive.loading.value ||
        archive.busy.value ||
        archive.mutationUnknown.value
      "
      @click="archive.refresh(true)"
      >{{ t(archive.loadingMore.value ? 'state.loading' : 'recordings.more') }}</Button
    >
  </div>
</template>
