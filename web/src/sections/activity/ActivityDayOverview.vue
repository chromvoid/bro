<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import { useResizeObserver } from '@vueuse/core'
import { computed, nextTick, ref, toRef, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Panel from '@/components/Panel.vue'
import ScrollStrip from '@/components/ScrollStrip.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { dayLabel, fullDayLabel, momentPhrase, rangeLabel, shiftDay } from '@/core/dates'
import { describeError } from '@/core/errors'
import { formatAmount } from '@/core/numbers'
import { unitOf, type History, type Metric } from '@/sections/personal/api'
import { distanceNumber, distanceUnit, useDisplayUnits } from '@/sections/personal/displayUnits'
import ResolutionExplanation from '@/sections/personal/ResolutionExplanation.vue'
import SourceLine from '@/sections/personal/SourceLine.vue'
import { useHistory, useOverview } from '@/sections/personal/usePersonal'
import { useSession } from '@/stores/session'
import { coverageOf } from './movement'

type MovementKind = 'steps' | 'active_minutes' | 'active_energy' | 'distance'
type DailyResult = History['daily'][number]
type Result = Metric | DailyResult | undefined

const props = defineProps<{ date: string; today: string; timezone: string }>()
const emit = defineEmits<{
  correctSteps: []
  go: [day: string]
  dynamics: []
  ready: [ready: boolean]
}>()
const { t } = useI18n()
const session = useSession()
const canRead = computed(() => session.can('personal.read'))
const canWrite = computed(() => canRead.value && session.can('personal.write'))
const displayUnits = useDisplayUnits()
const overview = useOverview(toRef(props, 'date'), canRead)
// Dates within the same seven-day window keep their position as a bar is selected.
// Historical windows are seven days long as well; their exact interval is always visible.
const weekEnd = computed(() => {
  const age = Math.max(
    0,
    Math.round(
      (Date.parse(`${props.today}T00:00:00Z`) - Date.parse(`${props.date}T00:00:00Z`)) / 86_400_000,
    ),
  )
  return shiftDay(props.today, -Math.floor(age / 7) * 7)
})
const weekRange = computed(() => ({
  from: shiftDay(weekEnd.value, -6),
  to: weekEnd.value,
  kind: 'steps' as const,
}))
const steps = useHistory(weekRange, canRead)
const weekStrip = useTemplateRef<InstanceType<typeof ScrollStrip>>('weekStrip')
const weekButtons = useTemplateRef<HTMLElement>('weekButtons')
// Reveal only after selection/layout changes: a person can still pan away to read other days.
// ScrollStrip moves this row horizontally and never scrolls the surrounding page.
function revealSelectedDay() {
  void nextTick(() => {
    const strip = weekStrip.value
    const element = strip?.element
    const selected = element?.querySelector('[aria-pressed="true"]')
    if (!element || !selected) return
    const viewport = element.getBoundingClientRect()
    const button = selected.getBoundingClientRect()
    if (button.left >= viewport.left && button.right <= viewport.right) return
    strip?.reveal(selected)
  })
}
watch([weekStrip, () => props.date], revealSelectedDay, { flush: 'post' })
useResizeObserver(
  computed(() => weekStrip.value?.element),
  revealSelectedDay,
)
useResizeObserver(weekButtons, revealSelectedDay)
watch(
  () => !overview.isPending.value && !steps.isPending.value,
  (ready) => emit('ready', ready),
  { immediate: true },
)
const detail = ref<MovementKind | null>(null)
const detailOpen = computed({
  get: () => detail.value !== null,
  set: (open: boolean) => {
    if (!open) detail.value = null
  },
})
// A tile's own week, read while its details are open; the steps week is read for the chart.
const detailRange = computed(() => ({ ...weekRange.value, kind: detail.value ?? 'steps' }))
const other = useHistory(
  detailRange,
  computed(() => canRead.value && detail.value !== null && detail.value !== 'steps'),
)
watch(
  () => props.date,
  () => {
    detail.value = null
  },
)
watch(canRead, (allowed) => {
  if (!allowed) detail.value = null
})

function metric(kind: MovementKind): Metric | undefined {
  return canRead.value && overview.data.value?.date === props.date
    ? overview.data.value.metrics[kind]
    : undefined
}
function unresolved(row: Result): boolean {
  return row?.status === 'conflict' || row?.resolution?.status === 'conflict'
}
function valueOf(row: Result): string | null {
  return row?.status === 'available' &&
    !unresolved(row) &&
    row.value !== null &&
    row.value.trim() !== '' &&
    Number.isFinite(Number(row.value)) &&
    Number(row.value) >= 0
    ? row.value
    : null
}
function statusOf(row: Result): string {
  if (unresolved(row)) return 'conflict'
  if (row?.status === 'unavailable') return row.status
  if (!row || row.status === 'no_data' || row.status === 'available') return 'no_data'
  return 'unknown'
}
/** A value's number and unit: distance follows the display units, the rest are whole numbers. */
function parts(kind: MovementKind, value: string): { number: string; unit: string } {
  if (kind !== 'distance') return { number: formatAmount(value, 0), unit: unitOf(kind) }
  const units = displayUnits.value
  return { number: distanceNumber(Number(value), units), unit: distanceUnit(units) }
}
function shown(kind: MovementKind, value: string): string {
  const { number, unit } = parts(kind, value)
  return `${number} ${unit}`
}
function amount(row: Result, kind: MovementKind): string {
  const value = valueOf(row)
  return value !== null ? shown(kind, value) : t(`resolution.status.${statusOf(row)}`)
}
function titleOf(kind: MovementKind): string {
  if (kind === 'steps') return t('activityDay.steps')
  return t(kind === 'active_minutes' ? 'activityDay.activeMinutes' : `personal.metric.${kind}`)
}
function goalOf(row: Result): string | null {
  return row?.target !== null &&
    row?.target !== undefined &&
    row.target.trim() !== '' &&
    Number.isFinite(Number(row.target)) &&
    Number(row.target) > 0
    ? row.target
    : null
}
function unverified(row: Result): boolean {
  return row?.resolution?.protocol?.verified === false && !!row.resolution.selected.length
}
const MOVEMENT = ['steps', 'active_minutes', 'active_energy', 'distance'] as const
// Steps and active minutes always have a tile; energy and distance only when a source reports them.
const tiled = (kind: MovementKind) =>
  kind === 'steps' || kind === 'active_minutes' || (metric(kind)?.status ?? 'no_data') !== 'no_data'
const metrics = computed(() =>
  MOVEMENT.filter(tiled).map((kind) => {
    const row = metric(kind)
    return {
      kind,
      row,
      title: titleOf(kind),
      value: valueOf(row),
      status: statusOf(row),
      goal: goalOf(row),
      coverage: coverageOf(row),
    }
  }),
)
const detailMetric = computed(() => (detail.value ? metric(detail.value) : undefined))
const detailTitle = computed(() => titleOf(detail.value ?? 'steps'))
const detailHistory = computed(() => (!detail.value || detail.value === 'steps' ? steps : other))
const weekday = new Intl.DateTimeFormat('ru-RU', { weekday: 'short', timeZone: 'UTC' })
const days = computed(() => {
  const history = steps.data.value
  // An unexpected server range cannot be relabelled as the visible week.
  const daily =
    history?.from === weekRange.value.from && history.to === weekRange.value.to ? history.daily : []
  return Array.from({ length: 7 }, (_, index) => {
    const date = shiftDay(weekRange.value.from, index)
    const row = daily.find((item) => item.date === date && item.kind === 'steps')
    return { date, row, value: valueOf(row), coverage: coverageOf(row) }
  })
})
const chartMaximum = computed(() => Math.max(1, ...days.value.map((day) => Number(day.value ?? 0))))
function barHeight(value: string): number {
  return (Number(value) / chartMaximum.value) * 52
}
function correctSteps() {
  detail.value = null
  emit('correctSteps')
}
function historyRow(date: string): DailyResult | undefined {
  const result = detailHistory.value.data.value
  return result?.from === weekRange.value.from && result.to === weekRange.value.to
    ? result.daily.find((row) => row.date === date && row.kind === detail.value)
    : undefined
}
</script>

<template>
  <div v-if="canRead" class="space-y-4" data-testid="activity-day-overview">
    <section class="bg-card @container rounded-3xl border p-4" :aria-label="t('activityDay.title')">
      <RefreshFailed :query="overview" />
      <div
        v-if="overview.isPending.value"
        class="grid grid-cols-1 gap-4 @min-[18rem]:grid-cols-2"
        role="status"
      >
        <Skeleton class="h-20 w-full" />
        <Skeleton class="h-20 w-full" />
        <span class="sr-only">{{ t('state.loading') }}</span>
      </div>
      <div v-else-if="overview.isLoadingError.value" class="space-y-2 text-sm" role="alert">
        <p>{{ t(describeError(overview.error.value)) }}</p>
        <Button variant="outline" class="min-h-11" @click="overview.refetch()">
          {{ t('common.retry') }}
        </Button>
      </div>
      <div v-else class="grid grid-cols-1 gap-4 @min-[18rem]:grid-cols-2">
        <div v-for="item in metrics" :key="item.kind" class="min-w-0 space-y-1">
          <button
            type="button"
            class="hover:bg-muted focus-visible:ring-ring flex min-h-11 w-full flex-col items-start gap-1 rounded-xl text-left outline-none focus-visible:ring-2"
            :aria-label="t('activityDay.details', { metric: item.title, date: fullDayLabel(date) })"
            :data-testid="`day-metric-${item.kind}`"
            @click="detail = item.kind"
          >
            <span class="text-muted-foreground flex w-full items-center gap-1 text-sm">
              {{ item.title }}<ChevronRight class="ms-auto size-4 shrink-0" aria-hidden="true" />
            </span>
            <span
              v-if="item.value !== null"
              :class="item.kind === 'steps' ? 'text-3xl' : 'text-2xl'"
              class="font-semibold break-words tabular-nums"
              :data-testid="`day-value-${item.kind}`"
            >
              {{ parts(item.kind, item.value).number }}
              <span v-if="item.kind !== 'steps'" class="text-sm font-normal">{{
                parts(item.kind, item.value).unit
              }}</span>
            </span>
            <span
              v-else
              class="text-sm font-medium"
              :class="{ 'text-warning': unresolved(item.row) }"
              :data-status="item.status"
              :data-testid="`day-status-${item.kind}`"
              >{{ amount(item.row, item.kind) }}</span
            >
            <span v-if="item.goal !== null" class="text-muted-foreground text-xs">
              {{
                t('activityDay.goal', {
                  value: shown(item.kind, item.goal),
                })
              }}
            </span>
            <!-- Only a partial day is marked on the tile; its details say the rest. -->
            <span
              v-if="item.value !== null && item.coverage === 'partial'"
              class="text-muted-foreground text-xs"
              :data-coverage="item.coverage"
            >
              {{ t('activityDay.partial') }}
            </span>
            <span
              v-if="item.row?.resolution?.freshness?.measured_at"
              class="text-muted-foreground text-xs"
            >
              {{
                t('resolution.measuredAt', {
                  when: momentPhrase(item.row.resolution.freshness.measured_at, timezone, today),
                })
              }}
            </span>
            <span v-if="unverified(item.row)" class="text-warning text-xs">{{
              t('activityDay.unverified')
            }}</span>
            <span v-if="item.row?.source_unavailable" class="text-warning text-xs">{{
              t('resolution.status.unavailable')
            }}</span>
          </button>
        </div>
      </div>
    </section>

    <section class="bg-card space-y-2 rounded-3xl border p-4" data-testid="activity-week">
      <div class="flex flex-wrap items-baseline justify-between gap-x-2">
        <h2 class="text-sm font-medium">{{ t('activityDay.weekTitle') }}</h2>
        <p class="text-muted-foreground text-xs">
          {{ rangeLabel(weekRange.from, weekRange.to) }}
        </p>
      </div>
      <RefreshFailed :query="steps" />
      <div v-if="steps.isPending.value" role="status">
        <Skeleton class="h-24 w-full" /><span class="sr-only">{{ t('state.loading') }}</span>
      </div>
      <div v-else-if="steps.isLoadingError.value" class="space-y-2 text-sm" role="alert">
        <p>{{ t(describeError(steps.error.value)) }}</p>
        <Button variant="outline" class="min-h-11" @click="steps.refetch()">{{
          t('common.retry')
        }}</Button>
      </div>
      <template v-else>
        <ScrollStrip ref="weekStrip" class="-mx-3" data-testid="activity-week-strip">
          <div
            ref="weekButtons"
            class="flex w-full min-w-78 shrink-0 px-1"
            role="group"
            :aria-label="
              t('activityDay.weekLabel', {
                from: fullDayLabel(weekRange.from),
                to: fullDayLabel(weekRange.to),
              })
            "
          >
            <button
              v-for="day in days"
              :key="day.date"
              type="button"
              class="hover:bg-muted focus-visible:ring-ring flex min-h-11 min-w-11 flex-1 flex-col items-center rounded-xl px-0.5 py-1 outline-none focus-visible:ring-2"
              :class="
                day.date === date
                  ? 'bg-muted text-primary ring-primary ring-1'
                  : 'text-muted-foreground'
              "
              :aria-pressed="day.date === date"
              :aria-label="
                t('activityDay.dayLabel', {
                  date: fullDayLabel(day.date),
                  value: amount(day.row, 'steps'),
                  quality: day.value !== null ? t(`activityDay.${day.coverage}`) : '',
                })
              "
              :data-testid="`week-day-${day.date}`"
              :data-status="day.value !== null ? 'available' : statusOf(day.row)"
              @click="emit('go', day.date)"
            >
              <svg
                viewBox="0 0 32 64"
                width="32"
                height="64"
                class="w-full max-w-8"
                aria-hidden="true"
              >
                <rect
                  v-if="day.value !== null && Number(day.value) > 0"
                  x="7"
                  :y="60 - barHeight(day.value)"
                  width="18"
                  :height="barHeight(day.value)"
                  rx="3"
                  class="fill-chart-1"
                />
                <circle v-else-if="day.value !== null" cx="16" cy="59" r="2" class="fill-chart-1" />
                <!-- A day without a total is a dash; why is told in its details. -->
                <text v-else x="16" y="49" text-anchor="middle" class="fill-current text-xs">
                  —
                </text>
              </svg>
              <span
                v-if="day.value !== null && Number(day.value) === 0"
                class="text-xs tabular-nums"
                aria-hidden="true"
                >0</span
              >
              <span v-else class="h-4 text-xs" aria-hidden="true">{{
                weekday.format(new Date(`${day.date}T00:00:00Z`))
              }}</span>
              <span class="text-xs tabular-nums" aria-hidden="true">{{
                Number(day.date.slice(8))
              }}</span>
            </button>
          </div>
        </ScrollStrip>
      </template>
      <Button variant="ghost" class="min-h-11 w-full justify-between" @click="emit('dynamics')"
        >{{ t('activityRedesign.openDynamics') }}<ChevronRight class="size-4"
      /></Button>
    </section>

    <Panel
      v-model:open="detailOpen"
      :title="detailTitle"
      :description="fullDayLabel(date)"
      :autofocus="false"
    >
      <template v-if="detail">
        <p class="text-3xl font-semibold tabular-nums" data-testid="day-detail-value">
          {{ amount(detailMetric, detail) }}
        </p>
        <p v-if="detail === 'active_minutes'" class="text-muted-foreground text-sm">
          {{ t('activityDay.activeHint') }}
        </p>
        <p v-if="valueOf(detailMetric) !== null" class="text-muted-foreground text-sm">
          {{ t(`activityDay.${coverageOf(detailMetric)}`) }}
        </p>
        <SourceLine
          :metric="detail"
          :contributions="detailMetric?.resolution?.contributions"
          :day="date"
        />
        <ResolutionExplanation
          v-if="detailMetric?.resolution"
          :resolution="detailMetric.resolution"
          :timezone="timezone"
          :today="today"
        />
        <div class="space-y-2">
          <h3 class="text-sm font-medium">{{ t('activityDay.history') }}</h3>
          <RefreshFailed :query="detailHistory" />
          <p
            v-if="detailHistory.isPending.value"
            role="status"
            class="text-muted-foreground text-sm"
          >
            {{ t('state.loading') }}
          </p>
          <div
            v-else-if="detailHistory.isLoadingError.value"
            class="space-y-2 text-sm"
            role="alert"
          >
            <p>{{ t(describeError(detailHistory.error.value)) }}</p>
            <Button variant="outline" class="min-h-11" @click="detailHistory.refetch()">{{
              t('common.retry')
            }}</Button>
          </div>
          <table v-else class="w-full text-left text-sm">
            <thead class="text-muted-foreground">
              <tr>
                <th class="py-2 font-normal">{{ t('activityDay.date') }}</th>
                <th class="py-2 font-normal">{{ t('activityDay.amount') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="day in days"
                :key="day.date"
                class="border-t"
                :class="{ 'bg-muted': day.date === date }"
              >
                <td class="py-3">{{ dayLabel(day.date, today) }}</td>
                <td class="py-3 tabular-nums">
                  {{ amount(historyRow(day.date), detail)
                  }}<span
                    v-if="valueOf(historyRow(day.date)) !== null"
                    class="text-muted-foreground block text-xs"
                    >{{ t(`activityDay.${coverageOf(historyRow(day.date))}`) }}</span
                  >
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <template v-if="detail === 'steps' && canWrite" #footer>
        <Button
          variant="outline"
          class="min-h-11 w-full"
          data-testid="day-correct-steps"
          @click="correctSteps"
          >{{ t('activityDay.correctSteps') }}</Button
        >
      </template>
    </Panel>
  </div>
</template>
