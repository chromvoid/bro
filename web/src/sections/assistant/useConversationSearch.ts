import { useInfiniteQuery, useQueryClient } from '@tanstack/vue-query'
import { refDebounced } from '@vueuse/core'
import { computed, ref, watch, type Ref } from 'vue'
import { api } from '@/core/client'
import { isIsoDay, shiftDay } from '@/core/dates'
import { SearchResults } from './model'

/** Dates are account calendar days; the API's upper boundary is exclusive. */
export function searchBounds(after: string, through: string) {
  if ((after && !isIsoDay(after)) || (through && !isIsoDay(through))) return null
  if (after && through && after > through) return null
  return { after: after || null, before: through ? shiftDay(through, 1) : null }
}

export function useConversationSearch(
  text: Ref<string>,
  after: Ref<string>,
  through: Ref<string>,
  zone: Ref<string>,
  open: Ref<boolean>,
) {
  const client = useQueryClient()
  const trimmed = computed(() => text.value.trim())
  const query = refDebounced(trimmed, 300)
  const bounds = computed(() => searchBounds(after.value, through.value))
  // A return to earlier criteria starts at page one, even if their query is still cached.
  const revision = ref(0)
  const key = computed(() => [
    'conversation',
    'search',
    query.value,
    after.value,
    through.value,
    zone.value,
    revision.value,
  ])
  watch(
    [trimmed, after, through, zone],
    () => {
      revision.value++
    },
    { flush: 'sync' },
  )
  watch(key, (_, previous) => void client.cancelQueries({ queryKey: previous, exact: true }), {
    flush: 'sync',
  })

  const ready = computed(
    () =>
      open.value && trimmed.value.length >= 2 && trimmed.value === query.value && !!bounds.value,
  )
  const results = useInfiniteQuery({
    queryKey: key,
    initialPageParam: null as string | null,
    queryFn: async ({ signal, pageParam }) => {
      const params = new URLSearchParams({ query: query.value })
      if (bounds.value?.after) params.set('after', bounds.value.after)
      if (bounds.value?.before) params.set('before', bounds.value.before)
      if (pageParam) params.set('cursor', pageParam)
      return SearchResults.parse(await api.get(`/v1/conversation/search?${params}`, signal))
    },
    getNextPageParam: (page) => page.cursor ?? undefined,
    enabled: ready,
    gcTime: 0,
  })
  const hits = computed(() =>
    ready.value ? (results.data.value?.pages.flatMap((page) => page.messages) ?? []) : [],
  )
  return { results, hits, ready, bounds }
}
