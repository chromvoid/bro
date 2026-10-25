import { describe, expect, it, vi } from 'vitest'
import {
  CLARIFICATIONS,
  clarificationReceipt,
  historyClarification,
  moveToRun,
  readHistoryClarification,
  type ClarificationReceipt,
} from './clarifications'

const message = crypto.randomUUID()
const generation = crypto.randomUUID()
const queued = crypto.randomUUID()
const body = { message_id: message, generation, queued_message_id: queued }
const TEXT = 'Нет, яблоко 200 г'
const receipt = (state: ClarificationReceipt['state'] = 'accepted'): ClarificationReceipt => ({
  id: crypto.randomUUID(),
  submission_key: 'key',
  message_id: message,
  generation,
  source_message_id: queued,
  intent_revision: 2,
  state,
  reason: null,
  question: null,
  admitted_at: '2026-10-05T09:00:00Z',
  delivered_at: null,
  applied_at: null,
})

describe('server authored clarification progress', () => {
  it('accepts only the receipt matching the exact key, request and execution generation', () => {
    const value = receipt()
    expect(clarificationReceipt(value, 'key', body)).toEqual(value)
    for (const wrong of [
      { ...value, submission_key: 'another-key' },
      { ...value, message_id: crypto.randomUUID() },
      { ...value, generation: crypto.randomUUID() },
      { ...value, source_revision: 2 },
    ])
      expect(() => clarificationReceipt(wrong, 'key', body)).toThrow()
  })

  it('moves a queued message by its identity and its own key, and trusts only its receipt', async () => {
    const value = receipt()
    const post = vi.fn(async () => ({ status: 202, data: value }))
    const beforeSend = () => undefined
    expect(
      await moveToRun({ post } as never, body, 'key', { expectedUser: 'user', beforeSend }),
    ).toEqual(value)
    expect(post).toHaveBeenCalledExactlyOnceWith(CLARIFICATIONS, body, {
      key: 'key',
      expectedUser: 'user',
      beforeSend,
    })
    post.mockResolvedValue({ status: 202, data: { ...value, source_message_id: message } })
    await expect(moveToRun({ post } as never, body, 'key')).rejects.toThrow('another source')
    post.mockResolvedValue({ status: 202, data: { ...value, submission_key: 'other' } })
    await expect(moveToRun({ post } as never, body, 'key')).rejects.toThrow('original request')
    await expect(
      moveToRun({ post } as never, { ...body, queued_message_id: 'not-a-message' }, 'key'),
    ).rejects.toThrow()
    expect(post).toHaveBeenCalledTimes(3)
  })

  it('treats a stopped admitted clarification as unapplied', () => {
    const value = {
      ...receipt('paused'),
      reason: 'budget_exhausted',
      delivered_at: '2026-10-05T09:00:01Z',
      question: 'Оставить яблоко и задачу?',
    }
    expect(clarificationReceipt(value, 'key', body)).toEqual(value)
    expect(() =>
      clarificationReceipt({ ...value, applied_at: '2026-10-05T09:00:02Z' }, 'key', body),
    ).toThrow()
  })

  it('rebuilds a saved clarification only on its exact immutable history source', () => {
    const value = receipt('delivered')
    const source = { id: value.source_message_id, text: TEXT, clarificationReceipt: value }
    expect(historyClarification(source)).toEqual({
      key: 'key',
      text: TEXT,
      target: { messageId: message, generation },
      state: 'delivered',
      receipt: value,
    })
    expect(historyClarification({ ...source, id: message })).toBeNull()
    expect(
      historyClarification({ ...source, clarificationReceipt: { state: 'applied' } }),
    ).toBeNull()
    expect(historyClarification({ id: message, text: TEXT })).toBeNull()
  })

  it('checks a history clarification by its original key without another submission', async () => {
    const value = receipt('accepted')
    const item = historyClarification({
      id: value.source_message_id,
      text: TEXT,
      clarificationReceipt: value,
    })!
    const applied = { ...value, state: 'applied', applied_at: '2026-10-05T09:00:02Z' }
    const get = vi.fn(async () => applied)
    expect(await readHistoryClarification({ get } as never, item)).toEqual(applied)
    expect(get).toHaveBeenCalledWith(`${CLARIFICATIONS}/by-key/key`, undefined)
    get.mockResolvedValue({ ...applied, source_message_id: crypto.randomUUID() })
    await expect(readHistoryClarification({ get } as never, item)).rejects.toThrow('source changed')
    get.mockResolvedValue({ ...applied, generation: crypto.randomUUID() })
    await expect(readHistoryClarification({ get } as never, item)).rejects.toThrow(
      'original request',
    )
  })
})
