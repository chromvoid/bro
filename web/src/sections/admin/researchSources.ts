import { z } from '@/core/zod'

/** The trusted source registry as GET /v1/research/sources sends it (server/plugins/research/store.py). */
export const ResearchSource = z.looseObject({
  id: z.string(),
  slug: z.string(),
  name: z.string(),
  topics: z.array(z.string()),
  domains: z.array(z.string()),
  entry_url: z.string().nullable(),
  role: z.string(),
  priority: z.number().int(),
  status: z.enum(['active', 'candidate', 'rejected', 'retired']),
  origin: z.string(),
  best_for: z.string(),
  limitations: z.string(),
  revision: z.number().int(),
})
export type ResearchSource = z.infer<typeof ResearchSource>

export const ResearchTopic = z.looseObject({
  slug: z.string(),
  label: z.string(),
  status: z.string(),
})

export const ResearchSettings = z.looseObject({
  unlisted_stats: z.boolean(),
  model_discovery: z.boolean(),
  revision: z.number().int(),
})
export type ResearchSettings = z.infer<typeof ResearchSettings>

export const ResearchRegistry = z.object({
  sources: z.array(ResearchSource),
  topics: z.array(ResearchTopic),
  settings: ResearchSettings,
})
export type ResearchRegistry = z.infer<typeof ResearchRegistry>

export const RESEARCH = {
  endpoint: '/v1/research/operations',
  lookupPrefix: '/v1/research/operations/by-key/',
  domain: 'research',
}
export const switches = ['unlisted_stats', 'model_discovery'] as const
