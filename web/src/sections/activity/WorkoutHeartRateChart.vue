<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import LineChart from '@/components/chart/LineChart.vue'
import { formatAmount } from '@/core/numbers'
import { workoutPoints, type WorkoutPulse } from './workoutChart'

const props = defineProps<{ samples: WorkoutPulse[]; timezone: string }>()
const { t } = useI18n()
const qualityUnknown = computed(() => props.samples.some((sample) => sample.quality === 'unknown'))
const series = computed(() =>
  (['running', 'workout', 'recovery'] as const).map((phase) => ({
    phase,
    points: workoutPoints(props.samples, phase),
  })),
)
const hasPoints = computed(() => series.value.some((item) => item.points.length > 0))
const time = (timestamp: number) =>
  new Intl.DateTimeFormat('ru-RU', {
    timeZone: props.timezone,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(timestamp)
const bpm = (value: number) => `${formatAmount(value, 0)} ${t('ring.workout.bpm')}`
</script>

<template>
  <div class="space-y-3" data-testid="workout-heart-rate-chart">
    <p
      v-if="qualityUnknown"
      class="text-warning text-sm"
      role="status"
      data-testid="workout-heart-rate-quality-warning"
    >
      {{ t('workoutPulse.qualityWarning') }}
    </p>
    <p
      v-if="samples.length && !hasPoints"
      class="text-muted-foreground text-sm"
      role="status"
      data-testid="workout-heart-rate-withheld"
    >
      {{ t('workoutPulse.noConfirmedSamples') }}
    </p>
    <template v-for="item in series" :key="item.phase">
      <section v-if="item.points.length" class="space-y-1">
        <h3 class="text-muted-foreground text-sm font-medium">
          {{ t(`ring.workout.chart.${item.phase}`) }}
        </h3>
        <LineChart
          :points="item.points"
          :label="t(`ring.workout.chart.${item.phase}`)"
          :format-x="time"
          :format-y="bpm"
          :height="140"
          points-only
        />
      </section>
    </template>
    <p class="text-muted-foreground text-xs">{{ t('ring.workout.chart.actualSamples') }}</p>
  </div>
</template>
