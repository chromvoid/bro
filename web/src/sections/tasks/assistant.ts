import { z } from '@/core/zod'
const Grants = z.object({
  tools: z.array(z.string()).default([]),
  actions: z.array(z.string()).default([]),
  resources: z.array(z.string()).default([]),
  executors: z.array(z.string()).default([]),
})
export const Delegation = z.looseObject({
  id: z.string(),
  task_id: z.string(),
  user_id: z.string(),
  instruction: z.string(),
  mode: z.enum(['oneoff', 'monitor']),
  completion_mode: z.enum(['manual', 'auto']),
  status: z.string(),
  revision: z.number(),
  grants: Grants,
  interval_minutes: z.number().nullish(),
  next_run_at: z.string().nullish(),
  budget: z
    .looseObject({
      active_seconds: z.number().optional(),
      tool_calls: z.number().optional(),
      daily_runs: z.number().optional(),
    })
    .nullish(),
  completion_criteria: z
    .looseObject({ required_checks: z.array(z.string()).default([]) })
    .nullish(),
})
export type Delegation = z.infer<typeof Delegation>
export const AssistantSnapshot = z.object({
  delegation: Delegation.nullable(),
  runs: z
    .array(
      z.looseObject({
        id: z.string(),
        status: z.string(),
        created_at: z.string().nullish(),
        finished_at: z.string().nullish(),
        error: z.unknown().optional(),
        summary: z.string().nullish(),
      }),
    )
    .default([]),
  questions: z
    .array(
      z.looseObject({
        id: z.string(),
        text: z.string(),
        status: z.string(),
        revision: z.number(),
        audience_private: z.boolean().optional(),
        kind: z.enum(['question', 'proposal']).default('question'),
      }),
    )
    .default([]),
  results: z
    .array(
      z.looseObject({
        id: z.string(),
        body: z.string(),
        audience_private: z.boolean().optional(),
        status: z.string(),
        revision: z.number(),
        sources: z
          .array(z.looseObject({ title: z.string().optional(), url: z.string() }))
          .default([]),
        created_at: z.string().nullish(),
      }),
    )
    .default([]),
  can_manage: z.boolean().optional(),
})
const Choice = z.looseObject({
  id: z.string(),
  title: z.string().optional(),
  description: z.string().optional(),
})
export const AssistantCapabilities = z.looseObject({
  enabled: z.boolean().default(true),
  default_grants: Grants.optional(),
  tools: z.array(Choice).default([]),
  actions: z.array(Choice).default([]),
  resources: z.array(Choice).default([]),
  executors: z.array(Choice).default([]),
})
export function safeSource(url: string): string | null {
  try {
    const parsed = new URL(url)
    return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password
      ? parsed.href
      : null
  } catch {
    return null
  }
}
export function delegationIsActive(delegation: Delegation | null | undefined): boolean {
  return (
    !!delegation &&
    !['revoked', 'completed', 'done', 'cancelled', 'failed'].includes(delegation.status)
  )
}
