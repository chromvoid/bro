import { useQuery } from '@tanstack/vue-query'
import { api } from '@/core/client'
import { z } from '@/core/zod'

export const Availability = z.object({
  state: z.enum(['configured', 'blocked', 'temporary']),
  reason: z.string().nullable(),
  action: z.enum(['configure_access', 'open_accounts', 'contact_admin']).nullable().optional(),
  retry_at: z.string().nullable(),
  checked_at: z.string(),
})
export type Availability = z.infer<typeof Availability>

export function useAvailability() {
  return useQuery({
    queryKey: ['assistant-availability'],
    queryFn: async ({ signal }) =>
      Availability.parse(await api.get('/v1/conversation/availability', signal)),
    refetchInterval: 15_000,
    refetchOnWindowFocus: 'always',
    staleTime: 5_000,
  })
}
