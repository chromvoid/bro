<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import SleepAnalysisDetails from './SleepAnalysisDetails.vue'
import SleepAnalysisSummary from './SleepAnalysisSummary.vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Progress } from '@/components/ui/progress'
import { momentLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import { amountText, provenance, unitOf, type Metric } from './api'
import ResolutionExplanation from './ResolutionExplanation.vue'
import SleepSessions from './SleepSessions.vue'
import { metricSleepSessions, sleepConflictKey, sleepReportConflict } from './sleep'

/** One reported metric of the day and where it came from; the panel around it names it. */
const displayUnits = useDisplayUnits()
const props = defineProps<{ metric: Metric; today: string }>()
const { t } = useI18n()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const shown = computed(() => amountText(props.metric.kind, props.metric.value, displayUnits.value))
const sessions = computed(() => metricSleepSessions(props.metric))
const reportConflict = computed(() =>
  props.metric.kind === 'sleep' ? sleepReportConflict(props.metric.resolution) : null,
)
const progress = computed(() => {
  const { value, target } = props.metric
  if (props.metric.kind === 'sleep' && props.metric.resolution?.sleep_analysis) return null
  return value !== null && Number(target) > 0
    ? Math.min(100, (Number(value) / Number(target)) * 100)
    : null
})
</script>

<template>
  <article
    class="bg-card flex h-full flex-col gap-2 rounded-3xl border p-5"
    :data-testid="`metric-${metric.kind}`"
  >
    <SleepAnalysisSummary
      v-if="metric.kind === 'sleep' && metric.resolution?.sleep_analysis"
      :analysis="metric.resolution.sleep_analysis"
      :timezone="zone"
      :today="today"
    />
    <SleepAnalysisDetails
      v-if="metric.kind === 'sleep' && metric.resolution?.sleep_analysis"
      :analysis="metric.resolution.sleep_analysis"
      :timezone="zone"
      :today="today"
    />
    <p v-else-if="metric.status === 'conflict'" class="text-warning text-lg font-semibold">
      {{ reportConflict ? t(sleepConflictKey(reportConflict)) : t('resolution.status.conflict') }}
    </p>
    <p v-else-if="shown" class="text-2xl font-semibold tabular-nums">{{ shown }}</p>
    <SleepSessions
      v-else-if="sessions.length"
      :sessions="sessions"
      :timezone="zone"
      :today="today"
    />
    <p v-else class="text-muted-foreground text-lg">
      {{ metric.status === 'unavailable' ? t('personal.unavailable') : t('personal.noData') }}
    </p>

    <template v-if="progress !== null">
      <Progress :model-value="progress" :aria-label="t(`personal.metric.${metric.kind}`)" />
      <p class="text-muted-foreground text-sm">
        {{
          t('personal.target', {
            value: ['weight', 'active_energy', 'distance', 'sleep'].includes(metric.kind)
              ? amountText(metric.kind, metric.target, displayUnits)
              : `${formatAmount(metric.target, 0)} ${unitOf(metric.kind)}`,
          })
        }}
      </p>
    </template>
    <p
      v-if="
        metric.kind === 'sleep' &&
        !metric.resolution?.sleep_analysis &&
        metric.status === 'available' &&
        !shown
      "
      class="text-muted-foreground text-sm"
    >
      {{ t('personal.periodOnly') }}
    </p>
    <p
      v-if="shown && metric.complete === false && !metric.resolution"
      class="text-muted-foreground text-sm"
    >
      {{ t('personal.incomplete') }}
    </p>
    <p v-if="metric.source_unavailable" class="text-warning text-sm">
      {{ t('personal.sourceDown') }}
    </p>

    <ResolutionExplanation
      v-if="metric.resolution && !metric.resolution.sleep_analysis"
      :resolution="metric.resolution"
      :timezone="zone"
      :today="today"
    />
    <details v-else-if="metric.records?.length" class="text-sm">
      <summary class="text-primary cursor-pointer">{{ t('personal.provenance') }}</summary>
      <ul class="text-muted-foreground mt-2 space-y-1">
        <li v-for="record in metric.records" :key="record.id">
          {{ provenance(record, (origin) => t(`personal.origin.${origin}`)) }} ·
          {{ momentLabel(record.start_at, zone, today) }}
        </li>
      </ul>
    </details>
  </article>
</template>
