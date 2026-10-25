/** The list, its unread count and the recipient's actions; one query shared by every screen. */
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed } from 'vue'
import { api } from '@/core/client'
import { useSession } from '@/stores/session'
import { NotificationList, type Notification } from './model'

export const NOTIFICATIONS_KEY = ['notifications', 'list'] as const

export function useNotifications() {
  const session = useSession()
  const queries = useQueryClient()
  // `notifications.changed` events invalidate the domain; the list itself is small (30 days).
  const list = useQuery({
    queryKey: NOTIFICATIONS_KEY,
    queryFn: async ({ signal }) =>
      NotificationList.parse(await api.get('/v1/notifications?limit=50', signal)),
    enabled: computed(() => session.me !== null),
  })
  const items = computed<Notification[]>(() => list.data.value?.notifications ?? [])
  const unread = computed(() => list.data.value?.unread ?? 0)

  async function refresh() {
    await queries.invalidateQueries({ queryKey: ['notifications'] })
  }
  /** The listed ones, or all of them. */
  async function read(ids?: string[]) {
    await api.post('/v1/notifications/read', ids ? { ids } : { all: true })
    await refresh()
  }
  async function snooze(id: string, until: { minutes: number } | { until: string }) {
    await api.post(`/v1/notifications/${encodeURIComponent(id)}/snooze`, until)
    await refresh()
  }
  return { list, items, unread, read, snooze }
}
