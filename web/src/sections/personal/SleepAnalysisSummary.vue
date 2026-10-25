<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import { duration } from './api'
import type { SleepAnalysis } from './resolutionContract'
import { episodeParts, sleepInterval } from './sleep'
import SleepStrip from './SleepStrip.vue'

/** The night in a few lines: how long, from when to when, and with `strip` its outline. */
const props = defineProps<{
  analysis: SleepAnalysis
  timezone: string
  today: string
  strip?: boolean
}>()
const { t } = useI18n()
const unclassified = computed(
  () => props.analysis.summary.asleep_minutes === 0 && props.analysis.summary.awake_minutes === 0,
)
</script>

<template>
  <span class="block space-y-1" data-testid="sleep-analysis-summary">
    <span
      v-if="analysis.summary.complete_asleep_minutes !== null"
      class="block text-lg font-semibold tabular-nums md:text-xl"
    >
      {{ duration(analysis.summary.complete_asleep_minutes) }}
    </span>
    <template v-else-if="analysis.episodes.length">
      <span v-if="unclassified" class="block font-medium">{{ t('personal.periodOnly') }}</span>
      <span v-else class="block font-medium tabular-nums">
        {{
          t('personal.sleepAnalysis.recognized', {
            duration: duration(analysis.summary.asleep_minutes),
          })
        }}
      </span>
    </template>
    <span v-else class="block text-sm">{{
      t(
        analysis.status === 'conflict'
          ? 'personal.sleepAnalysis.reportConflict'
          : 'personal.noData',
      )
    }}</span>
    <span v-for="episode in analysis.episodes" :key="episode.start_at" class="block space-y-1">
      <span
        :class="[
          'block tabular-nums',
          analysis.summary.complete_asleep_minutes === null && unclassified
            ? 'font-medium'
            : 'text-muted-foreground text-sm',
        ]"
        data-testid="sleep-interval"
      >
        {{ sleepInterval(episode.start_at, episode.end_at, timezone, today) }}
      </span>
      <SleepStrip
        v-if="strip"
        :start="episode.start_at"
        :end="episode.end_at"
        :parts="episodeParts(episode)"
      />
    </span>
    <span
      v-if="analysis.summary.complete_asleep_minutes === null && analysis.episodes.length"
      class="text-muted-foreground block text-sm tabular-nums"
    >
      {{
        t('personal.sleepAnalysis.unclassified', {
          duration: duration(analysis.summary.unknown_minutes),
        })
      }}
    </span>
    <span
      v-if="analysis.summary.forecast_asleep_minutes !== null"
      class="text-muted-foreground block text-sm"
    >
      {{
        t('personal.sleepAnalysis.forecast', {
          duration: duration(Math.round(analysis.summary.forecast_asleep_minutes / 5) * 5),
        })
      }}
    </span>
    <span v-if="analysis.summary.conflict_minutes > 0" class="text-warning block text-sm">
      {{
        t('personal.sleepAnalysis.conflictSubset', {
          duration: duration(analysis.summary.conflict_minutes),
        })
      }}
    </span>
  </span>
</template>
