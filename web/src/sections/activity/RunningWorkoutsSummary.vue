<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { describeError } from '@/core/errors'
import { formatAmount } from '@/core/numbers'
import { duration } from '@/sections/personal/api'
import ResolutionExplanation from '@/sections/personal/ResolutionExplanation.vue'
import type { ResolvedPage } from '@/sections/personal/resolution'
import { WorkoutSummary } from './running'

const props = defineProps<{
  page?: ResolvedPage
  loading: boolean
  error?: unknown
  timezone: string
  today: string
}>()
defineEmits<{ retry: [] }>()
const { t } = useI18n()
const summary = computed(() => {
  let count = 0
  let seconds = 0
  let unknown = 0
  let durationReported = false
  for (const day of props.page?.daily ?? []) {
    if (day.kind !== 'running_workouts') continue
    const parsed = WorkoutSummary.safeParse(day.resolution.summary)
    if (parsed.success) {
      const item = parsed.data
      if (item.count !== null && Number.isSafeInteger(item.count)) count += item.count
      if (item.known_duration_seconds !== null) {
        seconds += item.known_duration_seconds
        durationReported = true
      }
      if (item.unknown_duration_count !== null && Number.isSafeInteger(item.unknown_duration_count))
        unknown += item.unknown_duration_count
    }
  }
  return { count, seconds, unknown, durationReported }
})
const unresolved = computed(
  () =>
    props.page?.daily.filter(
      (day) => day.kind === 'running_workouts' && day.resolution.status === 'conflict',
    ) ?? [],
)
</script>

<template>
  <section class="bg-card space-y-2 rounded-2xl border p-4" data-testid="running-workouts-summary">
    <h2 class="font-semibold">{{ t('activity.running.deviceWorkouts') }}</h2>
    <div v-if="loading" role="status">
      <Skeleton class="h-7 w-40" /><span class="sr-only">{{ t('state.loading') }}</span>
    </div>
    <div v-else-if="error" class="space-y-2 text-sm" role="alert">
      <p>{{ t(describeError(error)) }}</p>
      <Button size="sm" variant="outline" @click="$emit('retry')">{{ t('common.retry') }}</Button>
    </div>
    <template v-else>
      <p v-if="summary.count" class="text-lg font-semibold" data-testid="device-workout-count">
        {{ t('activity.running.count', { count: formatAmount(summary.count, 0) }) }}
      </p>
      <p
        v-if="summary.count && summary.durationReported"
        class="text-sm"
        data-testid="device-workout-duration"
      >
        {{ t('activity.knownDuration') }}: {{ duration(summary.seconds / 60) }}
        <span v-if="summary.unknown">
          · {{ t('activity.missingDuration', { count: summary.unknown }) }}</span
        >
      </p>
      <p
        v-if="summary.count === 0 && !unresolved.length"
        class="text-muted-foreground text-sm"
        data-status="unverified"
        data-testid="device-workout-status"
      >
        {{ t('activity.running.workoutStatus.unverified') }}
      </p>
      <div
        v-for="day in unresolved"
        :key="day.date"
        class="space-y-1 text-sm"
        data-testid="workout-conflict"
      >
        <p>{{ day.date }} · {{ t(`resolution.status.${day.resolution.status}`) }}</p>
        <ResolutionExplanation :resolution="day.resolution" :timezone="timezone" :today="today" />
      </div>
      <p class="text-muted-foreground text-xs">{{ t('activity.running.separateSummary') }}</p>
    </template>
  </section>
</template>
