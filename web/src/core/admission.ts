/** Conversation admission is a receipt, not proof that its domain actions completed. */
import { z } from './zod'

export const CONVERSATION_MESSAGES = '/v1/conversation/messages'
const Base = z.object({
  message_id: z.string().min(1).max(128),
  state: z.enum(['queued', 'routing', 'processing', 'completed', 'failed', 'clarification']),
})
export const Admission = Base.extend({
  submission_key: z.string().min(1).max(80),
  admitted_at: z.iso.datetime({ offset: true }),
  trace_id: z
    .string()
    .regex(/^[a-f0-9]{32}$/i)
    .nullable(),
})
export type Admission = z.infer<typeof Admission>

/** Old stored receipts remain readable. A partial new receipt is never treated as legacy. */
export function storedAdmission(value: unknown, key: string): z.infer<typeof Base> | null {
  const parsed = Admission.safeParse(value)
  if (parsed.success) return parsed.data.submission_key === key ? parsed.data : null
  if (
    value &&
    typeof value === 'object' &&
    ['submission_key', 'admitted_at'].some((field) => field in value)
  )
    return null
  const legacy = Base.safeParse(value)
  return legacy.success ? legacy.data : null
}

export class InvalidAdmission extends Error {
  constructor() {
    super('Invalid admission receipt')
  }
}

export function admission(value: unknown, key: string): Admission {
  const parsed = Admission.safeParse(value)
  if (!parsed.success || parsed.data.submission_key !== key) throw new InvalidAdmission()
  return parsed.data
}
