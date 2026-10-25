import { QueryClient } from '@tanstack/vue-query'

export const queryClient = new QueryClient({
  defaultOptions: {
    // Server events invalidate data; refetching on every focus would duplicate that work.
    // A screen opened again within half an hour shows what it showed and refreshes behind it
    // instead of waiting for the network; longer would hold every screen visited in memory.
    queries: { refetchOnWindowFocus: false, staleTime: 30_000, gcTime: 30 * 60_000, retry: 1 },
    // Writes go through the submission journal and are never repeated automatically.
    mutations: { retry: false },
  },
})
