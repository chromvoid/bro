<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { timeIn } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { duration } from './api'
import type { SleepAnalysis } from './resolutionContract'
import { episodeParts, sleepInterval } from './sleep'
import SleepNightChart from './SleepNightChart.vue'

/** Each night as a chart first; what it rests on stays one tap away. */
defineProps<{ analysis: SleepAnalysis; timezone: string; today: string }>()
const { t, te } = useI18n()
const reason = (value: string) =>
  te(`personal.sleepAnalysis.reason.${value}`)
    ? t(`personal.sleepAnalysis.reason.${value}`)
    : t('personal.sleepAnalysis.reason.unsupported_report')
const stateLabel = (state: string, stage: string | null) => {
  if (state === 'sleep')
    return t(
      `personal.sleepStage.${stage && ['light', 'deep', 'rem'].includes(stage) ? stage : 'sleeping'}`,
    )
  if (state === 'awake')
    return t(`personal.sleepStage.${stage === 'awake_in_bed' ? 'awake_in_bed' : 'awake'}`)
  return t('personal.sleepStage.unknown')
}
// Damaged timing, unproven and estimated stages change how the chart reads; the rest is provenance.
const WARNINGS = ['duration_mismatch', 'sleep_taxonomy_unverified', 'estimated_stages']
type Night = NonNullable<SleepAnalysis['episodes'][number]['night']>
// The figures the evidence carries, in reading order; an empty one is left out, never shown as zero.
const nightRows = (night: Night, zone: string) =>
  [
    night.latency_minutes !== null && ['latency', duration(night.latency_minutes)],
    night.efficiency !== null && ['efficiency', `${formatAmount(night.efficiency * 100, 0)}%`],
    night.awakenings !== null && [
      'awakenings',
      t(
        'personal.sleepNight.awakeningCount',
        { count: night.awakenings, duration: duration(night.waso_minutes ?? 0) },
        night.awakenings,
      ),
    ],
    night.turns_per_hour !== null && [
      'turns',
      t('personal.sleepNight.turnRate', { rate: formatAmount(night.turns_per_hour, 1) }),
    ],
    night.heart_rate && [
      'heartRate',
      t('personal.sleepNight.heartRateValue', {
        lowest: formatAmount(night.heart_rate.lowest, 0),
        mean: formatAmount(night.heart_rate.mean, 0),
      }),
    ],
    night.hrv && [
      'hrv',
      t('personal.sleepNight.hrvValue', { value: formatAmount(night.hrv.mean, 0) }),
    ],
    ['midpoint', timeIn(zone, night.midpoint_at)],
  ].filter((row): row is [string, string] => Array.isArray(row))
const names = (refs: { source_name?: string | null }[]) =>
  [...new Set(refs.map((ref) => ref.source_name ?? t('personal.unnamedSource')))].join(', ')
</script>

<template>
  <div class="space-y-4 text-sm" data-testid="sleep-analysis-details">
    <section v-for="episode in analysis.episodes" :key="episode.start_at" class="space-y-2">
      <p v-if="analysis.episodes.length > 1" class="font-medium tabular-nums">
        {{ sleepInterval(episode.start_at, episode.end_at, timezone, today) }}
      </p>
      <SleepNightChart
        :start="episode.start_at"
        :end="episode.end_at"
        :parts="episodeParts(episode)"
        :changes="episode.position_changes"
        :timezone="timezone"
        :today="today"
      />
      <dl
        v-if="episode.night"
        class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1"
        data-testid="sleep-night"
      >
        <template v-for="[key, value] in nightRows(episode.night, timezone)" :key="key">
          <dt class="text-muted-foreground">{{ t(`personal.sleepNight.${key}`) }}</dt>
          <dd class="tabular-nums" :data-testid="`sleep-night-${key}`">{{ value }}</dd>
        </template>
      </dl>
      <p v-if="episode.classification_coverage < 1">
        {{
          t('personal.sleepAnalysis.coverage', {
            percent: Math.round(episode.classification_coverage * 100),
          })
        }}
      </p>
      <p v-if="episode.conflict_minutes" class="text-warning">
        {{
          t('personal.sleepAnalysis.conflictSubset', {
            duration: duration(episode.conflict_minutes),
          })
        }}
      </p>
      <p v-if="episode.bounds && episode.unknown_minutes">
        {{
          t('personal.sleepAnalysis.bounds', {
            min: duration(episode.bounds.min_minutes),
            max: duration(episode.bounds.max_minutes),
          })
        }}
      </p>
      <p
        v-for="flag in episode.flags.filter((flag) => WARNINGS.includes(flag))"
        :key="flag"
        class="text-muted-foreground"
      >
        {{ reason(flag) }}
      </p>
      <div
        v-if="episode.forecast.status === 'available'"
        class="bg-muted space-y-1 rounded-lg p-3"
        data-testid="sleep-forecast"
      >
        <p class="font-medium">
          {{
            t('personal.sleepAnalysis.forecast', {
              duration: duration(Math.round(episode.forecast.estimated_asleep_minutes! / 5) * 5),
            })
          }}
        </p>
        <p>
          {{
            t('personal.sleepAnalysis.forecastBasis', {
              count: episode.forecast.training_nights,
              duration: duration(episode.forecast.estimated_missing_minutes ?? 0),
            })
          }}
        </p>
      </div>
      <p
        v-else-if="episode.forecast.reason !== 'complete_report'"
        class="text-muted-foreground"
        data-testid="sleep-forecast-unavailable"
      >
        {{ reason(episode.forecast.reason ?? 'unsupported_report') }}
      </p>
      <details>
        <summary class="text-primary cursor-pointer">
          {{ t('personal.sleepAnalysis.evidence') }}
        </summary>
        <div class="mt-2 space-y-2">
          <p>
            {{ t('personal.sleepAnalysis.window', { duration: duration(episode.window_minutes) }) }}
          </p>
          <p v-if="episode.flags.includes('protocol_unverified')" class="text-muted-foreground">
            {{ reason('protocol_unverified') }}
          </p>
          <p v-for="(minutes, stage) in episode.stage_minutes" :key="stage">
            {{ stateLabel('sleep', String(stage)) }}: {{ duration(minutes) }}
          </p>
          <ul class="space-y-2">
            <li v-for="segment in episode.segments" :key="segment.start_at">
              {{ sleepInterval(segment.start_at, segment.end_at, timezone, today) }} ·
              {{ stateLabel(segment.state, segment.stage) }}
              <span class="text-muted-foreground block"
                >{{ reason(segment.reason) }} ·
                {{
                  names(segment.selected_refs.length ? segment.selected_refs : segment.evidence)
                }}</span
              >
            </li>
          </ul>
          <p v-if="episode.forecast.validation">
            {{
              t('personal.sleepAnalysis.validation', {
                count: episode.forecast.validation.nights,
                mae: Math.round(episode.forecast.validation.mae_minutes),
                p90: Math.round(episode.forecast.validation.p90_error_minutes),
              })
            }}
          </p>
          <p
            v-for="report in episode.reports.filter(
              (report) => report.reported_asleep_minutes !== null,
            )"
            :key="report.id"
            class="text-muted-foreground"
          >
            {{
              t('personal.sleepAnalysis.originalTotal', {
                source: report.source_name ?? t('personal.unnamedSource'),
                duration: duration(report.reported_asleep_minutes!),
              })
            }}
          </p>
        </div>
      </details>
    </section>
    <ul v-if="analysis.rejected_reports.length" class="text-warning space-y-1">
      <li v-for="report in analysis.rejected_reports" :key="`${report.id}-${report.reason}`">
        {{ report.source_name ?? t('personal.unnamedSource') }} ·
        {{ t(`resolution.reason.${report.reason}`) }}
      </li>
    </ul>
  </div>
</template>
