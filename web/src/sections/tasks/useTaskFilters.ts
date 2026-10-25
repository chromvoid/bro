/** URL state only: search keeps the screen's existing input and debounce. */
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  activeTaskFilterCount,
  hasTaskNarrowing,
  readTaskFilters,
  resetTaskFiltersQuery,
  taskFilterKey,
  taskFiltersQuery,
  taskWholeHistoryQuery,
  type TaskFilterState,
  type TaskLayout,
} from './taskFilters'

export function useTaskFilters(layout: MaybeRefOrGetter<TaskLayout>) {
  const route = useRoute()
  const router = useRouter()
  const filters = computed(() => readTaskFilters(route.query))
  const count = computed(() => activeTaskFilterCount(filters.value))
  const narrowed = computed(() => hasTaskNarrowing(filters.value))
  const queryKey = computed(() => taskFilterKey(filters.value))

  function change(patch: Partial<TaskFilterState>) {
    return router.replace({ query: taskFiltersQuery(route.query, patch) })
  }

  function reset() {
    return router.replace({ query: resetTaskFiltersQuery(route.query) })
  }

  function wholeHistory() {
    return router.replace({ query: taskWholeHistoryQuery(route.query, toValue(layout)) })
  }

  return { filters, count, narrowed, queryKey, change, reset, wholeHistory }
}
