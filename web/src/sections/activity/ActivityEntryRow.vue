<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, type LocationQueryRaw } from 'vue-router'
import { timeIn } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { distanceText, useDisplayUnits } from '@/sections/personal/displayUnits'
import type { JournalEntry, WorkoutEntry } from './running'
import { workoutTitle } from './sports'
import { PhoneWorkoutOrigin } from './workoutHeartRate'

const props = defineProps<{
  entry: JournalEntry
  timezone: string
  returnQuery: LocationQueryRaw
}>()
const emit = defineEmits<{ open: [report: WorkoutEntry]; activate: [id: string] }>()
const { t } = useI18n()
const displayUnits = useDisplayUnits()
const manual = computed(() => (props.entry.type === 'manual' ? props.entry.session : null))
const report = computed(() => (props.entry.type === 'device' ? props.entry.report : null))
const title = computed(() =>
  manual.value
    ? manual.value.title || t(`activity.kinds.${manual.value.kind}`)
    : workoutTitle(t, report.value?.data.sport_type ?? 'running'),
)
const source = computed(() => {
  if (manual.value) return t('activityRedesign.manual')
  if (report.value?.source_name) return report.value.source_name
  return t(
    PhoneWorkoutOrigin.safeParse(report.value?.origin).success
      ? 'ring.workout.phoneSource'
      : 'activity.running.ringSource',
  )
})
const facts = computed(() => {
  const item = manual.value
  const device = report.value
  const pieces: string[] = []
  if (item) {
    pieces.push(t(`activity.kinds.${item.kind}`))
    if (item.started_at) pieces.push(timeIn(item.timezone, item.started_at))
    if (item.duration_minutes !== null)
      pieces.push(`${formatAmount(item.duration_minutes)} ${t('activity.minutes')}`)
    const distance = distanceText(
      item.distance_km === null ? null : Number(item.distance_km) * 1000,
      displayUnits.value,
    )
    if (distance !== null) pieces.push(distance)
    if (item.kind === 'strength')
      pieces.push(t('activity.exerciseCount', { count: item.exercises.length }))
  } else if (device) {
    pieces.push(timeIn(device.timezone ?? props.timezone, device.start_at))
    if (device.data.duration_seconds !== null)
      pieces.push(`${formatAmount(device.data.duration_seconds / 60, 1)} ${t('activity.minutes')}`)
    const distance = distanceText(device.data.distance_meters, displayUnits.value)
    if (distance !== null) pieces.push(distance)
    if (device.data.duration_seconds === null && device.data.distance_meters === null)
      pieces.push(t('activity.running.unknownDuration'))
  }
  return pieces.join(' · ')
})
function activate() {
  emit('activate', props.entry.id)
  if (report.value) emit('open', report.value)
}
</script>

<template>
  <component
    :is="manual ? RouterLink : 'button'"
    :id="`activity-entry-${entry.id}`"
    :to="
      manual
        ? { name: 'activity-session', params: { session: manual.id }, query: returnQuery }
        : undefined
    "
    :type="manual ? undefined : 'button'"
    class="bg-card hover:bg-muted focus-visible:ring-ring/50 flex min-h-11 w-full items-center gap-3 rounded-2xl border p-3 text-left transition-colors outline-none focus-visible:ring-3 sm:p-4"
    data-testid="activity-entry-row"
    :data-origin="entry.type"
    @click="activate"
  >
    <div class="min-w-0 flex-1 space-y-1">
      <p class="font-semibold break-words">{{ title }}</p>
      <p class="text-muted-foreground text-sm break-words">{{ facts }}</p>
      <p class="text-muted-foreground text-xs break-words">{{ source }}</p>
    </div>
    <ChevronRight class="text-muted-foreground size-5 shrink-0" />
  </component>
</template>
