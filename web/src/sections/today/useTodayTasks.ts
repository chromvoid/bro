import { useQuery } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { api } from '@/core/client'
import { TaskPage } from '@/sections/tasks/api'

/**
 * The first open tasks of today, overdue ones included: the server decides both in the account's
 * time zone. «Сегодня» lists them, and the assistant screen suggests asking about them.
 */
export function useTodayTasks(enabled: MaybeRefOrGetter<boolean> = true) {
  return useQuery({
    queryKey: ['tasks', 'items', 'today', '', 'card'],
    queryFn: async ({ signal }) =>
      TaskPage.parse(await api.get('/v1/tasks/items?view=today&sort=created&limit=5', signal)),
    enabled: computed(() => toValue(enabled)),
  })
}
