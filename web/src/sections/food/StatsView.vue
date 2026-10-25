<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { BarChart3, ChevronLeft, ChevronRight } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import SegmentedControl from '@/components/SegmentedControl.vue'
import EmptyState from '@/components/state/EmptyState.vue'
import ErrorState from '@/components/state/ErrorState.vue'
import LoadingState from '@/components/state/LoadingState.vue'
import RefreshFailed from '@/components/state/RefreshFailed.vue'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { api } from '@/core/client'
import { dayIn, fullDayLabel, isIsoDay } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { cn } from '@/lib/utils'
import { useSession } from '@/stores/session'
import {
  MACROS,
  Stats,
  periodOf,
  shiftPeriod,
  summarize,
  type Period,
  type PeriodKind,
  type StatsDay,
} from './stats'

/** Week and month statistics of the diary (plan 3.8): from the food screen's «Ещё». */
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const session = useSession()
const today = computed(() => dayIn(session.me?.timezone ?? 'UTC'))
const kind = computed<PeriodKind>(() => (route.query.period === 'month' ? 'month' : 'week'))
const anchor = computed(() => (isIsoDay(route.query.day) ? route.query.day : today.value))
const period = computed(() => periodOf(kind.value, anchor.value))
function show(next: Period) {
  void router.replace({
    query: { period: next.kind === 'month' ? 'month' : undefined, day: next.start },
  })
}
const kinds = computed(() => [
  { value: 'week', label: t('stats.week') },
  { value: 'month', label: t('stats.month') },
])
const later = computed(() => period.value.end < today.value)
const dayMonth = new Intl.DateTimeFormat('ru-RU', {
  day: 'numeric',
  month: 'long',
  timeZone: 'UTC',
})
const monthYear = new Intl.DateTimeFormat('ru-RU', {
  month: 'long',
  year: 'numeric',
  timeZone: 'UTC',
})
const at = (day: string) => new Date(`${day}T00:00:00Z`)
// «28 сентября — 4 октября 2026» for a week, «октябрь 2026 г.» for a month.
const title = computed(() =>
  kind.value === 'month'
    ? monthYear.format(at(period.value.start))
    : `${dayMonth.format(at(period.value.start))} — ${dayMonth.format(at(period.value.end))} ${period.value.end.slice(0, 4)}`,
)

const stats = useQuery({
  queryKey: computed(() => ['nutrition', 'range', period.value.start, period.value.end]),
  queryFn: async ({ signal }) =>
    Stats.parse(
      await api.get(
        `/v1/nutrition/range?start=${period.value.start}&end=${period.value.end}`,
        signal,
      ),
    ),
})
const days = computed(() => stats.data.value?.days ?? [])
const summary = computed(() => summarize(days.value))

// Bars: each day's energy against the period's largest day or goal, the goal as a mark.
const scale = computed(() =>
  Math.max(1, ...days.value.flatMap((day) => [Number(day.energy_kcal), Number(day.target ?? 0)])),
)
const percent = (value: number) => `${Math.min(100, (value / scale.value) * 100)}%`
const weekday = new Intl.DateTimeFormat('ru-RU', { weekday: 'short', timeZone: 'UTC' })
const barLabel = (day: StatsDay) =>
  kind.value === 'week'
    ? weekday.format(new Date(`${day.date}T00:00:00Z`))
    : Number(day.date.slice(8)) % 5 === 1
      ? String(Number(day.date.slice(8)))
      : ''
const over = (day: StatsDay) => day.target !== null && Number(day.energy_kcal) > Number(day.target)
const describe = (day: StatsDay) =>
  day.logged
    ? t('stats.dayBar', {
        day: fullDayLabel(day.date),
        kcal: formatAmount(day.energy_kcal, 0),
        target: day.target ? formatAmount(day.target, 0) : '—',
      })
    : t('stats.dayEmpty', { day: fullDayLabel(day.date) })
const share = (value: number) => `${Math.round(value * 100)}%`
// One hue per meal from the chart palette: shades of one colour read as one meal.
const KIND_COLORS = {
  breakfast: 'bg-chart-1',
  lunch: 'bg-chart-2',
  dinner: 'bg-chart-3',
  snack: 'bg-chart-4',
  none: 'bg-chart-5',
} as const
const hasGoal = computed(() => days.value.some((day) => day.target))
const hasOver = computed(() => days.value.some(over))
</script>

<template>
  <PageHeader :title="t('stats.title')" :back="{ name: 'food' }" />
  <div class="mx-auto max-w-3xl space-y-5 px-4 pb-8 md:px-8">
    <div class="flex flex-wrap items-center gap-2">
      <SegmentedControl
        :segments="kinds"
        :label="t('stats.periodKind')"
        :model-value="kind"
        @update:model-value="show(periodOf($event as PeriodKind, anchor))"
      />
      <div class="ms-auto flex items-center gap-1">
        <Button
          variant="ghost"
          size="icon"
          :aria-label="t('stats.earlier')"
          @click="show(shiftPeriod(period, -1))"
          ><ChevronLeft
        /></Button>
        <Button
          variant="ghost"
          size="icon"
          :aria-label="t('stats.later')"
          :disabled="!later"
          @click="show(shiftPeriod(period, 1))"
          ><ChevronRight
        /></Button>
      </div>
    </div>
    <h2 class="text-lg font-semibold first-letter:uppercase" data-testid="stats-period">
      {{ title }}
    </h2>

    <RefreshFailed :query="stats" />
    <LoadingState v-if="stats.isPending.value" :rows="3" />
    <ErrorState
      v-else-if="stats.isLoadingError.value"
      :error="stats.error.value"
      @retry="stats.refetch()"
    />
    <EmptyState
      v-else-if="!summary.logged"
      :icon="BarChart3"
      :title="t('stats.empty')"
      :description="t('stats.emptyText')"
    />
    <template v-else>
      <section class="bg-card space-y-3 rounded-3xl border p-5" aria-labelledby="stats-chart">
        <h3 id="stats-chart" class="font-medium">{{ t('stats.chart') }}</h3>
        <ul class="flex h-36 items-end gap-1" data-testid="stats-bars">
          <li
            v-for="day in days"
            :key="day.date"
            class="relative flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1"
            :aria-label="describe(day)"
          >
            <span class="relative w-full flex-1">
              <span
                v-if="day.target"
                class="bg-foreground/70 absolute -inset-x-0.5 z-10 h-0.5 rounded-full"
                :style="{ bottom: percent(Number(day.target)) }"
                aria-hidden="true"
              />
              <span
                :class="
                  cn(
                    'absolute inset-x-0 bottom-0 rounded-t-md',
                    over(day) ? 'bg-warning-fill' : 'bg-primary',
                  )
                "
                :style="{ height: percent(Number(day.energy_kcal)) }"
                aria-hidden="true"
              />
            </span>
            <span class="text-muted-foreground h-4 text-xs tabular-nums" aria-hidden="true">{{
              barLabel(day)
            }}</span>
          </li>
        </ul>
        <ul
          class="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-sm"
          data-testid="stats-legend"
        >
          <li v-if="hasGoal" class="flex items-center gap-1.5">
            <span class="bg-foreground/70 h-0.5 w-4 rounded-full" aria-hidden="true" />{{
              t('stats.legend.goal')
            }}
          </li>
          <li class="flex items-center gap-1.5">
            <span class="bg-primary size-2.5 rounded-sm" aria-hidden="true" />{{
              t('stats.legend.within')
            }}
          </li>
          <li v-if="hasOver" class="flex items-center gap-1.5">
            <span class="bg-warning-fill size-2.5 rounded-sm" aria-hidden="true" />{{
              t('stats.legend.over')
            }}
          </li>
        </ul>
      </section>

      <section class="bg-card space-y-4 rounded-3xl border p-5" aria-labelledby="stats-average">
        <div>
          <h3 id="stats-average" class="font-medium">{{ t('stats.perDay') }}</h3>
          <p class="text-muted-foreground text-sm">
            {{ t('stats.loggedDays', { logged: summary.logged, days: summary.days }) }}
          </p>
        </div>
        <div class="space-y-1.5" data-testid="stats-kcal">
          <p class="flex items-baseline justify-between gap-3">
            <span class="text-2xl font-semibold tabular-nums"
              >{{ formatAmount(summary.kcal, 0) }}
              <span class="text-muted-foreground text-sm font-normal">{{
                t('food.kcal')
              }}</span></span
            >
            <span v-if="summary.target" class="text-muted-foreground text-sm tabular-nums">{{
              t('stats.goal', { value: formatAmount(summary.target, 0) })
            }}</span>
          </p>
          <Progress
            v-if="summary.target"
            :model-value="Math.min(100, (summary.kcal! / summary.target) * 100)"
            :tone="summary.kcal! > summary.target ? 'warning' : 'default'"
          />
          <p
            v-if="summary.target && summary.kcal! > summary.target"
            class="text-warning text-sm tabular-nums"
            data-testid="stats-over"
          >
            {{ t('food.over', { value: formatAmount(summary.kcal! - summary.target, 0) }) }}
          </p>
        </div>
        <dl class="grid grid-cols-[repeat(auto-fill,minmax(8rem,1fr))] gap-3 text-sm">
          <div v-for="macro in MACROS" :key="macro" class="space-y-1">
            <dt class="text-muted-foreground">{{ t(`food.macros.${macro}`) }}</dt>
            <dd class="tabular-nums">
              {{ t('food.grams', { value: formatAmount(summary.macros[macro].average, 1) }) }}
              <span v-if="summary.macros[macro].target" class="text-muted-foreground"
                >/ {{ formatAmount(summary.macros[macro].target, 0) }}</span
              >
            </dd>
            <Progress
              v-if="summary.macros[macro].target"
              class="h-1.5"
              :model-value="
                Math.min(
                  100,
                  (summary.macros[macro].average! / summary.macros[macro].target!) * 100,
                )
              "
            />
          </div>
        </dl>
        <ul class="text-muted-foreground space-y-1 text-sm" data-testid="stats-conclusions">
          <li v-if="summary.withGoal">
            {{
              t(
                'stats.nearGoal',
                { near: summary.nearGoal, total: summary.withGoal },
                summary.withGoal,
              )
            }}
          </li>
          <li v-if="summary.overGoal">{{ t('stats.overGoal', { count: summary.overGoal }) }}</li>
          <li v-if="summary.underGoal">
            {{ t('stats.underGoal', { count: summary.underGoal }) }}
          </li>
          <li v-if="summary.completed">
            {{
              t(
                'stats.completedDays',
                { count: summary.completed, total: summary.days },
                summary.completed,
              )
            }}
          </li>
        </ul>
      </section>

      <section
        v-if="summary.kinds.length"
        class="bg-card space-y-3 rounded-3xl border p-5"
        aria-labelledby="stats-kinds"
        data-testid="stats-kinds"
      >
        <h3 id="stats-kinds" class="font-medium">{{ t('stats.byKind') }}</h3>
        <div class="bg-muted flex h-3 overflow-hidden rounded-full" aria-hidden="true">
          <span
            v-for="item in summary.kinds"
            :key="item.kind"
            :class="KIND_COLORS[item.kind]"
            :style="{ width: share(item.share) }"
          />
        </div>
        <ul class="space-y-1.5 text-sm">
          <li v-for="item in summary.kinds" :key="item.kind" class="flex items-center gap-2">
            <span :class="cn('size-2.5 shrink-0 rounded-full', KIND_COLORS[item.kind])" />
            <span class="flex-1">{{
              item.kind === 'none' ? t('food.noMealKind') : t(`food.mealKinds.${item.kind}`)
            }}</span>
            <span class="text-muted-foreground tabular-nums"
              >{{ formatAmount(item.kcal, 0) }} {{ t('food.kcal') }} · {{ share(item.share) }}</span
            >
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
