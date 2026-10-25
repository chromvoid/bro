<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import LineChart from '@/components/chart/LineChart.vue'
import DayNav from '@/components/DayNav.vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { dateLabel, dayIn, isIsoDay, timeIn } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { useSession } from '@/stores/session'
import { provideDisplayUnits } from './displayUnits'
import MetricSourcesPanel from './MetricSourcesPanel.vue'
import SleepAnalysisDetails from './SleepAnalysisDetails.vue'
import SleepAnalysisSummary from './SleepAnalysisSummary.vue'
import SleepSessions from './SleepSessions.vue'
import { sensorAmount } from './sensors'
import {
  dayAmount,
  deviceTitle,
  latestDay,
  readingsOf,
  roleOf,
  rowItems,
  shortNames,
  SOURCE_ROWS,
  useSourceDay,
  useSources,
  useSourcesDay,
  VITAL_METRICS,
  type DayItem,
} from './sources'
import { useOverview } from './usePersonal'

/**
 * One kind of measurement from one device, a day at a time: the device's own value, its readings
 * through the day and whether the combined day used it. Nothing from the other devices is mixed in.
 */
const { t, te } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const zone = computed(() => session.me?.timezone ?? 'UTC')
const today = computed(() => dayIn(zone.value))
const canWrite = computed(() => session.can('personal.write'))
const overview = useOverview(today)
const units = computed(() => {
  const profile = overview.data.value?.profile
  return profile && profile.user_id === session.me?.id ? profile.display_units : 'metric'
})
provideDisplayUnits(units)

const sources = useSources()
const list = computed(() => sources.data.value?.sources ?? [])
const sourceId = computed(() => String(route.params.source ?? ''))
const source = computed(() => list.value.find((item) => item.id === sourceId.value) ?? null)
const row = computed(() => SOURCE_ROWS.find((item) => item.key === route.params.row) ?? null)
const day = computed(() =>
  isIsoDay(route.query.day) && route.query.day <= today.value
    ? route.query.day
    : latestDay(source.value, zone.value, today.value),
)
function go(next: string) {
  void router.replace({ query: { ...route.query, day: next } })
}

const data = useSourceDay(
  computed(() => (source.value && row.value ? source.value.id : null)),
  day,
)
const combined = useSourcesDay(day)
const items = computed(() =>
  row.value ? rowItems(row.value, data.data.value?.daily ?? [], day.value) : [],
)
const role = computed(() =>
  source.value && row.value && combined.data.value && items.value.length
    ? (roleOf(source.value.id, row.value, combined.data.value, day.value) ?? 'unused')
    : null,
)
const names = computed(() =>
  shortNames(t, list.value, (item) =>
    dateLabel(dayIn(zone.value, new Date(item.created_at!)), today.value),
  ),
)

const count = (key: string, value: number) => t(key, value)
const amount = (item: DayItem) => dayAmount(count, item, units.value)
const label = (kind: string) =>
  te(`sources.row.${kind}`)
    ? t(`sources.row.${kind}`)
    : te(`personal.metric.${kind}`)
      ? t(`personal.metric.${kind}`)
      : kind
const reading = (value: number, unit: string) =>
  sensorAmount(String(value), unit, units.value) ?? ''
const shown = computed(() =>
  items.value.map((item) => {
    const points = VITAL_METRICS.has(item.kind)
      ? readingsOf(data.data.value?.series ?? [], item.kind)
      : []
    const values = points.map((point) => point.value)
    return {
      item,
      readings: points,
      newest: [...points].reverse(),
      chart: points.map((point) => ({ x: point.at, y: point.value })),
      extremes:
        values.length > 1
          ? t('sources.extremes', {
              min: reading(Math.min(...values), item.unit),
              max: reading(Math.max(...values), item.unit),
            })
          : null,
    }
  }),
)
// A device off the wrist for longer than this leaves a gap in the line instead of a slope.
const GAP_DAYS = 30 / 1440

const choosing = ref(false)
</script>

<template>
  <PageHeader
    :title="row ? t(`sources.row.${row.key}`) : t('sources.title')"
    :description="source ? deviceTitle(t, source) : undefined"
    :back="{ name: 'personal-source', params: { source: sourceId } }"
  />
  <div class="mx-auto max-w-3xl space-y-4 px-4 pb-8 md:px-8">
    <RefreshFailed :query="sources" />
    <LoadingState v-if="sources.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="sources.isLoadingError.value"
      :error="sources.error.value"
      @retry="sources.refetch()"
    />
    <EmptyState v-else-if="!source || !row" :title="t('sources.notFound')" />
    <template v-else>
      <DayNav :day="day" :today="today" @go="go" />
      <RefreshFailed :query="data" />
      <LoadingState v-if="data.isPending.value" :rows="3" />
      <ErrorState
        v-else-if="data.isLoadingError.value"
        :error="data.error.value"
        @retry="data.refetch()"
      />
      <EmptyState v-else-if="!items.length" :title="t('sources.dayEmpty')" />
      <template v-else>
        <p v-if="role" class="text-muted-foreground text-sm" data-testid="source-data-role">
          {{ t(`sources.roleDay.${role}`) }}
        </p>
        <section
          v-for="entry in shown"
          :key="entry.item.kind"
          class="bg-card space-y-3 rounded-3xl border p-4"
          :data-testid="`source-data-${entry.item.kind}`"
        >
          <h2 v-if="row.metrics.length > 1" class="text-muted-foreground text-sm">
            {{ label(entry.item.kind) }}
          </h2>
          <template v-if="entry.item.kind === 'sleep' && entry.item.resolution.sleep_analysis">
            <SleepAnalysisSummary
              :analysis="entry.item.resolution.sleep_analysis"
              :timezone="zone"
              :today="today"
            />
            <SleepAnalysisDetails
              :analysis="entry.item.resolution.sleep_analysis"
              :timezone="zone"
              :today="today"
            />
          </template>
          <p v-else-if="amount(entry.item)" class="text-2xl font-semibold tabular-nums">
            {{ amount(entry.item) }}
          </p>
          <SleepSessions
            v-if="
              entry.item.kind === 'sleep' &&
              !entry.item.resolution.sleep_analysis &&
              entry.item.resolution.sessions?.length
            "
            :sessions="entry.item.resolution.sessions"
            :timezone="zone"
            :today="today"
          />
          <template v-if="entry.readings.length">
            <p v-if="entry.extremes" class="text-muted-foreground text-sm tabular-nums">
              {{ entry.extremes }}
            </p>
            <LineChart
              v-if="entry.chart.length > 1"
              :points="entry.chart"
              :label="t('sources.chart', { metric: label(entry.item.kind) })"
              :format-x="(x) => timeIn(zone, new Date(x))"
              :format-y="(y) => reading(y, entry.item.unit)"
              :format-tick="(y) => formatAmount(y, 1)"
              :gap-days="GAP_DAYS"
              :height="160"
            />
            <details class="text-sm" :data-testid="`source-readings-${entry.item.kind}`">
              <summary class="text-primary min-h-11 cursor-pointer py-2">
                {{ t('sources.readings', entry.readings.length) }}
              </summary>
              <ul class="divide-y">
                <li
                  v-for="point in entry.newest"
                  :key="point.id"
                  class="flex justify-between gap-3 py-2 tabular-nums"
                >
                  <span class="text-muted-foreground">{{ timeIn(zone, new Date(point.at)) }}</span>
                  <span>{{ reading(point.value, point.unit) }}</span>
                </li>
              </ul>
            </details>
          </template>
        </section>
      </template>
      <Button variant="outline" @click="choosing = true">{{ t('sources.choose') }}</Button>
    </template>
  </div>

  <MetricSourcesPanel
    v-if="row && choosing"
    v-model:open="choosing"
    :row="row"
    :sources="list"
    :page="combined.data.value"
    :today="today"
    :day="day"
    :zone="zone"
    :names="names"
    :can-write="canWrite"
  />
</template>
