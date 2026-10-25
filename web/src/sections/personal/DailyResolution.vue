<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import SleepAnalysisDetails from './SleepAnalysisDetails.vue'
import SleepAnalysisSummary from './SleepAnalysisSummary.vue'
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import { dayLabel, momentPhrase } from '@/core/dates'
import { KINDS, amountText, duration, type Kind } from './api'
import { sensorAmount } from './sensors'
import { formatAmount } from '@/core/numbers'
import type { Resolution } from './resolution'
import ResolutionExplanation from './ResolutionExplanation.vue'
import SleepSessions from './SleepSessions.vue'
import { sleepConflictKey, sleepReportConflict } from './sleep'

const displayUnits = useDisplayUnits()
const props = defineProps<{
  date: string
  kind: string
  value: string | null
  unit: string
  resolution: Resolution
  timezone: string
  today: string
}>()
const { t, te } = useI18n()
const metricLabel = computed(() =>
  te(`personal.metric.${props.kind}`)
    ? t(`personal.metric.${props.kind}`)
    : te(`ring.metric.${props.kind}`)
      ? t(`ring.metric.${props.kind}`)
      : props.kind,
)
const reportConflict = computed(() =>
  props.kind === 'sleep' ? sleepReportConflict(props.resolution) : null,
)
const amount = (kind: string, value: string | null, unit: string) =>
  (KINDS as readonly string[]).includes(kind)
    ? amountText(kind as Kind, value, displayUnits.value)
    : sensorAmount(value, unit, displayUnits.value)
const status = (value: string) =>
  te(`resolution.status.${value}`) ? t(`resolution.status.${value}`) : value
const statistics = computed(
  () =>
    props.resolution.statistics?.groups ??
    (props.resolution.statistics ? [props.resolution.statistics] : []),
)
const context = (key: string, value: string) =>
  te(`resolution.context.${key}.${value}`) ? t(`resolution.context.${key}.${value}`) : value
</script>

<template>
  <article class="bg-card space-y-3 rounded-3xl border p-4" data-testid="daily-resolution">
    <h3 class="font-medium">{{ dayLabel(date, today) }} · {{ metricLabel }}</h3>
    <SleepAnalysisSummary
      v-if="kind === 'sleep' && resolution.sleep_analysis"
      :analysis="resolution.sleep_analysis"
      :timezone="timezone"
      :today="today"
    />
    <SleepAnalysisDetails
      v-if="kind === 'sleep' && resolution.sleep_analysis"
      :analysis="resolution.sleep_analysis"
      :timezone="timezone"
      :today="today"
    />
    <p v-else-if="reportConflict" class="text-warning font-medium">
      {{ t(sleepConflictKey(reportConflict)) }}
    </p>
    <p
      v-else-if="kind === 'sleep' && resolution.status === 'conflict'"
      class="text-warning font-medium"
    >
      {{ t('resolution.status.conflict') }}
    </p>
    <p v-else-if="value !== null" class="text-xl font-semibold tabular-nums">
      {{ amount(kind, value, unit) }}
    </p>
    <template v-else-if="kind === 'sleep' && resolution.sessions?.length">
      <SleepSessions :sessions="resolution.sessions" :timezone="timezone" :today="today" />
      <p class="text-muted-foreground text-sm">{{ t('personal.periodOnly') }}</p>
    </template>
    <p v-else class="text-muted-foreground text-sm">{{ status(resolution.status) }}</p>
    <div
      v-for="(stats, index) in statistics"
      :key="index"
      class="space-y-3"
      data-testid="statistics-group"
    >
      <p
        v-if="
          statistics.length > 1 ||
          kind === 'temperature' ||
          (stats.measurement_kind && !['observed', 'measured'].includes(stats.measurement_kind)) ||
          (stats.context && stats.context !== 'unknown')
        "
        class="text-muted-foreground text-sm"
      >
        {{
          [
            stats.measurement_kind ? context('measurement', stats.measurement_kind) : null,
            kind === 'temperature' && stats.sensor_location
              ? context('location', stats.sensor_location)
              : null,
            kind === 'temperature' && stats.temperature_basis
              ? context('temperature', stats.temperature_basis)
              : null,
            stats.context && stats.context !== 'unknown' ? context('context', stats.context) : null,
            stats.source_names?.join(' · '),
          ]
            .filter(Boolean)
            .join(' · ')
        }}
      </p>
      <section v-if="stats.points?.count" class="space-y-1 text-sm" data-testid="statistics-points">
        <h4 class="font-medium">{{ t('resolution.points') }}</h4>
        <p>{{ t('resolution.count', { count: stats.points.count }) }}</p>
        <p v-if="stats.points.first_at">
          {{
            t('resolution.firstMeasurement', {
              when: momentPhrase(stats.points.first_at, timezone, today),
            })
          }}
        </p>
        <p v-if="stats.points.latest_at">
          {{
            t('resolution.latestMeasurement', {
              when: momentPhrase(stats.points.latest_at, timezone, today),
            })
          }}
        </p>
        <p v-if="stats.points.mean !== null">
          {{ t('resolution.pointMean') }}:
          {{ sensorAmount(stats.points.mean, unit, displayUnits) }}
        </p>
        <p v-if="stats.points.min !== null">
          {{ t('resolution.min') }}: {{ sensorAmount(stats.points.min, unit, displayUnits) }}
        </p>
        <p v-if="stats.points.max !== null">
          {{ t('resolution.max') }}: {{ sensorAmount(stats.points.max, unit, displayUnits) }}
        </p>
      </section>
      <section
        v-if="stats.interval_means?.count"
        class="space-y-1 text-sm"
        data-testid="statistics-interval-means"
      >
        <h4 class="font-medium">{{ t('resolution.intervalMeans') }}</h4>
        <p>{{ t('resolution.count', { count: stats.interval_means.count }) }}</p>
        <p v-if="stats.interval_means.mean !== null">
          {{ t('resolution.intervalMean') }}:
          {{ sensorAmount(stats.interval_means.mean, unit, displayUnits) }}
        </p>
        <p v-if="stats.interval_means.min !== null">
          {{ t('resolution.min') }}:
          {{ sensorAmount(stats.interval_means.min, unit, displayUnits) }}
        </p>
        <p v-if="stats.interval_means.max !== null">
          {{ t('resolution.max') }}:
          {{ sensorAmount(stats.interval_means.max, unit, displayUnits) }}
        </p>
        <p class="text-muted-foreground">{{ t('resolution.intervalExtrema') }}</p>
        <p v-if="stats.interval_means.covered_seconds !== undefined">
          {{
            t('resolution.coverage', {
              duration: duration(stats.interval_means.covered_seconds / 60),
            })
          }}
        </p>
      </section>
      <section v-if="stats.ranges?.count" class="space-y-1 text-sm" data-testid="statistics-ranges">
        <h4 class="font-medium">{{ t('resolution.ranges') }}</h4>
        <p>{{ t('resolution.count', { count: stats.ranges.count }) }}</p>
        <p v-if="stats.ranges.min !== null">
          {{ t('resolution.min') }}: {{ sensorAmount(stats.ranges.min, unit, displayUnits) }}
        </p>
        <p v-if="stats.ranges.max !== null">
          {{ t('resolution.max') }}: {{ sensorAmount(stats.ranges.max, unit, displayUnits) }}
        </p>
        <p class="text-muted-foreground">{{ t('resolution.rangeNoMean') }}</p>
        <p v-if="stats.ranges.covered_seconds !== undefined">
          {{
            t('resolution.coverage', {
              duration: duration(stats.ranges.covered_seconds / 60),
            })
          }}
        </p>
      </section>
      <section
        v-if="stats.sample_windows?.count"
        class="space-y-1 text-sm"
        data-testid="statistics-sample-windows"
      >
        <h4 class="font-medium">{{ t('resolution.sampleWindows.title') }}</h4>
        <p>
          {{
            t('resolution.sampleWindows.count', {
              windows: stats.sample_windows.count,
              usable: stats.sample_windows.usable_sample_count,
              total: stats.sample_windows.sample_count,
            })
          }}
        </p>
        <p v-if="stats.sample_windows.mean !== null">
          {{ t('resolution.sampleWindows.mean') }}:
          {{ sensorAmount(stats.sample_windows.mean, unit) }}
        </p>
        <p v-if="stats.sample_windows.min !== null">
          {{ t('resolution.min') }}: {{ sensorAmount(stats.sample_windows.min, unit) }}
        </p>
        <p v-if="stats.sample_windows.max !== null">
          {{ t('resolution.max') }}: {{ sensorAmount(stats.sample_windows.max, unit) }}
        </p>
        <p>
          {{
            t('resolution.sampleWindows.seconds', { count: stats.sample_windows.sampled_seconds })
          }}
        </p>
        <p v-if="stats.sample_windows.sample_coverage_ratio !== null">
          {{
            t('resolution.sampleWindows.ratio', {
              percent: formatAmount(stats.sample_windows.sample_coverage_ratio * 100, 1),
            })
          }}
        </p>
        <p v-if="stats.sample_windows.gap_count">
          {{ t('ring.workout.gaps', { count: stats.sample_windows.gap_count }) }}
        </p>
        <p v-if="stats.sample_windows.max_inter_sample_gap_ms !== null">
          {{
            t('resolution.sampleWindows.maxGap', {
              seconds: formatAmount(stats.sample_windows.max_inter_sample_gap_ms / 1000, 1),
            })
          }}
        </p>
        <p class="text-muted-foreground text-xs">
          {{ t('resolution.sampleWindows.discreteHint') }}
        </p>
      </section>
    </div>
    <ResolutionExplanation :resolution="resolution" :timezone="timezone" :today="today" />
  </article>
</template>
