<script setup lang="ts">
import { useDisplayUnits } from './displayUnits'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RouteLocationRaw } from 'vue-router'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { momentLabel } from '@/core/dates'
import messages from '@/locales/metric-details-ru.json'
import { amountText, provenance, type Metric, type Observation } from './api'
import MetricCard from './MetricCard.vue'
import SourceLine from './SourceLine.vue'
import { sleepInterval } from './sleep'
import { useHistory } from './usePersonal'

/** A day's result and the stored observations that can actually be corrected. */
const displayUnits = useDisplayUnits()
const props = defineProps<{
  metric: Metric
  day: string
  today: string
  timezone: string
  canWrite: boolean
  historyLink: RouteLocationRaw
}>()
const emit = defineEmits<{
  edit: [record: Observation]
  history: [link: RouteLocationRaw]
}>()
const { t } = useI18n({ useScope: 'local', messages: { ru: messages } })
const embedded = computed(() =>
  (props.metric.records ?? []).filter((record) => record.kind === props.metric.kind),
)
const needsHistory = computed(() => !embedded.value.length)
const range = computed(() => ({ from: props.day, to: props.day, kind: props.metric.kind }))
// Opening the detail mounts this query only when the overview omitted the observations.
const history = useHistory(range, needsHistory)
const records = computed(() =>
  [
    ...new Map(
      (embedded.value.length ? embedded.value : (history.data.value?.records ?? []))
        .filter((record) => record.kind === props.metric.kind)
        .map((record) => [record.id, record] as const),
    ).values(),
  ].sort((a, b) => b.start_at.localeCompare(a.start_at) || a.id.localeCompare(b.id)),
)
</script>

<template>
  <div class="space-y-4" data-testid="metric-details">
    <MetricCard :metric="metric" :today="today" />
    <SourceLine
      :metric="metric.kind"
      :contributions="metric.resolution?.contributions"
      :day="day"
    />
    <Button variant="outline" class="min-h-11" as-child>
      <RouterLink
        :to="historyLink"
        data-testid="metric-detail-history"
        @click="emit('history', historyLink)"
        >{{ t('metricDetails.history') }}</RouterLink
      >
    </Button>
    <section :aria-label="t('metricDetails.records')" class="space-y-3">
      <h3 class="font-semibold">{{ t('metricDetails.records') }}</h3>
      <template v-if="needsHistory">
        <RefreshFailed :query="history" />
        <LoadingState v-if="history.isPending.value" :rows="2" />
        <ErrorState
          v-else-if="history.isLoadingError.value"
          :error="history.error.value"
          @retry="history.refetch()"
        />
      </template>
      <template v-if="records.length">
        <p class="text-muted-foreground text-sm">{{ t('metricDetails.originalRecords') }}</p>
        <ul class="divide-y">
          <li
            v-for="record in records"
            :key="record.id"
            class="space-y-2 py-3"
            :data-testid="`metric-record-${record.id}`"
          >
            <p class="font-medium tabular-nums">
              {{
                amountText(record.kind, record.data.value, displayUnits) ??
                t('metricDetails.noValue')
              }}
            </p>
            <p class="text-muted-foreground text-sm">
              {{
                record.kind === 'sleep'
                  ? sleepInterval(record.start_at, record.end_at, timezone, today)
                  : momentLabel(record.start_at, timezone, today)
              }}
              ·
              {{ provenance(record, (origin) => t(`metricDetails.origin.${origin}`)) }}
            </p>
            <p v-if="record.excluded" class="text-warning text-sm">
              {{ t('metricDetails.excluded') }}
            </p>
            <Button
              variant="outline"
              class="min-h-11"
              :data-testid="`${canWrite ? 'metric-record-correct' : 'metric-record-detail'}-${record.id}`"
              @click="emit('edit', record)"
              >{{ canWrite ? t('metricDetails.correct') : 'Подробности' }}</Button
            >
          </li>
        </ul>
      </template>
      <p
        v-else-if="!needsHistory || (!history.isPending.value && !history.isLoadingError.value)"
        class="text-muted-foreground text-sm"
      >
        {{ t('metricDetails.noRecords') }}
      </p>
    </section>
  </div>
</template>
