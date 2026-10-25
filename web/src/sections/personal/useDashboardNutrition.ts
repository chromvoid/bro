import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { api } from '@/core/client'
import { isIsoDay, shiftDay } from '@/core/dates'
import { Day } from '@/sections/food/api'
import { useSession } from '@/stores/session'
import {
  DashboardNutritionRange,
  rangeNutritionRow,
  selectedNutritionRow,
} from './dashboardNutrition'

export function useDashboardNutritionDay(day: Ref<string>, active: Ref<boolean>) {
  const session = useSession()
  return useQuery({
    queryKey: computed(() => ['nutrition', 'day', day.value]),
    enabled: computed(() => active.value && session.can('nutrition.read') && isIsoDay(day.value)),
    queryFn: async ({ signal }) =>
      Day.parse(await api.get(`/v1/nutrition/day?date=${encodeURIComponent(day.value)}`, signal)),
  })
}

export function useDashboardNutrition(day: Ref<string>, period: Ref<7 | 14>) {
  const session = useSession()
  const active = computed(() => session.can('nutrition.read'))
  const diary = useDashboardNutritionDay(day, active)
  const range = computed(() => ({
    start: shiftDay(day.value, -(period.value - 1)),
    end: day.value,
  }))
  const history = useQuery({
    queryKey: computed(() => ['nutrition', 'range', range.value.start, range.value.end]),
    enabled: computed(() => active.value && isIsoDay(day.value)),
    queryFn: async ({ signal }) =>
      DashboardNutritionRange.parse(
        await api.get(
          `/v1/nutrition/range?start=${range.value.start}&end=${range.value.end}`,
          signal,
        ),
      ),
  })
  const selected = computed(() =>
    diary.data.value ? selectedNutritionRow(diary.data.value) : null,
  )
  const rows = computed(() => {
    const current = selected.value
    return (history.data.value?.days ?? [])
      .filter((item) => item.date >= range.value.start && item.date <= range.value.end)
      .map((item) => (current && item.date === current.date ? current : rangeNutritionRow(item)))
      .sort((a, b) => a.date.localeCompare(b.date))
  })
  return { diary, history, selected, rows, range }
}
