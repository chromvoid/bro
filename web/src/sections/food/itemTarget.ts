/** Current canonical diary target, resolved by identity rather than a historical date or label. */
import { useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { api } from '@/core/client'
import { useSession } from '@/stores/session'
import { ItemTarget, isDish, type Day } from './api'

export function useFoodItemTarget(kind: Ref<string>, id: Ref<string>) {
  const session = useSession()
  return useQuery({
    queryKey: computed(() => ['nutrition', 'target', session.me?.id, kind.value, id.value]),
    enabled: computed(
      () => !!id.value && ['entry', 'dish'].includes(kind.value) && session.can('nutrition.read'),
    ),
    queryFn: async ({ signal }) =>
      ItemTarget.parse(
        await api.get(
          `/v1/nutrition/items/${encodeURIComponent(kind.value)}/${encodeURIComponent(id.value)}`,
          signal,
        ),
      ),
  })
}

export function targetItem(
  target: { kind: string; id: string; date: string; status: string },
  day: Day,
) {
  if (target.status !== 'active' || target.date !== day.date) return null
  return (
    day.items.find(
      (item) => item.id === target.id && (isDish(item) ? 'dish' : 'entry') === target.kind,
    ) ?? null
  )
}
