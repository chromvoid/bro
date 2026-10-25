<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import LineChart from '@/components/chart/LineChart.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { dayLabel } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import type { History } from '@/sections/personal/api'
import { distanceText, useDisplayUnits } from '@/sections/personal/displayUnits'
import { useHistory } from '@/sections/personal/usePersonal'
import { useSession } from '@/stores/session'
import { dailyMetric, shortDate, summaryPoints } from './charts'
import MovementDaySummary from './MovementDaySummary.vue'
import { useRunningData } from './running'
import RunningWorkoutsSummary from './RunningWorkoutsSummary.vue'
import { useActivitySummary, type ActivityRange } from './useActivity'

const props = defineProps<{
  subject: 'steps' | 'active_minutes' | 'sessions'
  range: ActivityRange
  today: string
  timezone: string
}>()
const emit = defineEmits<{ go: [date: string] }>()
const { t } = useI18n()
const displayUnits = useDisplayUnits()
const account = useSession()
const canRead = computed(() => account.can('personal.read'))
const manual = computed(() => props.subject === 'sessions')
const history = useHistory(
  computed(() => ({
    from: props.range.from,
    to: props.range.to,
    kind: props.subject === 'active_minutes' ? 'active_minutes' : 'steps',
  })),
  computed(() => canRead.value && !manual.value),
)
const summary = useActivitySummary(
  computed(() => props.range),
  manual,
)
const showRunning = computed(
  () => canRead.value && manual.value && (!props.range.kind || props.range.kind === 'running'),
)
const running = useRunningData(
  computed(() => ({ from: props.range.from, to: props.range.to })),
  showRunning,
  computed(() => false),
)
const field = ref<'sessions' | 'duration_minutes' | 'working_sets'>('sessions')
const fieldTitle = computed(() => t(`activityRedesign.sessionMeasures.${field.value}`))
const metricKind = computed(() => (props.subject === 'active_minutes' ? 'active_minutes' : 'steps'))
const title = computed(() => t(`activityRedesign.subjects.${props.subject}`))
const points = computed(() => dailyMetric(history.data.value, metricKind.value))
const unit = computed(() =>
  props.subject === 'active_minutes' ? t('activity.minutes') : t('activity.running.stepUnit'),
)
const formatMetric = (value: number) => `${formatAmount(value, 0)} ${unit.value}`
const values = computed(() => new Map(points.value.map((point) => [point.x, point.y])))
const rowValue = (date: string) => values.value.get(Date.parse(`${date}T00:00:00Z`))
function rowStatus(row: History['daily'][number]) {
  const resolved = row.resolution?.status
  if (resolved === 'conflict') return resolved
  return row.status === 'available' ? 'no_data' : row.status
}
const knownDuration = computed(
  () =>
    summary.data.value &&
    summary.data.value.totals.sessions > summary.data.value.totals.missing_duration_sessions,
)
const days = computed(
  () => history.data.value?.daily.filter((row) => row.kind === metricKind.value) ?? [],
)
const distances = computed(() =>
  Object.entries(summary.data.value?.totals.distance_km_by_kind ?? {}).filter(
    ([, value]) => value !== null,
  ),
)
</script>

<template>
  <section v-if="manual" class="space-y-4" data-testid="activity-dynamics-sessions">
    <h2 class="text-lg font-semibold">{{ t('activityRedesign.manualSessions') }}</h2>
    <RefreshFailed :query="summary" /><LoadingState v-if="summary.isPending.value" :rows="2" />
    <ErrorState
      v-else-if="summary.isLoadingError.value"
      :error="summary.error.value"
      @retry="summary.refetch()"
    />
    <template v-else-if="summary.data.value">
      <p v-if="!summary.data.value.totals.sessions" class="text-muted-foreground text-sm">
        {{ t('activity.emptyPeriod') }}
      </p>
      <template v-else>
        <div class="bg-card grid grid-cols-2 gap-4 rounded-2xl border p-4">
          <div>
            <p class="text-muted-foreground text-sm">{{ t('activity.sessionsCount') }}</p>
            <p class="text-2xl font-semibold tabular-nums">
              {{ summary.data.value.totals.sessions }}
            </p>
          </div>
          <div>
            <p class="text-muted-foreground text-sm">{{ t('activity.knownDuration') }}</p>
            <p class="text-2xl font-semibold tabular-nums">
              {{
                knownDuration ? formatAmount(summary.data.value.totals.duration_minutes, 1) : '—'
              }}
              <span class="text-base">{{ t('activity.minutes') }}</span>
            </p>
            <p
              v-if="summary.data.value.totals.missing_duration_sessions"
              class="text-muted-foreground text-xs"
            >
              {{
                t('activity.missingDuration', {
                  count: summary.data.value.totals.missing_duration_sessions,
                })
              }}
            </p>
          </div>
        </div>
        <section class="bg-card space-y-3 rounded-2xl border p-4">
          <label class="space-y-2 text-sm font-medium"
            ><span>{{ t('activityRedesign.chooseMeasure') }}</span
            ><NativeSelect v-model="field"
              ><option
                v-for="value in ['sessions', 'duration_minutes', 'working_sets']"
                :key="value"
                :value="value"
              >
                {{ t(`activityRedesign.sessionMeasures.${value}`) }}
              </option></NativeSelect
            ></label
          >
          <LineChart
            v-if="summaryPoints(summary.data.value, field).length"
            :points="summaryPoints(summary.data.value, field)"
            :label="fieldTitle"
            :format-x="shortDate"
            :format-y="(value) => formatAmount(value, field === 'duration_minutes' ? 1 : 0)"
            :gap-days="1"
          />
          <p v-else class="text-muted-foreground text-sm">{{ t('activity.noDurationData') }}</p>
          <details>
            <summary class="min-h-11 cursor-pointer py-3 font-medium">
              {{ t('activityRedesign.dailyTable') }}
            </summary>
            <div class="overflow-x-auto">
              <table class="w-full text-sm tabular-nums">
                <thead>
                  <tr class="text-muted-foreground text-left">
                    <th class="p-2">{{ t('activity.sessionDate') }}</th>
                    <th class="p-2">{{ t('activity.sessionsCount') }}</th>
                    <th class="p-2">{{ t('activity.duration') }}</th>
                    <th class="p-2">{{ t('activity.workingSets') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in summary.data.value.daily" :key="row.date" class="border-t">
                    <th class="p-2 text-left">
                      <Button variant="link" class="min-h-11 px-0" @click="emit('go', row.date)">{{
                        dayLabel(row.date, today)
                      }}</Button>
                    </th>
                    <td class="p-2">{{ row.sessions }}</td>
                    <td class="p-2">
                      {{
                        row.sessions > row.missing_duration_sessions
                          ? formatAmount(row.duration_minutes, 1)
                          : '—'
                      }}<span
                        v-if="row.missing_duration_sessions"
                        class="text-muted-foreground block text-xs"
                        >{{
                          t('activity.missingDuration', { count: row.missing_duration_sessions })
                        }}</span
                      >
                    </td>
                    <td class="p-2">{{ row.working_sets }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p v-for="[kind, distance] in distances" :key="kind" class="mt-2 text-sm">
              {{ t(`activity.kinds.${kind}`) }}:
              {{ distanceText(Number(distance) * 1000, displayUnits) }}
            </p>
          </details>
        </section>
      </template>
    </template>
    <template v-if="showRunning">
      <RefreshFailed :query="running.workouts" />
      <RunningWorkoutsSummary
        :page="running.workoutPage.value"
        :loading="running.workouts.isPending.value"
        :error="running.workouts.isLoadingError.value ? running.workouts.error.value : undefined"
        :timezone="timezone"
        :today="today"
        @retry="running.workouts.refetch()"
      />
    </template>
  </section>
  <section v-else class="space-y-4" data-testid="activity-dynamics-movement">
    <h2 class="text-lg font-semibold">{{ title }}</h2>
    <p v-if="!canRead" class="text-muted-foreground text-sm">
      {{ t('activity.noPersonalAccess') }}
    </p>
    <template v-else>
      <RefreshFailed :query="history" /><LoadingState v-if="history.isPending.value" :rows="2" />
      <ErrorState
        v-else-if="history.isLoadingError.value"
        :error="history.error.value"
        @retry="history.refetch()"
      />
      <section v-else class="bg-card space-y-3 rounded-2xl border p-4">
        <p class="text-muted-foreground text-sm">
          {{ t('activityRedesign.availableDays', { count: points.length }) }}
        </p>
        <LineChart
          v-if="points.length"
          :points="points"
          :label="title"
          :format-x="shortDate"
          :format-y="formatMetric"
          :format-tick="(value) => (Number.isInteger(value) ? formatAmount(value, 0) : '')"
          :gap-days="1"
        />
        <p v-else class="text-muted-foreground text-sm">
          {{ t(subject === 'steps' ? 'activity.noStepsData' : 'activity.noActiveData') }}
        </p>
        <p class="text-muted-foreground text-xs">{{ t('activityRedesign.historyCoverage') }}</p>
        <details>
          <summary class="min-h-11 cursor-pointer py-3 font-medium">
            {{ t('activityRedesign.dailyTable') }}
          </summary>
          <div class="divide-y">
            <div
              v-for="row in days"
              :key="row.date"
              class="flex flex-wrap items-center justify-between gap-2 py-1 text-sm"
            >
              <Button variant="link" class="min-h-11 px-0" @click="emit('go', row.date)">{{
                dayLabel(row.date, today)
              }}</Button>
              <p class="tabular-nums">
                {{
                  rowValue(row.date) !== undefined
                    ? formatMetric(rowValue(row.date)!)
                    : t(`resolution.status.${rowStatus(row)}`)
                }}
              </p>
            </div>
          </div>
          <MovementDaySummary
            :history="history.data.value"
            :kind="metricKind"
            :date="range.to"
            :today="today"
            :timezone="timezone"
          />
        </details>
      </section>
    </template>
  </section>
</template>
