/** Live clarifications are immutable human messages; only a server receipt establishes progress. */
import type { Api, WriteOptions } from '@/core/http'
import { z } from '@/core/zod'

export const CLARIFICATIONS = '/v1/conversation/clarifications'
export const CLARIFICATION_LOOKUP = `${CLARIFICATIONS}/by-key/`
/** «Отправить сейчас»: a message waiting in the queue goes into the running request. */
export const MoveBody = z.object({
  message_id: z.uuid(),
  generation: z.uuid(),
  queued_message_id: z.uuid(),
})
export type MoveBody = z.infer<typeof MoveBody>
export const ClarificationReceipt = z
  .object({
    id: z.uuid(),
    submission_key: z.string().min(1).max(80),
    message_id: z.uuid(),
    generation: z.uuid(),
    source_message_id: z.uuid(),
    source_revision: z.literal(1).optional(),
    intent_revision: z.number().int().nonnegative(),
    state: z.enum(['accepted', 'delivered', 'applied', 'queued', 'rejected', 'paused']),
    reason: z.string().nullable(),
    question: z.string().nullable(),
    admitted_at: z.iso.datetime({ offset: true }),
    delivered_at: z.iso.datetime({ offset: true }).nullable(),
    applied_at: z.iso.datetime({ offset: true }).nullable(),
  })
  .refine((receipt) => receipt.state !== 'paused' || receipt.applied_at === null, {
    message: 'A paused clarification cannot claim application',
  })
export type ClarificationReceipt = z.infer<typeof ClarificationReceipt>
/** A clarification as history shows it, on its own immutable source message. */
export interface ClarificationItem {
  key: string
  text: string
  /** The request it clarifies. */
  target: { messageId: string; generation: string }
  state: ClarificationReceipt['state']
  receipt: ClarificationReceipt
}

export function clarificationReceipt(
  value: unknown,
  key: string,
  body: Pick<MoveBody, 'message_id' | 'generation'>,
): ClarificationReceipt {
  const receipt = ClarificationReceipt.parse(value)
  if (
    receipt.submission_key !== key ||
    receipt.message_id !== body.message_id ||
    receipt.generation !== body.generation
  )
    throw new Error('Clarification receipt does not match its original request')
  return receipt
}

/**
 * Moves a queued message into the running request. The server takes it at most once, while the
 * request accepts clarifications; otherwise it refuses and the message stays next in the queue.
 */
export async function moveToRun(
  api: Pick<Api, 'post'>,
  body: MoveBody,
  key: string,
  options: Omit<WriteOptions, 'key'> = {},
): Promise<ClarificationReceipt> {
  const request = MoveBody.parse(body)
  const reply = await api.post(CLARIFICATIONS, request, { ...options, key })
  const receipt = clarificationReceipt(reply.data, key, request)
  if (receipt.source_message_id !== request.queued_message_id)
    throw new Error('Clarification receipt names another source')
  return receipt
}

/** History can show a clarification still in progress; its receipt is read by the original key. */
export async function readHistoryClarification(
  api: Pick<Api, 'get'>,
  item: ClarificationItem,
  signal?: AbortSignal,
): Promise<ClarificationReceipt> {
  const response = await api.get(CLARIFICATION_LOOKUP + encodeURIComponent(item.key), signal)
  signal?.throwIfAborted()
  const receipt = clarificationReceipt(response, item.key, {
    message_id: item.target.messageId,
    generation: item.target.generation,
  })
  if (receipt.source_message_id !== item.receipt.source_message_id)
    throw new Error('Clarification source changed after admission')
  return receipt
}

/** A history row owns a clarification only when it is the receipt's immutable source. */
export function historyClarification(source: {
  id: string
  text: string
  clarificationReceipt?: unknown
}): ClarificationItem | null {
  const result = ClarificationReceipt.safeParse(source.clarificationReceipt)
  if (!result.success || result.data.source_message_id !== source.id) return null
  const receipt = result.data
  return {
    key: receipt.submission_key,
    text: source.text,
    target: { messageId: receipt.message_id, generation: receipt.generation },
    state: receipt.state,
    receipt,
  }
}
