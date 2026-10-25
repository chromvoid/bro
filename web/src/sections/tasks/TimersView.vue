<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { useDocumentVisibility, useIntervalFn } from '@vueuse/core'
import { Timer as TimerIcon, X } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import UnresolvedWrites from '@/components/UnresolvedWrites.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api } from '@/core/client'
import { errorText } from '@/core/errors'
import { operationKind, useLedger } from '@/core/operations'
import { useUndo } from '@/core/undo'
import { useDevicePush } from '@/sections/notifications/devicePush'
import { useSession } from '@/stores/session'
import { TIMERS, Timers, clock, remaining, type Timer } from './api'
import RemindersPanel from './RemindersPanel.vue'

const { t } = useI18n()
const session = useSession()
const ledger = useLedger(TIMERS)
const canWrite = computed(() => session.can('timers.write'))
const readAt = ref(performance.now())
const now = ref(performance.now())

// Running timers only; the server fires them. The page counts down from what it last read.
const running = useQuery({
  queryKey: ['timers', 'running'],
  queryFn: async ({ signal }) => {
    const result = Timers.parse(await api.get('/v1/timers/running', signal))
    readAt.value = performance.now()
    return result
  },
})
const visibility = useDocumentVisibility()
watch(visibility, (state) => state === 'visible' && void running.refetch())
useIntervalFn(() => (now.value = performance.now()), 250)

/** Timers seen running that have since ended, kept for this visit only (T13). */
const finished = ref<Array<{ id: string; label: string | null; at: number }>>([])
const timers = computed(() => running.data.value?.timers ?? [])
watch(timers, (current, previous) => {
  const still = new Set(current.map((timer) => timer.id))
  for (const timer of previous ?? [])
    if (!still.has(timer.id) && !finished.value.some((item) => item.id === timer.id))
      finished.value.unshift({ id: timer.id, label: timer.label ?? null, at: Date.now() })
})
const left = (timer: Timer) => remaining(timer, readAt.value, now.value)
const reading = (timer: Timer) => (left(timer) === 0 ? t('tasks.finished') : clock(left(timer)))
/**
 * The reading stays inside its card at any text size: 2.25rem while that fits, else a share of the
 * card's width (cqi). Hours and the finished words get the smaller share; minutes keep 2.25rem
 * wherever the card stands at 100% text.
 */
const readingSize = (timer: Timer) =>
  reading(timer).length > 5
    ? 'text-[length:min(2.25rem,21cqi)]'
    : 'text-[length:min(2.25rem,33cqi)]'
// A local count reaching zero is not the server's fire: re-read shortly after instead.
watch(
  () => timers.value.some((timer) => left(timer) === 0),
  (due) => due && setTimeout(() => void running.refetch(), 2000),
)

const minutes = ref('10')
const label = ref('')
const busy = ref(false)
const undoable = useUndo()
const presets = [1, 5, 10, 15, 30]

async function start(duration = Number(minutes.value)) {
  if (!Number.isInteger(duration) || duration < 1 || duration > 1440 || busy.value) return
  busy.value = true
  try {
    const payload: Record<string, unknown> = { duration_seconds: duration * 60 }
    if (label.value.trim()) payload.label = label.value.trim()
    await ledger.operate({ kind: 'timer.start', payload })
    label.value = ''
    toast.success(t('tasks.started'))
  } catch (error) {
    toast.error(errorText(error, t))
  } finally {
    busy.value = false
  }
}

/** A cancelled timer cannot resume; undo starts a new one for the time that was left. */
async function restart(timer: Timer) {
  const left = Math.round((Date.parse(timer.due_at) - Date.now()) / 1000)
  if (left < 1) return false
  const payload: Record<string, unknown> = { duration_seconds: left }
  if (timer.label) payload.label = timer.label
  await ledger.operate({ kind: 'timer.start', payload })
}

async function cancel(timer: Timer) {
  try {
    await ledger.operate({
      kind: 'timer.cancel',
      payload: { timer_ids: [timer.id] },
      expected_revisions: { [timer.id]: timer.revision },
    })
    undoable.confirm(t('tasks.cancelled'), () => restart(timer))
  } catch (error) {
    toast.error(errorText(error, t))
  }
}
// What happens when a timer fires depends on whether this device hears the app while it is closed.
const { state: push } = useDevicePush()
const pushText = computed(() =>
  push.value === null
    ? ''
    : t(
        push.value === 'on'
          ? 'tasks.timersPush.on'
          : push.value === 'unsupported'
            ? 'tasks.timersPush.unsupported'
            : 'tasks.timersPush.off',
      ),
)
const pushOffer = computed(() => push.value === 'off' || push.value === 'denied')
const describe = (entry: { body: string }) =>
  t('tasks.unresolved', { kind: operationKind(entry as never) })
</script>

<template>
  <PageHeader :title="t('tasks.timersTitle')" />
  <div class="mx-auto max-w-3xl space-y-5 px-4 md:px-8">
    <UnresolvedWrites
      :entries="ledger.unresolved.value"
      :describe="describe"
      :check="ledger.check"
      :resend="ledger.resend"
      :abandon="ledger.abandon"
    />

    <RefreshFailed :query="running" />
    <LoadingState v-if="running.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="running.isLoadingError.value"
      :error="running.error.value"
      @retry="running.refetch()"
    />
    <!-- Someone who may not start timers sees why the page is empty; the others see the form. -->
    <EmptyState
      v-else-if="!timers.length && !canWrite"
      :icon="TimerIcon"
      :title="t('tasks.timersEmpty')"
      :description="pushText"
    />
    <ul v-else-if="timers.length" class="grid gap-3 sm:grid-cols-2" data-testid="timers">
      <li
        v-for="timer in timers"
        :key="timer.id"
        class="bg-card flex flex-wrap items-center gap-4 rounded-3xl border p-5"
      >
        <!-- Large text gives the reading the whole row and moves the button under it. -->
        <div class="@container min-w-0 grow basis-24">
          <p class="text-muted-foreground truncate text-sm">
            {{ timer.label || t('tasks.timer') }}
          </p>
          <p
            :class="[
              'leading-[calc(2.5/2.25)] font-semibold tabular-nums',
              readingSize(timer),
              left(timer) === 0 && 'text-warning',
            ]"
            role="timer"
            :aria-label="timer.label || t('tasks.timer')"
          >
            {{ reading(timer) }}
          </p>
        </div>
        <Button
          v-if="canWrite"
          variant="ghost"
          size="icon"
          class="ms-auto"
          :aria-label="t('tasks.cancel')"
          @click="cancel(timer)"
        >
          <X class="size-5" />
        </Button>
      </li>
    </ul>

    <section v-if="finished.length" class="space-y-2" aria-labelledby="timers-recent">
      <h2 id="timers-recent" class="font-semibold">{{ t('tasks.recent') }}</h2>
      <ul class="bg-card divide-y rounded-2xl border">
        <li v-for="item in finished" :key="item.id" class="px-4 py-3">
          <p class="font-medium">{{ item.label || t('tasks.timer') }}</p>
        </li>
      </ul>
    </section>

    <section
      v-if="canWrite"
      class="bg-card @container space-y-4 rounded-3xl border p-5"
      aria-labelledby="timers-new"
    >
      <div class="space-y-1">
        <h2 id="timers-new" class="font-semibold">{{ t('tasks.newTimer') }}</h2>
        <p
          v-if="!running.isPending.value && !running.isLoadingError.value && !timers.length"
          class="text-muted-foreground text-sm"
          data-testid="timers-empty"
        >
          {{ t('tasks.timersEmpty') }}
        </p>
      </div>
      <div class="flex flex-wrap gap-2" :aria-label="t('tasks.presets')">
        <Button
          v-for="preset in presets"
          :key="preset"
          variant="outline"
          size="sm"
          :disabled="busy"
          @click="start(preset)"
        >
          {{ t('tasks.minutesShort', { count: preset }) }}
        </Button>
      </div>
      <!-- Minutes beside the label while both fit; large text puts one under the other. -->
      <form
        class="grid grid-cols-1 gap-3 @min-[14rem]:grid-cols-[6rem_1fr]"
        @submit.prevent="start()"
      >
        <div class="space-y-2">
          <Label for="timer-minutes">{{ t('tasks.minutes') }}</Label>
          <Input id="timer-minutes" v-model="minutes" inputmode="numeric" />
        </div>
        <div class="space-y-2">
          <Label for="timer-label">{{ t('tasks.label') }}</Label>
          <Input id="timer-label" v-model="label" maxlength="60" />
        </div>
        <Button type="submit" class="@min-[14rem]:col-span-2" :disabled="busy">{{
          t('tasks.start')
        }}</Button>
      </form>
      <p v-if="pushText" class="text-muted-foreground text-sm" data-testid="timers-push">
        {{ pushText }}
        <RouterLink
          v-if="pushOffer"
          :to="{ name: 'settings-notifications' }"
          class="text-foreground font-medium underline underline-offset-4"
          >{{ t('tasks.timersPush.enable') }}</RouterLink
        >
      </p>
    </section>

    <RemindersPanel :ledger="ledger" />
  </div>
</template>
