/** Query composables for resolved Personal data. */
import { useInfiniteQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { api } from '@/core/client'
import { isSensorMetric, SensorSample } from './sensors'

import { ResolvedPage, type ResolutionRange } from './resolutionContract'

export * from './resolutionContract'

export function useResolved(range: Ref<ResolutionRange>, enabled?: Ref<boolean>) {
  return useInfiniteQuery({
    enabled,
    queryKey: computed(() => ['personal', 'resolved', range.value]),
    initialPageParam: null as string | null,
    queryFn: async ({ signal, pageParam }) => {
      const query = new URLSearchParams({
        ...range.value,
        limit: '500',
        ...(pageParam ? { cursor: pageParam } : {}),
      })
      const page = ResolvedPage.parse(await api.get(`/v1/personal/resolved?${query}`, signal))
      if (isSensorMetric(range.value.metric))
        page.series = page.series.map((row) => SensorSample.parse(row))
      return page
    },
    getNextPageParam: (page) => page.next_cursor ?? undefined,
  })
}

/** Pages from different decisions must never be presented as a single resolved series. */
export function consistentPages(pages: ResolvedPage[]): boolean {
  return pages.every((page) => page.fingerprint === pages[0]?.fingerprint)
}

/**
 * Alternatives one source reported many times for the same reason, as one line with a count: a
 * ring sends a reading every few minutes, and nineteen identical lines said nothing more than one.
 */
export function groupFacts<
  T extends { source_id?: string | null; source_name?: string | null; reason?: string | null },
>(facts: T[]): Array<{ fact: T; count: number }> {
  const groups = new Map<string, { fact: T; count: number }>()
  for (const fact of facts) {
    const key = JSON.stringify([
      fact.source_id ?? null,
      fact.source_name ?? null,
      fact.reason ?? null,
    ])
    const group = groups.get(key)
    if (group) group.count += 1
    else groups.set(key, { fact, count: 1 })
  }
  return [...groups.values()]
}
