import { describe, expect, it } from 'vitest'
import type { Submission } from '@/core/journal'
import { controlReceipt, originalEnvelope } from './controls'
import { toExchange } from './model'

const receipt = {
  id: 'control',
  submission_key: 'key',
  message_id: 'message',
  generation: 'generation',
  intent_revision: 0,
  action: 'withdraw',
  state: 'settled',
  reason: null,
  admitted_at: '2026-10-05T12:00:00Z',
  settled_at: '2026-10-05T12:00:00Z',
  execution: { effects: 'none', completion: 'failed', safe_to_resubmit: true },
  message_body: {
    text: 'Synthetic speech',
    image_id: 'image',
    transcription_id: 'transcription',
    reply_to_message_id: 'reply',
  },
}
const entry: Submission = {
  key: 'key',
  endpoint: '/v1/conversation/controls',
  method: 'POST',
  lookup: null,
  found: 'accepted',
  body: JSON.stringify({ message_id: 'message', generation: 'generation', action: 'withdraw' }),
  createdAt: 0,
  state: 'accepted',
  response: receipt,
  failure: null,
  context: {},
}

describe('control authority', () => {
  it('keeps admission separate from settlement and refuses a mismatched receipt', () => {
    expect(controlReceipt(entry)?.state).toBe('settled')
    expect(
      controlReceipt({
        ...entry,
        response: { ...receipt, state: 'accepted', execution: null, settled_at: null },
      })?.execution,
    ).toBeNull()
    for (const field of ['submission_key', 'message_id', 'generation', 'action'])
      expect(controlReceipt({ ...entry, response: { ...receipt, [field]: 'other' } })).toBeNull()
  })

  it('preserves the source envelope for queued edit, including speech provenance', () => {
    const exchange = toExchange({
      id: 'message',
      created_at: receipt.admitted_at,
      state: 'queued',
      text: receipt.message_body.text,
      reply_to_message_id: 'reply',
      transcription_id: 'transcription',
      images: [{ id: 'image', url: '/image', width: 1, height: 1 }],
    })
    expect(originalEnvelope(exchange)).toEqual(receipt.message_body)
  })

  it('does not trust a malformed persisted request or crash the recovery screen', () => {
    for (const body of [
      '{',
      'null',
      '[]',
      '{}',
      JSON.stringify({ message_id: 'message', generation: 'generation', action: 'steer' }),
    ])
      expect(controlReceipt({ ...entry, body })).toBeNull()
  })

  it('allows restoration only after a proven withdrawal with no effects', () => {
    const base = {
      id: 'message',
      created_at: receipt.admitted_at,
      state: 'failed',
      error: 'user_withdrawn',
      control_action: 'withdraw' as const,
      control_state: 'settled' as const,
      control_execution: {
        effects: 'none' as const,
        completion: 'failed' as const,
        safe_to_resubmit: true,
      },
    }
    expect(toExchange(base).canRestore).toBe(true)
    expect(toExchange({ ...base, control_state: 'accepted' }).canRestore).toBe(false)
    expect(
      toExchange({ ...base, control_execution: { ...base.control_execution, effects: 'unknown' } })
        .canRestore,
    ).toBe(false)
    expect(
      toExchange({
        ...base,
        turns: [
          {
            id: 'turn',
            state: 'failed',
            result: {
              services: [{ service: 'timers', receipts: [{ operation_id: 'committed' }] }],
            },
          },
        ],
      }).canRestore,
    ).toBe(false)
  })
})
