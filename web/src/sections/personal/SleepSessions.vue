<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { amountText } from './api'
import type { SleepSession } from './resolutionContract'
import { sessionParts, sleepInterval } from './sleep'
import SleepNightChart from './SleepNightChart.vue'

defineProps<{
  sessions: SleepSession[]
  timezone: string
  today: string
  detailed?: boolean
}>()
const { t } = useI18n()
const stageLabel = (stage: string) =>
  t(
    `personal.sleepStage.${['light', 'deep', 'rem', 'awake', 'sleeping', 'awake_in_bed', 'out_of_bed'].includes(stage) ? stage : 'unknown'}`,
  )
</script>

<template>
  <ul class="space-y-2" data-testid="sleep-sessions">
    <li v-for="session in sessions" :key="session.id" data-testid="sleep-session">
      <p class="font-medium tabular-nums">
        {{ sleepInterval(session.start_at, session.end_at, timezone, today) }}
      </p>
      <template v-if="detailed">
        <p>{{ amountText('sleep', session.asleep_minutes) ?? t('personal.periodOnly') }}</p>
        <p v-if="session.source_name" class="text-muted-foreground">{{ session.source_name }}</p>
        <SleepNightChart
          v-if="session.stages.length"
          class="mt-2"
          :start="session.start_at"
          :end="session.end_at"
          :parts="sessionParts(session)"
          :changes="session.position_changes"
          :timezone="timezone"
          :today="today"
        />
        <details v-if="session.stages.length" class="mt-1">
          <summary class="text-primary cursor-pointer">{{ t('personal.sleepStages') }}</summary>
          <ul class="mt-1 space-y-1">
            <li v-for="stage in session.stages" :key="`${stage.start_at}-${stage.end_at}`">
              {{ sleepInterval(stage.start_at, stage.end_at, timezone, today) }} ·
              {{ stageLabel(stage.stage) }}
            </li>
          </ul>
        </details>
      </template>
    </li>
  </ul>
</template>
