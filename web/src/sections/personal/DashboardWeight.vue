<script setup lang="ts">
import { useDisplayUnits, massValue, massUnit } from './displayUnits'
import { ChevronDown, ChevronRight, ChevronUp, Plus, Scale } from '@lucide/vue'
import { useResizeObserver } from '@vueuse/core'
import { computed, nextTick, ref, useId, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RouteLocationRaw } from 'vue-router'
import DataNotes from '@/components/DataNotes.vue'
import Panel from '@/components/Panel.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { dayIn, momentLabel, momentPhrase, rangeLabel, shiftDay } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import messages from '@/locales/dashboard-weight-ru.json'
import { amountText, provenance, signedKilograms, type Metric, type Observation } from './api'
import BodySourceDetails from './BodySourceDetails.vue'
import SourceLine from './SourceLine.vue'
import {
  DASHBOARD_WEIGHT_PERIODS,
  dashboardAxis,
  dashboardDateX,
  dashboardFatMeasurements,
  dashboardFatSeries,
  dashboardLine,
  dashboardNearestPoint,
  dashboardValueY,
  dashboardWeightChange,
  dashboardWeightConflicts,
  dashboardWeightPoints,
  dashboardWeightRange,
  type DashboardWeightPeriod,
  type DashboardWeightPoint,
} from './dashboardWeight'
import { useHistory } from './usePersonal'

const displayUnits = useDisplayUnits()
const props = withDefaults(
  defineProps<{
    day: string
    today: string
    timezone: string
    weight: Metric
    canWrite?: boolean
    editingRecordId?: string | null
    historyLink?: (period: DashboardWeightPeriod) => RouteLocationRaw
  }>(),
  { canWrite: false, editingRecordId: null },
)
const emit = defineEmits<{
  'add-weight': []
  edit: [record: Observation]
  history: [link: RouteLocationRaw]
}>()
// The few shared words (a decision's status) come from the global messages.
const { t } = useI18n({
  useScope: 'local',
  messages: { ru: messages },
  missingWarn: false,
  fallbackWarn: false,
})
const period = ref<DashboardWeightPeriod>(90)
const range = computed(() => dashboardWeightRange(props.day, period.value))
const weightRange = computed(() => ({ ...range.value, kind: 'weight' as const }))
const bodyRange = computed(() => ({ ...range.value, kind: 'body' as const }))
const weightHistory = useHistory(weightRange)
const bodyHistory = useHistory(bodyRange)
const points = computed(() => dashboardWeightPoints(weightHistory.data.value, range.value))
const fatSeries = computed(() => dashboardFatSeries(bodyHistory.data.value, range.value))
const fatPoints = computed(() => fatSeries.value.flatMap((series) => series.points))
const conflicts = computed(() => dashboardWeightConflicts(weightHistory.data.value, range.value))
const allPoints = computed(() =>
  [...points.value, ...fatPoints.value].sort(
    (a, b) => a.day.localeCompare(b.day) || a.key.localeCompare(b.key),
  ),
)
const weightAxis = computed(() => dashboardAxis(points.value))
const fatAxis = computed(() => dashboardAxis(fatPoints.value))
const changed = computed(() => dashboardWeightChange(points.value))
const latest = computed(() =>
  props.weight.status === 'available'
    ? [...(props.weight.records ?? [])]
        .filter((record) => !record.excluded)
        .sort((a, b) => Date.parse(b.start_at) - Date.parse(a.start_at))[0]
    : undefined,
)
const shown = computed(() =>
  props.weight.status === 'available'
    ? amountText('weight', props.weight.value, displayUnits.value)
    : null,
)
const target = computed(() =>
  props.weight.target && Number(props.weight.target) > 0
    ? amountText('weight', props.weight.target, displayUnits.value)
    : null,
)
const offline = computed(
  () =>
    props.weight.source_unavailable ||
    weightHistory.data.value?.records.some((record) =>
      ['unavailable', 'disabled'].includes(record.source_status ?? ''),
    ) ||
    [...(bodyHistory.data.value?.sources ?? [])].some(
      (source) => source.connection?.provider === 'ddx' && source.status !== 'connected',
    ),
)
const limited = computed(
  () =>
    weightHistory.data.value?.truncated ||
    bodyHistory.data.value?.truncated ||
    bodyHistory.data.value?.sources?.some((source) => source.error === 'history_restricted'),
)
const expanded = ref(false)
const panelOpen = ref(false)
let returningRecord: string | null = null
function editRecord(record: Observation) {
  returningRecord = record.id
  panelOpen.value = false
  emit('edit', record)
}
watch(
  () => props.editingRecordId,
  async (current, previous) => {
    if (current !== null || !returningRecord || previous !== returningRecord) return
    const id = returningRecord
    returningRecord = null
    panelOpen.value = true
    await nextTick()
    const target = document.getElementById(`${chartId}-correct-${id}`)
    target?.scrollIntoView({ block: 'nearest' })
    target?.focus({ preventScroll: true })
  },
)
const selected = ref<string | null>(null)
const focused = computed(() => allPoints.value.find((point) => point.key === selected.value))
const frame = useTemplateRef<HTMLElement>('frame')
const width = ref(360)
useResizeObserver(frame, ([entry]) => {
  if (entry && entry.contentRect.width > 0)
    width.value = Math.max(220, Math.round(entry.contentRect.width))
})
const chartId = useId()
watch([() => props.day, period], () => {
  selected.value = null
})
const dateText = (day: string) => `${day.slice(8)}.${day.slice(5, 7)}.${day.slice(0, 4)}`
const shortDate = (day: string) => `${day.slice(8)}.${day.slice(5, 7)}`
const x = (day: string) => dashboardDateX(day, range.value, width.value)
const y = (point: DashboardWeightPoint) =>
  dashboardValueY(point.value, point.kind === 'weight' ? weightAxis.value! : fatAxis.value!)
const xDates = computed(() => [
  range.value.from,
  shiftDay(range.value.from, Math.floor((period.value - 1) / 2)),
  range.value.to,
])
const historyLink = computed(
  () =>
    props.historyLink?.(period.value) ?? {
      name: 'personal-history',
      query: {
        kind: 'weight',
        period: period.value,
        end: props.day === props.today ? undefined : props.day,
      },
    },
)
const origin = (record: Observation) =>
  provenance(record, (kind) => t(`dashboardWeight.origin.${kind}`))
function label(point: DashboardWeightPoint) {
  const record = point.records[0]
  return [
    dateText(point.day),
    t(point.kind === 'weight' ? 'dashboardWeight.selectedWeight' : 'dashboardWeight.fat'),
    amountText(point.kind, String(point.value), displayUnits.value),
    record ? origin(record) : t('dashboardWeight.unknownSource'),
  ].join(' · ')
}
function inspect(event: PointerEvent) {
  const svg = event.currentTarget as SVGSVGElement
  const bounds = svg.getBoundingClientRect()
  const at = ((event.clientX - bounds.left) / bounds.width) * width.value
  const top = ((event.clientY - bounds.top) / bounds.height) * 180
  const nearest = dashboardNearestPoint(
    allPoints.value.map((point) => ({ point, x: x(point.day), y: y(point) })),
    { x: at, y: top },
  )
  selected.value = nearest?.point.key ?? null
}
function key(event: KeyboardEvent) {
  const index = allPoints.value.findIndex((point) => point.key === selected.value)
  if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    event.preventDefault()
    const next =
      event.key === 'ArrowRight'
        ? Math.min(allPoints.value.length - 1, index + 1)
        : Math.max(0, (index < 0 ? allPoints.value.length : index) - 1)
    selected.value = allPoints.value[next]?.key ?? null
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    panelOpen.value = true
  }
}
function addWeight() {
  panelOpen.value = false
  emit('add-weight')
}
const records = computed(() =>
  [
    ...(weightHistory.data.value?.records ?? []).map((record) => ({
      record,
      day: undefined as string | undefined,
      archive: undefined,
    })),
    ...dashboardFatMeasurements(bodyHistory.data.value, range.value),
  ].sort((a, b) => Date.parse(b.record.start_at) - Date.parse(a.record.start_at)),
)
</script>

<template>
  <section
    class="dashboard-weight bg-card rounded-3xl border p-4 sm:p-5"
    data-testid="dashboard-weight"
  >
    <!-- At 200% text «Подробнее» no longer fits beside the title and goes below it. -->
    <div class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="flex items-center gap-2 font-semibold">
        <Scale class="size-4" />{{ t('personal.metric.weight') }}
      </h2>
      <Button variant="ghost" class="hidden min-h-11 md:inline-flex" @click="panelOpen = true"
        >{{ t('dashboardWeight.more') }}<ChevronRight
      /></Button>
    </div>
    <div
      class="dashboard-weight__summary mt-2 flex flex-wrap items-center justify-between gap-3"
      data-testid="weight-card"
    >
      <p v-if="weight.status === 'conflict'" class="text-warning text-lg font-semibold">
        {{ t('resolution.status.conflict') }}
      </p>
      <button
        v-else-if="shown"
        type="button"
        class="dashboard-weight__number text-primary flex min-h-11 items-center gap-2 rounded-lg text-4xl font-semibold tracking-tight tabular-nums"
        data-testid="weight-details-open"
        :aria-label="`${shown} · ${t('dashboardWeight.table')}`"
        @click="panelOpen = true"
      >
        {{ shown }}<ChevronRight class="size-5 shrink-0" aria-hidden="true" />
      </button>
      <p v-else class="text-muted-foreground text-lg">
        {{
          t(
            weight.status === 'unavailable'
              ? 'dashboardWeight.unavailable'
              : 'dashboardWeight.noWeight',
          )
        }}
      </p>
      <Button
        v-if="canWrite"
        class="dashboard-weight__add min-h-11 whitespace-normal md:hidden"
        data-testid="weight-add-mobile"
        :aria-label="t('dashboardWeight.add')"
        @click="emit('add-weight')"
        ><Plus />{{ t('dashboardWeight.addShort') }}</Button
      >
      <div
        v-if="changed !== null"
        class="dashboard-weight__change bg-muted rounded-xl px-3 py-2 text-right text-sm"
      >
        <p class="font-medium">
          {{
            t('dashboardWeight.change', {
              value: signedKilograms(changed, t('dashboardWeight.kg'), displayUnits),
            })
          }}
        </p>
        <p class="text-muted-foreground text-xs">
          {{ rangeLabel(points[0]!.day, points.at(-1)!.day) }}
        </p>
      </div>
      <p v-if="latest" class="dashboard-weight__latest text-muted-foreground basis-full text-xs">
        {{
          t('dashboardWeight.latest', {
            when: momentPhrase(latest.start_at, timezone, today),
          })
        }}
      </p>
    </div>
    <p v-if="target" class="text-muted-foreground mt-1 text-sm">
      {{ t('dashboardWeight.target', { value: target }) }}
    </p>
    <SourceLine metric="weight" :contributions="weight.resolution?.contributions" :day="day" />
    <Button v-if="canWrite" class="mt-3 hidden min-h-11 md:inline-flex" @click="emit('add-weight')"
      ><Plus />{{ t('dashboardWeight.add') }}</Button
    >
    <p v-if="offline" class="text-warning mt-2 text-sm" role="status">
      {{ t('dashboardWeight.offline') }}
    </p>
    <RefreshFailed :query="[weightHistory, bodyHistory]" class="mt-2" />
    <div class="mt-2 space-y-2 text-sm">
      <p v-if="weightHistory.isPending.value" role="status">
        {{ t('dashboardWeight.weightLoading') }}
      </p>
      <p v-if="bodyHistory.isPending.value" role="status">{{ t('dashboardWeight.fatLoading') }}</p>
      <div
        v-if="weightHistory.isLoadingError.value"
        class="text-destructive flex flex-wrap items-center gap-2"
      >
        <p>{{ t('dashboardWeight.weightError') }}</p>
        <Button
          variant="outline"
          class="min-h-11"
          :disabled="weightHistory.isFetching.value"
          @click="weightHistory.refetch()"
          >{{ t('dashboardWeight.retry') }}</Button
        >
      </div>
      <div
        v-if="bodyHistory.isLoadingError.value"
        class="text-destructive flex flex-wrap items-center gap-2"
      >
        <p>{{ t('dashboardWeight.fatError') }}</p>
        <Button
          variant="outline"
          class="min-h-11"
          :disabled="bodyHistory.isFetching.value"
          @click="bodyHistory.refetch()"
          >{{ t('dashboardWeight.retry') }}</Button
        >
      </div>
    </div>
    <button
      class="dashboard-weight-disclosure text-primary mt-2 flex min-h-11 w-full items-center justify-between text-sm font-medium"
      type="button"
      :aria-expanded="expanded"
      :aria-controls="chartId"
      @click="expanded = !expanded"
    >
      {{ t(expanded ? 'dashboardWeight.hideChart' : 'dashboardWeight.showChart', { count: period })
      }}<ChevronUp v-if="expanded" class="size-4" /><ChevronDown v-else class="size-4" />
    </button>
    <div :id="chartId" class="dashboard-weight-chart" :class="{ 'is-expanded': expanded }">
      <div class="mt-3 flex flex-wrap items-center justify-between gap-2">
        <p class="text-muted-foreground text-xs">
          {{ rangeLabel(range.from, range.to) }}
        </p>
        <div class="flex rounded-lg border p-0.5" :aria-label="t('dashboardWeight.period')">
          <button
            v-for="option in DASHBOARD_WEIGHT_PERIODS"
            :key="option"
            class="min-h-11 min-w-11 rounded-md px-2 text-sm"
            :class="
              period === option
                ? 'bg-accent text-accent-foreground font-medium'
                : 'text-muted-foreground'
            "
            type="button"
            :aria-pressed="period === option"
            @click="period = option"
          >
            {{ t('dashboardWeight.days', { count: option }) }}
          </button>
        </div>
      </div>
      <div class="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs" aria-hidden="true">
        <span class="flex items-center gap-2"
          ><i class="weight-legend" />{{ t('dashboardWeight.weightLegend') }}</span
        ><span class="flex items-center gap-2"
          ><i class="fat-legend" />{{ t('dashboardWeight.fatLegend') }}</span
        >
      </div>
      <div class="mt-2 space-y-2 text-sm">
        <p v-if="conflicts.length" class="text-warning">
          {{ t('dashboardWeight.conflictDays', { count: conflicts.length }) }}
        </p>
      </div>
      <figure ref="frame" class="mt-3">
        <svg
          v-if="allPoints.length"
          class="dashboard-weight-svg"
          data-testid="dashboard-weight-chart"
          :viewBox="`0 0 ${width} 180`"
          :width="width"
          height="180"
          tabindex="0"
          role="img"
          :aria-label="
            t('dashboardWeight.chartLabel', { from: dateText(range.from), to: dateText(range.to) })
          "
          @pointerdown="inspect"
          @pointermove="inspect"
          @keydown="key"
        >
          <g aria-hidden="true">
            <text x="0" y="13" class="weight-axis" data-testid="dashboard-weight-unit">
              {{ massUnit(displayUnits) }}
            </text>
            <text
              :x="width"
              y="13"
              text-anchor="end"
              class="fat-axis"
              data-testid="dashboard-fat-unit"
            >
              %
            </text>
            <template v-for="value in weightAxis?.ticks ?? []" :key="`kg:${value}`">
              <line
                x1="40"
                :x2="width - 40"
                :y1="dashboardValueY(value, weightAxis!)"
                :y2="dashboardValueY(value, weightAxis!)"
                class="weight-grid"
              />
              <text x="0" :y="dashboardValueY(value, weightAxis!) + 4" class="weight-axis">
                {{ formatAmount(massValue(value, displayUnits), 1) }}
              </text>
            </template>
            <text
              v-for="value in fatAxis?.ticks ?? []"
              :key="`fat:${value}`"
              :x="width"
              :y="dashboardValueY(value, fatAxis!) + 4"
              text-anchor="end"
              class="fat-axis"
            >
              {{ formatAmount(value, 1) }}
            </text>
            <path
              v-if="points.length > 1"
              :d="dashboardLine(points, range, width, conflicts)"
              class="weight-line"
            />
            <path
              v-for="series in fatSeries"
              :key="series.sourceId"
              :d="dashboardLine(series.points, range, width, [], fatAxis)"
              class="fat-line"
            />
            <template v-for="point in allPoints" :key="point.key">
              <circle
                v-if="point.kind === 'weight'"
                :cx="x(point.day)"
                :cy="y(point)"
                :r="selected === point.key ? 5 : 3"
                class="weight-point"
                data-testid="dashboard-weight-point"
              />
              <rect
                v-else
                :x="x(point.day) - 3.5"
                :y="y(point) - 3.5"
                width="7"
                height="7"
                class="fat-point"
                :class="{ 'is-selected': selected === point.key }"
                data-testid="dashboard-fat-point"
              />
            </template>
            <text
              v-for="(chartDay, index) in xDates"
              :key="chartDay"
              :x="x(chartDay)"
              y="173"
              :text-anchor="index === 0 ? 'start' : index === 2 ? 'end' : 'middle'"
              class="date-axis"
            >
              {{ shortDate(chartDay) }}
            </text>
          </g>
        </svg>
        <p
          v-else-if="
            !weightHistory.isPending.value &&
            !bodyHistory.isPending.value &&
            !weightHistory.isLoadingError.value &&
            !bodyHistory.isLoadingError.value
          "
          class="text-muted-foreground rounded-xl border p-4 text-sm"
        >
          {{ t('dashboardWeight.noPoints') }}
        </p>
        <figcaption class="text-muted-foreground mt-2 min-h-10 text-sm" aria-live="polite">
          {{ focused ? label(focused) : '' }}
        </figcaption>
      </figure>
      <p
        v-if="
          !bodyHistory.isPending.value && !bodyHistory.isLoadingError.value && fatPoints.length < 2
        "
        class="text-muted-foreground text-sm"
      >
        {{ t(fatPoints.length ? 'dashboardWeight.fatSingle' : 'dashboardWeight.noFat') }}
      </p>
      <DataNotes
        class="mt-2"
        :notes="[limited && t('dashboardWeight.truncated'), t('dashboardWeight.measuredOnly')]"
      />
      <div class="mt-2 flex flex-wrap gap-2">
        <Button variant="outline" class="min-h-11" @click="panelOpen = true">{{
          t('dashboardWeight.table')
        }}</Button
        ><Button variant="ghost" class="min-h-11" as-child
          ><RouterLink :to="historyLink" @click="emit('history', historyLink)">{{
            t('dashboardWeight.history')
          }}</RouterLink></Button
        >
      </div>
    </div>
    <Panel v-model:open="panelOpen" :title="t('dashboardWeight.detailsTitle')" :autofocus="false">
      <p class="text-muted-foreground text-sm">
        {{ rangeLabel(range.from, range.to) }}
      </p>
      <RefreshFailed :query="[weightHistory, bodyHistory]" />
      <p v-if="weightHistory.isPending.value" class="text-muted-foreground text-sm" role="status">
        {{ t('dashboardWeight.weightLoading') }}
      </p>
      <p v-if="bodyHistory.isPending.value" class="text-muted-foreground text-sm" role="status">
        {{ t('dashboardWeight.fatLoading') }}
      </p>
      <p v-if="weightHistory.isLoadingError.value" class="text-destructive text-sm">
        {{ t('dashboardWeight.weightError') }}
      </p>
      <p v-if="bodyHistory.isLoadingError.value" class="text-destructive text-sm">
        {{ t('dashboardWeight.fatError') }}
      </p>
      <ul v-if="records.length" class="divide-y" data-testid="dashboard-weight-records">
        <li
          v-for="row in records"
          :key="row.record.id"
          class="py-3"
          :data-testid="`weight-record-${row.record.id}`"
        >
          <div class="flex flex-wrap items-baseline justify-between gap-2">
            <p class="text-sm font-medium">
              {{
                t(row.record.kind === 'weight' ? 'personal.metric.weight' : 'dashboardWeight.fat')
              }}
            </p>
            <p class="font-medium tabular-nums">
              {{ amountText(row.record.kind, row.record.data.value, displayUnits) }}
            </p>
          </div>
          <p class="text-muted-foreground mt-1 text-xs">
            {{ momentLabel(row.record.start_at, timezone, today) }} · {{ origin(row.record) }}
          </p>
          <p v-if="row.record.excluded" class="text-warning mt-1 text-xs">
            {{ t('dashboardWeight.excluded') }}
          </p>
          <p v-if="row.record.corrected" class="text-muted-foreground mt-1 text-xs">
            {{ t('dashboardWeight.corrected') }}
          </p>
          <Button
            v-if="canWrite"
            :id="`${chartId}-correct-${row.record.id}`"
            variant="outline"
            class="mt-2 min-h-11"
            :data-testid="`weight-record-correct-${row.record.id}`"
            @click="editRecord(row.record)"
            >{{ t('dashboardWeight.correct') }}</Button
          >
          <p
            v-if="row.day && row.day !== dayIn(timezone, new Date(row.record.start_at))"
            class="text-muted-foreground mt-1 text-xs"
          >
            {{ t('dashboardWeight.calendarDate', { date: dateText(row.day) }) }}
          </p>
          <BodySourceDetails
            v-if="row.archive"
            :archive="row.archive"
            :timezone="timezone"
            :today="today"
          />
        </li>
      </ul>
      <p
        v-else-if="
          !weightHistory.isPending.value &&
          !bodyHistory.isPending.value &&
          !weightHistory.isLoadingError.value &&
          !bodyHistory.isLoadingError.value
        "
        class="text-muted-foreground text-sm"
      >
        {{ t('dashboardWeight.emptyTable') }}
      </p>
      <div class="flex flex-wrap gap-2">
        <Button v-if="canWrite" class="min-h-11" @click="addWeight"
          ><Plus />{{ t('dashboardWeight.add') }}</Button
        ><Button variant="outline" class="min-h-11" as-child
          ><RouterLink :to="historyLink" @click="emit('history', historyLink)">{{
            t('dashboardWeight.history')
          }}</RouterLink></Button
        >
      </div>
    </Panel>
  </section>
</template>

<style scoped>
.dashboard-weight {
  min-width: 0;
}
.dashboard-weight-disclosure {
  display: none;
}
.dashboard-weight-svg {
  display: block;
  width: 100%;
  height: 180px;
  touch-action: pan-y;
  border-radius: 0.75rem;
}
.dashboard-weight-svg:focus-visible {
  outline: 3px solid var(--ring);
  outline-offset: 3px;
}
.weight-grid {
  stroke: var(--border);
  stroke-width: 1;
}
.weight-axis,
.date-axis {
  fill: var(--muted-foreground);
  font-size: 12px;
}
.fat-axis {
  fill: var(--warning);
  font-size: 12px;
}
.weight-line {
  fill: none;
  stroke: var(--primary);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.fat-line {
  fill: none;
  stroke: var(--warning-fill);
  stroke-width: 2;
  stroke-dasharray: 6 4;
}
.weight-point {
  fill: var(--card);
  stroke: var(--primary);
  stroke-width: 2;
}
.fat-point {
  fill: var(--card);
  stroke: var(--warning-fill);
  stroke-width: 2;
}
.fat-point.is-selected {
  stroke-width: 3;
}
.weight-legend,
.fat-legend {
  display: inline-block;
  width: 24px;
  border-top: 2px solid var(--primary);
}
.fat-legend {
  border-top-color: var(--warning-fill);
  border-top-style: dashed;
}
@media (max-width: 767px) {
  .dashboard-weight__summary {
    gap: 8px;
  }
  .dashboard-weight__number {
    font-size: 2rem;
  }
  .dashboard-weight__add {
    max-width: 132px;
    gap: 6px;
    padding-inline: 12px;
    font-size: 0.8125rem;
  }
  .dashboard-weight__change {
    order: 4;
    flex-basis: 100%;
    padding: 0;
    border-radius: 0;
    background: none;
    text-align: left;
  }
  .dashboard-weight__change > p {
    display: inline;
  }
  .dashboard-weight__change > p + p::before {
    content: ' · ';
  }
  .dashboard-weight__latest {
    order: 3;
  }
  .dashboard-weight-disclosure {
    display: flex;
  }
  .dashboard-weight-chart {
    display: none;
  }
  .dashboard-weight-chart.is-expanded {
    display: block;
  }
}
</style>
