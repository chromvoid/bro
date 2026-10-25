/** Durable control receipts are separate from message admissions and effect receipts. */
import type { Submission } from '@/core/journal'
import { z } from '@/core/zod'
import type { Exchange } from './model'
import { Execution } from './model'

export const CONTROL_ENDPOINT = '/v1/conversation/controls'
export const CONTROL_LOOKUP = '/v1/conversation/controls/by-key/'
export const ControlReceipt = z.object({
  id: z.string(),
  submission_key: z.string(),
  message_id: z.string(),
  generation: z.string(),
  intent_revision: z.number().int().nonnegative(),
  action: z.enum(['stop', 'withdraw']),
  state: z.enum(['accepted', 'settled', 'rejected']),
  reason: z.string().nullable(),
  admitted_at: z.string(),
  settled_at: z.string().nullable(),
  execution: Execution.nullable(),
  message_body: z
    .object({
      text: z.string(),
      reply_to_message_id: z.string().optional(),
      transcription_id: z.string().optional(),
      image_id: z.string().optional(),
    })
    .optional(),
})
export type ControlReceipt = z.infer<typeof ControlReceipt>
const ControlRequest = z.strictObject({
  message_id: z.string(),
  generation: z.string(),
  action: z.enum(['stop', 'withdraw']),
})

/** The exchange a control entry names, read from its own request body. */
export function controlTarget(entry: Submission): string | null {
  try {
    return ControlRequest.parse(JSON.parse(entry.body)).message_id
  } catch {
    return null
  }
}

export function controlReceipt(entry: Submission): ControlReceipt | null {
  const parsed = ControlReceipt.safeParse(entry.response)
  if (!parsed.success || parsed.data.submission_key !== entry.key) return null
  let input: z.infer<typeof ControlRequest>
  try {
    input = ControlRequest.parse(JSON.parse(entry.body))
  } catch {
    return null
  }
  return parsed.data.message_id === input.message_id &&
    parsed.data.generation === input.generation &&
    parsed.data.action === input.action
    ? parsed.data
    : null
}

export function originalEnvelope(exchange: Exchange) {
  return {
    text: exchange.text,
    ...(exchange.replyTo ? { reply_to_message_id: exchange.replyTo } : {}),
    ...(exchange.transcriptionId ? { transcription_id: exchange.transcriptionId } : {}),
    ...(exchange.images[0] ? { image_id: exchange.images[0].id } : {}),
  }
}
