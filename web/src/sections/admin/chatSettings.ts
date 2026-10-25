import { z } from '@/core/zod'

const Choice = z.object({ model: z.string(), reasoning_effort: z.string() })
const Choices = z.object({ codex: Choice })
const Model = z.object({
  id: z.string(),
  name: z.string(),
  reasoning_efforts: z.array(z.string()),
})
export const ChatSettings = z.object({
  revision: z.number().int().nonnegative(),
  providers: Choices,
  defaults: Choices,
  // Codex calls beside the chat that installed plugins declare (research, quick search, food).
  purposes: z.record(z.string(), Choice).default({}),
  purpose_defaults: z.record(z.string(), Choice).default({}),
  catalog: z.object({ codex: z.array(Model) }),
})
export type ChatChoices = z.infer<typeof Choices>
export type ModelChoice = z.infer<typeof Choice>
export const providers = ['codex'] as const
/** Purposes this client can name, in display order; others stay saved but are not shown. */
export const purposes = ['research', 'quick_search', 'food_search'] as const
