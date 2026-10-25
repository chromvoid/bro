<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { Check } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { api } from '@/core/client'
import { fullDayLabel, shiftDay } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { cn } from '@/lib/utils'
import { Week, bar, weekStart } from './week'

/** The week around the shown day: calories per day against the goal; a tap opens that day. */
const props = defineProps<{ day: string; today: string }>()
const emit = defineEmits<{ go: [day: string] }>()
const { t } = useI18n()
const start = computed(() => weekStart(props.day))
const week = useQuery({
  queryKey: computed(() => ['nutrition', 'days', start.value]),
  queryFn: async ({ signal }) =>
    Week.parse(
      await api.get(
        `/v1/nutrition/days?start=${start.value}&end=${shiftDay(start.value, 6)}`,
        signal,
      ),
    ),
})
const weekday = new Intl.DateTimeFormat('ru-RU', { weekday: 'short', timeZone: 'UTC' })
const days = computed(() => {
  const known = week.data.value?.days ?? []
  const largest = Math.max(0, ...known.map((item) => Number(item.energy_kcal)))
  return Array.from({ length: 7 }, (_, index) => {
    const date = shiftDay(start.value, index)
    const data = known.find((item) => item.date === date)
    const kcal = data ? Number(data.energy_kcal) : 0
    const shown = data ? bar(data, largest) : { share: 0, over: false, goal: null }
    const label = !kcal
      ? t('food.weekEmpty', { day: fullDayLabel(date) })
      : data?.target
        ? t(shown.over ? 'food.weekOver' : 'food.weekOf', {
            day: fullDayLabel(date),
            kcal: formatAmount(kcal, 0),
            target: formatAmount(data.target, 0),
          })
        : t('food.weekKcal', { day: fullDayLabel(date), kcal: formatAmount(kcal, 0) })
    return {
      date,
      name: weekday.format(new Date(`${date}T00:00:00Z`)),
      number: Number(date.slice(8)),
      label,
      completed: !!data?.completed,
      ...shown,
    }
  })
})
// The legend names only what this week shows: an over day, a closed day.
const legend = computed(() => ({
  goal: (week.data.value?.days ?? []).some((item) => item.target),
  over: days.value.some((item) => item.over),
  closed: days.value.some((item) => item.completed),
}))
</script>

<template>
  <div class="space-y-1.5">
    <nav class="grid grid-cols-7 gap-1" :aria-label="t('food.week')" data-testid="week-strip">
      <button
        v-for="item in days"
        :key="item.date"
        type="button"
        :disabled="item.date > today"
        :aria-label="item.label"
        :aria-pressed="item.date === day"
        :aria-current="item.date === today ? 'date' : undefined"
        :class="
          cn(
            'group flex min-h-11 flex-col items-center gap-1 rounded-xl py-1.5 transition-colors',
            item.date === day ? 'bg-accent text-accent-foreground' : 'hover:bg-accent/50',
          )
        "
        @click="emit('go', item.date)"
      >
        <span class="text-muted-foreground text-xs">{{ item.name }}</span>
        <span class="bg-border relative h-10 w-2.5 overflow-hidden rounded-full" aria-hidden="true">
          <span
            :class="
              cn(
                'absolute inset-x-0 bottom-0 rounded-full',
                item.over ? 'bg-warning-fill' : 'bg-primary',
              )
            "
            :style="{ height: `${item.share}%` }"
          />
          <!-- The goal is a notch in the page colour: a day past it rises above the notch. -->
          <span
            v-if="item.goal !== null"
            :class="
              cn('absolute inset-x-0 h-0.5', item.date === day ? 'bg-accent' : 'bg-background')
            "
            :style="{ bottom: `${item.goal}%` }"
            data-testid="week-goal"
          />
        </span>
        <span
          :class="
            cn(
              'group-disabled:text-muted-foreground text-sm tabular-nums',
              item.date === today ? 'font-semibold' : 'font-medium',
            )
          "
          >{{ item.number }}</span
        >
        <Check
          :class="cn('text-success size-3', !item.completed && 'invisible')"
          aria-hidden="true"
        />
      </button>
    </nav>
    <ul
      v-if="legend.over || legend.closed"
      class="text-muted-foreground flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs"
      aria-hidden="true"
      data-testid="week-legend"
    >
      <li v-if="legend.goal" class="flex items-center gap-1.5">
        <span class="bg-primary size-2.5 rounded-full" />{{ t('food.weekLegend.within') }}
      </li>
      <li v-if="legend.over" class="flex items-center gap-1.5">
        <span class="bg-warning-fill size-2.5 rounded-full" />{{ t('food.weekLegend.over') }}
      </li>
      <li v-if="legend.closed" class="flex items-center gap-1.5">
        <Check class="text-success size-3" />{{ t('food.weekLegend.closed') }}
      </li>
    </ul>
  </div>
</template>
