<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import { useMediaQuery } from '@vueuse/core'
import { computed, nextTick, ref, shallowRef, toRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import Panel from '@/components/Panel.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { formatAmount } from '@/core/numbers'
import messages from '@/locales/dashboard-nutrition-ru.json'
import GoalPanel from '@/sections/food/GoalPanel.vue'
import type { Day } from '@/sections/food/api'
import { useSession } from '@/stores/session'
import {
  nutritionCeiling,
  nutritionNumber,
  nutritionStatus,
  type NutritionRow,
} from './dashboardNutrition'
import { useDashboardNutrition, useDashboardNutritionDay } from './useDashboardNutrition'

const props = defineProps<{ day: string; today: string; timezone: string }>()
const { t } = useI18n({ useScope: 'local', messages: { ru: messages } })
const session = useSession()
const canRead = computed(() => session.can('nutrition.read'))
const canWrite = computed(() => canRead.value && session.can('nutrition.write'))
const period = ref<7 | 14>(7)
const { diary, history, selected, rows, range } = useDashboardNutrition(toRef(props, 'day'), period)
const chartSelection = ref<string | null>(null)
const inspected = computed(
  () => rows.value.find((row) => row.date === chartSelection.value) ?? null,
)
const inspectedOpen = ref(false)
const wide = useMediaQuery('(min-width: 768px)')
const detailsOpen = ref(false)
watch(wide, (value) => (detailsOpen.value = value), { immediate: true })
function toggleDetails(event: Event) {
  detailsOpen.value = (event.target as HTMLDetailsElement).open
}
function inspect(row: NutritionRow) {
  chartSelection.value = row.date
  inspectedOpen.value = true
}
const goalDay = ref(props.day)
const goalOpen = ref(false)
const goalSnapshot = shallowRef<Day['goal']>(null)
const goalBusy = ref(false)
const goalError = ref<unknown>(null)
const goalQuery = useDashboardNutritionDay(goalDay, goalOpen)
let goalRequest = 0

async function openGoals() {
  if (!canWrite.value || goalBusy.value) return
  const request = ++goalRequest
  goalDay.value = props.day
  goalBusy.value = true
  goalError.value = null
  await nextTick()
  try {
    const result = await goalQuery.refetch()
    if (request !== goalRequest || !canWrite.value) return
    if (result.error) goalError.value = result.error
    else if (result.data?.date === goalDay.value) {
      // Keep the revision that populated the form even if SSE refreshes its query while editing.
      goalSnapshot.value = result.data.goal
      goalOpen.value = true
    }
  } finally {
    if (request === goalRequest) goalBusy.value = false
  }
}

watch([() => props.day, canWrite], () => {
  goalRequest++
  goalOpen.value = false
  goalSnapshot.value = null
  goalBusy.value = false
  goalError.value = null
})
watch(goalOpen, (open) => {
  if (!open) goalSnapshot.value = null
})
watch([() => props.day, period], () => {
  chartSelection.value = null
  inspectedOpen.value = false
})

const fullDate = (day: string) =>
  new Intl.DateTimeFormat('ru-RU', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${day}T00:00:00Z`))
const shortDate = (day: string) =>
  new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(`${day}T00:00:00Z`))
const amount = (value: string | null | undefined, digits = 0) =>
  nutritionNumber(value) == null ? '—' : formatAmount(value, digits)
// Today's diary is naturally still open: it gets no status until the day is over.
const statusLabel = (row: NutritionRow) => {
  const reason = nutritionStatus(row).reason
  return reason === 'dayOpen' && row.date === props.today ? '' : t(`dashboardNutrition.${reason}`)
}
const describe = (row: NutritionRow) =>
  row.logged
    ? t('dashboardNutrition.dateDetails', {
        day: fullDate(row.date),
        calories: row.energyComplete
          ? amount(row.energy)
          : t('dashboardNutrition.known', { value: amount(row.energy) }),
        limit: amount(row.limit),
        protein: row.proteinComplete
          ? amount(row.protein, 1)
          : t('dashboardNutrition.known', { value: amount(row.protein, 1) }),
        minimum: amount(row.minimum, 1),
        status: statusLabel(row),
      })
    : t('dashboardNutrition.emptyDay', { day: fullDate(row.date) })
const macros = computed(() => [
  { key: 'fat_g', label: 'fat', value: selected.value?.fat },
  { key: 'carbohydrate_g', label: 'carbs', value: selected.value?.carbs },
])
const remaining = computed(() => {
  const value = diary.data.value?.remaining_kcal
  if (
    !selected.value?.logged ||
    !diary.data.value?.remaining_complete ||
    value == null ||
    !value.trim()
  )
    return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
})
const ceiling = computed(() => nutritionCeiling(rows.value))
const y = (value: number) => 18 + (1 - value / ceiling.value) * 108
const plotWidth = computed(() => Math.max(1, rows.value.length) * 44)
const passed = computed(
  () => rows.value.filter((row) => nutritionStatus(row).tone === 'goal').length,
)
const hasEntries = computed(() => rows.value.some((row) => row.logged))
const marker = (row: NutritionRow) =>
  ({ goal: '✓', missed: '−', pending: '○', empty: '—' })[nutritionStatus(row).tone]
const barValue = (row: NutritionRow) => (row.logged ? nutritionNumber(row.energy) : null)
const goalSegments = computed(() =>
  rows.value.flatMap((row, index) => {
    const limit = nutritionNumber(row.limit)
    if (limit == null) return []
    const previous = index ? nutritionNumber(rows.value[index - 1]?.limit) : null
    return [
      { key: `${row.date}-line`, x1: index * 44, x2: (index + 1) * 44, y1: y(limit), y2: y(limit) },
      ...(previous != null
        ? [
            {
              key: `${row.date}-step`,
              x1: index * 44,
              x2: index * 44,
              y1: y(previous),
              y2: y(limit),
            },
          ]
        : []),
    ]
  }),
)
const scrollArea = ref<HTMLElement | null>(null)
watch(
  [() => range.value.start, () => rows.value.length, scrollArea],
  async () => {
    await nextTick()
    if (scrollArea.value) scrollArea.value.scrollLeft = scrollArea.value.scrollWidth
  },
  { flush: 'post', immediate: true },
)
</script>

<template>
  <article
    v-if="canRead"
    class="dashboard-nutrition bg-card min-w-0 space-y-4 rounded-3xl border p-4 md:p-5"
    data-testid="dashboard-nutrition"
    :data-timezone="timezone"
  >
    <header class="flex flex-wrap items-baseline justify-between gap-2">
      <h2 class="text-base font-semibold">{{ t('dashboardNutrition.title') }}</h2>
      <p class="text-muted-foreground text-xs">
        {{ day === today ? t('dashboardNutrition.today') : fullDate(day) }}
      </p>
    </header>
    <RefreshFailed :query="[diary, history]" />
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div class="min-w-0 flex-1 basis-40 space-y-2">
        <LoadingState v-if="diary.isPending.value" :rows="1" />
        <ErrorState
          v-else-if="diary.isLoadingError.value"
          :error="diary.error.value"
          @retry="diary.refetch()"
        />
        <template v-else-if="selected">
          <p class="text-3xl font-semibold tabular-nums">
            <span class="whitespace-nowrap" data-testid="nutrition-energy">{{
              selected.logged ? amount(selected.energy) : '—'
            }}</span>
            <span class="text-muted-foreground text-sm font-normal">{{
              selected.limit
                ? t('dashboardNutrition.of', { value: amount(selected.limit) })
                : t('dashboardNutrition.kcal')
            }}</span>
          </p>
          <p v-if="!selected.limit" class="text-sm">{{ t('dashboardNutrition.noLimit') }}</p>
          <p v-if="remaining !== null" class="text-muted-foreground text-sm">
            {{
              t(remaining < 0 ? 'dashboardNutrition.above' : 'dashboardNutrition.remaining', {
                value: formatAmount(Math.abs(remaining), 0),
              })
            }}
          </p>
        </template>
      </div>
      <Button as-child variant="outline" class="min-h-11 max-w-full whitespace-normal">
        <RouterLink
          :to="{ name: 'food', query: { day } }"
          :aria-label="t('dashboardNutrition.selectedDay', { day: fullDate(day) })"
          data-testid="nutrition-diary"
          ><span class="md:hidden">{{ t('dashboardNutrition.shortDiary') }}</span
          ><span class="hidden md:inline">{{ t('dashboardNutrition.diary') }}</span></RouterLink
        >
      </Button>
    </div>
    <template v-if="selected">
      <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 text-sm">
        <p class="tabular-nums" data-testid="nutrition-protein">
          {{ t('dashboardNutrition.protein') }} ·
          {{
            selected.logged && !selected.proteinComplete
              ? t('dashboardNutrition.known', { value: amount(selected.protein, 1) })
              : amount(selected.protein, 1)
          }}
          {{ t('dashboardNutrition.grams') }}
        </p>
        <p class="text-muted-foreground text-xs">
          {{
            selected.minimum
              ? t('dashboardNutrition.minimum', { value: amount(selected.minimum, 1) })
              : t('dashboardNutrition.noMinimum')
          }}
        </p>
      </div>
      <div class="flex flex-wrap gap-x-3 gap-y-1 text-xs">
        <p
          v-if="statusLabel(selected)"
          class="font-medium"
          :data-state="nutritionStatus(selected).tone"
        >
          {{ nutritionStatus(selected).tone === 'goal' ? '✓ ' : '' }}{{ statusLabel(selected) }}
        </p>
        <p v-if="selected.logged && !selected.energyComplete" class="text-muted-foreground">
          {{ t('dashboardNutrition.energyUnknown') }}
        </p>
        <p v-if="selected.logged && !selected.proteinComplete" class="text-muted-foreground">
          {{ t('dashboardNutrition.proteinPartial') }}
        </p>
        <p
          v-if="diary.data.value?.drafts.length"
          class="text-muted-foreground"
          data-testid="nutrition-drafts"
        >
          {{ t('dashboardNutrition.drafts', { count: diary.data.value.drafts.length }) }}
        </p>
      </div>
    </template>
    <details
      class="nutrition-details space-y-3"
      :open="detailsOpen"
      data-testid="nutrition-details"
      @toggle="toggleDetails"
    >
      <summary
        class="text-primary flex min-h-11 cursor-pointer list-none items-center justify-between text-sm font-medium md:hidden [&::-webkit-details-marker]:hidden"
        data-testid="nutrition-details-toggle"
      >
        {{ t('dashboardNutrition.more') }}
        <ChevronDown class="size-4" :class="{ 'rotate-180': detailsOpen }" aria-hidden="true" />
      </summary>
      <dl v-if="selected" class="grid grid-cols-2 gap-2 border-t pt-3 text-sm">
        <div v-for="macro in macros" :key="macro.key" class="min-w-0 space-y-1">
          <dt class="text-muted-foreground text-xs">
            {{ t(`dashboardNutrition.${macro.label}`) }}
          </dt>
          <dd class="tabular-nums">
            {{
              selected.logged && !diary.data.value?.totals[macro.key]?.complete
                ? t('dashboardNutrition.known', { value: amount(macro.value, 1) })
                : amount(macro.value, 1)
            }}
            <span class="text-muted-foreground text-xs">{{ t('dashboardNutrition.grams') }}</span>
          </dd>
        </div>
      </dl>
      <Button
        v-if="canWrite"
        type="button"
        variant="outline"
        class="min-h-11 whitespace-normal"
        data-testid="nutrition-goals"
        :disabled="goalBusy"
        @click="openGoals"
        >{{
          selected?.limit ? t('dashboardNutrition.changeGoals') : t('dashboardNutrition.setGoals')
        }}</Button
      >
      <p v-if="goalBusy" role="status" class="text-muted-foreground text-sm">
        {{ t('dashboardNutrition.loadingGoals') }}
      </p>
      <ErrorState v-if="goalError" :error="goalError" @retry="openGoals" />
    </details>

    <section class="space-y-2 border-t pt-3" :aria-label="t('dashboardNutrition.chart')">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <h3 class="text-sm font-medium">{{ t('dashboardNutrition.chart') }}</h3>
        <div class="flex gap-1" :aria-label="t('dashboardNutrition.period')">
          <Button
            v-for="days in [7, 14] as const"
            :key="days"
            type="button"
            variant="ghost"
            class="min-h-11 min-w-11 px-3"
            :aria-pressed="period === days"
            :data-testid="`nutrition-period-${days}`"
            @click="period = days"
            >{{ t('dashboardNutrition.days', { count: days }) }}</Button
          >
        </div>
      </div>
      <LoadingState v-if="history.isPending.value" :rows="1" />
      <ErrorState
        v-else-if="history.isLoadingError.value"
        :error="history.error.value"
        @retry="history.refetch()"
      />
      <template v-else-if="hasEntries">
        <p class="text-muted-foreground text-xs">
          {{ t('dashboardNutrition.passed', passed) }}
        </p>
        <div
          class="nutrition-chart"
          :aria-label="
            t('dashboardNutrition.chartLabel', {
              start: fullDate(range.start),
              end: fullDate(range.end),
            })
          "
        >
          <svg class="nutrition-axis" viewBox="0 0 38 170" aria-hidden="true">
            <text x="0" y="10">{{ t('dashboardNutrition.kcal') }}</text>
            <text v-for="tick in [ceiling, ceiling / 2, 0]" :key="tick" x="0" :y="y(tick) + 4">
              {{ formatAmount(tick, 0) }}
            </text>
          </svg>
          <div ref="scrollArea" class="nutrition-chart-scroll" data-testid="nutrition-chart-scroll">
            <div
              class="nutrition-plot"
              :class="period === 14 ? 'nutrition-plot-14' : 'nutrition-plot-7'"
            >
              <svg
                class="nutrition-svg"
                :viewBox="`0 0 ${plotWidth} 170`"
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                <line
                  v-for="tick in [ceiling, ceiling / 2, 0]"
                  :key="tick"
                  class="nutrition-grid"
                  x1="0"
                  :x2="plotWidth"
                  :y1="y(tick)"
                  :y2="y(tick)"
                />
                <line
                  v-for="segment in goalSegments"
                  :key="segment.key"
                  class="nutrition-limit"
                  :x1="segment.x1"
                  :x2="segment.x2"
                  :y1="segment.y1"
                  :y2="segment.y2"
                />
                <g v-for="(row, index) in rows" :key="row.date">
                  <rect
                    v-if="barValue(row) !== null"
                    :class="`nutrition-bar nutrition-tone-${nutritionStatus(row).tone}`"
                    :x="index * 44 + 9"
                    :y="y(barValue(row)!)"
                    width="26"
                    :height="y(0) - y(barValue(row)!)"
                    rx="3"
                  />
                  <text
                    class="nutrition-marker"
                    :x="index * 44 + 22"
                    :y="barValue(row) !== null ? Math.max(12, y(barValue(row)!) - 5) : y(0) - 5"
                    text-anchor="middle"
                  >
                    {{ marker(row) }}
                  </text>
                </g>
              </svg>
              <button
                v-for="row in rows"
                :key="row.date"
                type="button"
                class="nutrition-day"
                :class="{ 'nutrition-day-selected': inspected?.date === row.date }"
                :aria-label="describe(row)"
                aria-haspopup="dialog"
                :aria-pressed="inspected?.date === row.date"
                :data-testid="`nutrition-day-${row.date}`"
                :data-state="nutritionStatus(row).tone"
                @click="inspect(row)"
                @focus="chartSelection = row.date"
              >
                <span aria-hidden="true">{{ shortDate(row.date) }}</span>
              </button>
            </div>
          </div>
        </div>
        <ul
          class="text-muted-foreground flex flex-wrap gap-x-3 gap-y-1 text-xs"
          aria-label="Легенда графика"
        >
          <li>✓ {{ t('dashboardNutrition.goalLegend') }}</li>
          <li>− {{ t('dashboardNutrition.missedLegend') }}</li>
          <li>○ {{ t('dashboardNutrition.pendingLegend') }}</li>
          <li>
            <span class="nutrition-limit-swatch" aria-hidden="true"></span>
            {{ t('dashboardNutrition.limitLegend') }}
          </li>
        </ul>
      </template>
      <p v-else class="text-muted-foreground py-3 text-sm">
        {{ t('dashboardNutrition.emptyPeriod') }}
      </p>
      <p class="nutrition-readout sr-only" aria-live="polite" data-testid="nutrition-readout">
        {{
          inspected
            ? describe(inspected)
            : t(hasEntries ? 'dashboardNutrition.selectHint' : 'dashboardNutrition.emptyHint')
        }}
      </p>
    </section>
    <Panel
      v-if="inspected"
      v-model:open="inspectedOpen"
      :title="fullDate(inspected.date)"
      :autofocus="false"
      data-testid="nutrition-day-panel"
    >
      <p class="text-sm" data-testid="nutrition-day-description">{{ describe(inspected) }}</p>
      <Button as-child variant="outline" class="min-h-11 whitespace-normal">
        <RouterLink
          :to="{ name: 'food', query: { day: inspected.date } }"
          :aria-label="t('dashboardNutrition.selectedDay', { day: fullDate(inspected.date) })"
          data-testid="nutrition-selected-diary"
          >{{ t('dashboardNutrition.diary') }}</RouterLink
        >
      </Button>
    </Panel>
    <GoalPanel v-if="canWrite" v-model:open="goalOpen" :day="goalDay" :goal="goalSnapshot" />
  </article>
</template>

<style scoped>
.nutrition-chart {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
}
.nutrition-axis {
  width: 38px;
  height: 170px;
  fill: var(--muted-foreground);
  font-size: 11px;
}
.nutrition-chart-scroll {
  min-width: 0;
  overflow-x: auto;
  overscroll-behavior-inline: contain;
}
.nutrition-plot {
  position: relative;
  display: grid;
  height: 170px;
}
.nutrition-plot-7 {
  min-width: 308px;
  grid-template-columns: repeat(7, minmax(44px, 1fr));
}
.nutrition-plot-14 {
  min-width: 616px;
  grid-template-columns: repeat(14, minmax(44px, 1fr));
}
.nutrition-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 170px;
  pointer-events: none;
}
.nutrition-grid {
  stroke: var(--border);
  stroke-width: 1;
}
.nutrition-limit {
  stroke: var(--muted-foreground);
  stroke-width: 1.3;
  stroke-dasharray: 4 3;
}
.nutrition-bar.nutrition-tone-goal {
  fill: var(--primary);
}
.nutrition-bar.nutrition-tone-missed {
  fill: var(--warning-fill);
}
.nutrition-bar.nutrition-tone-pending {
  fill: var(--muted-foreground);
  opacity: 0.45;
}
.nutrition-marker {
  fill: var(--foreground);
  font-size: 12px;
}
.nutrition-day {
  z-index: 1;
  display: flex;
  min-width: 44px;
  min-height: 44px;
  align-items: flex-end;
  justify-content: center;
  padding: 0 0 10px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--muted-foreground);
}
.nutrition-day:hover {
  background: var(--nutrition-note-background);
}
.nutrition-day:focus-visible,
.nutrition-day-selected {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
.nutrition-readout {
  overflow-wrap: anywhere;
}
.nutrition-limit-swatch {
  display: inline-block;
  width: 12px;
  border-top: 1px dashed var(--muted-foreground);
  vertical-align: middle;
}
</style>
