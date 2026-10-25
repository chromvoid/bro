import { describe, expect, it } from 'vitest'
import { History, toExchange, type Message } from './model'

const base: Message = {
  id: 'm1',
  created_at: '2026-09-25T09:10:11+00:00',
  state: 'completed',
  text: 'Съел йогурт 150 г',
  images: [],
  turns: [],
}

describe('toExchange', () => {
  it('requires explicit server permission instead of inferring clarification ownership from a generation', () => {
    const message = {
      ...base,
      state: 'processing',
      run_generation: crypto.randomUUID(),
      intent_version: 2,
      intent_revision: 0,
    }
    expect(toExchange(message).canClarify).toBe(false)
    expect(toExchange({ ...message, can_clarify: true }).canClarify).toBe(true)
    const invalid = History.parse({ messages: [{ ...message, can_clarify: 'true' }], before: null })
      .messages[0]!
    expect(toExchange(invalid).canClarify).toBe(false)
  })

  it('projects immutable clarification history without a job and rejects a receipt on another source', () => {
    const source = crypto.randomUUID()
    const value = {
      id: crypto.randomUUID(),
      submission_key: 'key',
      message_id: crypto.randomUUID(),
      generation: crypto.randomUUID(),
      source_message_id: source,
      intent_revision: 2,
      state: 'paused',
      reason: 'budget_exhausted',
      question: null,
      admitted_at: '2026-10-05T09:00:00Z',
      delivered_at: null,
      applied_at: null,
    }
    const message = History.parse({
      messages: [{ ...base, id: source, state: null, clarification_receipt: value }],
      before: null,
    }).messages[0]!
    const saved = toExchange(message)
    expect(saved.clarification).toMatchObject({
      state: 'paused',
      text: base.text,
      target: { messageId: value.message_id, generation: value.generation },
    })
    expect(saved.status).toBe('done')
    expect(saved.canClarify).toBe(false)
    expect(toExchange({ ...message, id: value.message_id }).clarification).toBeNull()
    const malformed = History.parse({
      messages: [{ ...message, clarification_receipt: { state: 'applied' } }],
      before: null,
    }).messages[0]!
    expect(toExchange(malformed).clarification).toBeNull()
    expect(toExchange(malformed).text).toBe(base.text)
  })

  it('describes a reply by its investigation when a quick lookup before it found nothing', () => {
    const services = [
      {
        service: 'research',
        research_status: 'insufficient_evidence',
        limitations: ['Нет доступа'],
      },
      { service: 'research', research_status: 'ok', limitations: [] },
      { service: 'research', research_status: 'rate_limited', limitations: [] },
    ]
    const reply = (list: typeof services) =>
      toExchange({ ...base, turns: [{ id: 't', state: 'completed', result: { services: list } }] })
        .replies[0]!
    expect(reply(services)).toMatchObject({ researchStatus: 'ok', limitations: [] })
    expect(reply(services.slice(0, 1))).toMatchObject({
      researchStatus: 'insufficient_evidence',
      limitations: ['Нет доступа'],
    })
    expect(
      reply([services[0]!, { ...services[0]!, research_status: 'not_found', limitations: [] }]),
    ).toMatchObject({ researchStatus: 'insufficient_evidence' })
  })

  it.each([
    ['none', true, true],
    ['none', false, false],
    ['confirmed', true, false],
    ['unknown', true, false],
  ] as const)(
    'requires server proof before restoring a failed request: %s, safe=%s',
    (effects, safe, allowed) => {
      const exchange = toExchange({
        ...base,
        state: 'failed',
        turns: [
          {
            id: 'failure',
            state: 'failed',
            result: {
              error: 'model_auth_required',
              execution: { effects, completion: 'failed', safe_to_resubmit: safe },
            },
          },
        ],
      })
      expect(exchange.error).toBe('model_auth_required')
      expect(exchange.canRestore).toBe(allowed)
      expect(exchange.failureKind).toBe(
        effects === 'confirmed' ? 'response' : effects === 'unknown' ? 'unknown' : 'request',
      )
      expect(exchange.replies[0]?.committed).toBe(effects === 'confirmed')
    },
  )

  it('keeps receipt facts and unknown outcomes stronger than a contradictory resubmit flag', () => {
    const result = {
      execution: {
        effects: 'none' as const,
        completion: 'failed' as const,
        safe_to_resubmit: true,
      },
      services: [{ service: 'nutrition', receipts: [{ operation_id: 'written' }] }],
    }
    const committed = toExchange({
      ...base,
      state: 'failed',
      turns: [{ id: 't', state: 'failed', result }],
    })
    expect(committed.canRestore).toBe(false)
    expect(committed.failureKind).toBe('response')
    const unknown = toExchange({
      ...base,
      state: 'failed',
      turns: [
        { id: 't', state: 'failed', result: { ...result, services: [], error: 'outcome_pending' } },
      ],
    })
    expect(unknown.canRestore).toBe(false)
    expect(unknown.failureKind).toBe('unknown')
    expect(toExchange({ ...base, state: 'failed', error: 'model_deadline' }).canRestore).toBe(false)
  })

  it.each(['legacy', 'receipt', 'effect', 'unknown', 'confirmed'])(
    'requires every turn to be safe, even when another turn is %s',
    (otherKind) => {
      const safe = {
        effects: 'none' as const,
        completion: 'failed' as const,
        safe_to_resubmit: true,
      }
      const exchange = toExchange({
        ...base,
        state: 'failed',
        turns: [
          { id: 'failed', state: 'failed', result: { execution: safe } },
          {
            id: 'other',
            state: 'completed',
            result: {
              ...(otherKind === 'legacy'
                ? {}
                : {
                    execution: {
                      ...safe,
                      effects: otherKind === 'confirmed' ? ('confirmed' as const) : safe.effects,
                    },
                  }),
              services: [
                {
                  service: 'nutrition',
                  ...(otherKind === 'receipt' ? { receipts: [{ operation_id: 'written' }] } : {}),
                  ...(otherKind === 'unknown' ? { status: 'unknown' } : {}),
                  ...(otherKind === 'effect'
                    ? {
                        effects: [
                          {
                            operation_ref: 'written',
                            kind: 'nutrition.entry.add',
                            entities: [{ id: 'food', kind: 'nutrition_entry', revision: 1 }],
                          },
                        ],
                      }
                    : {}),
                },
              ],
            },
          },
        ],
      })
      expect(exchange.canRestore).toBe(false)
      if (otherKind === 'unknown') expect(exchange.failureKind).toBe('unknown')
      if (['receipt', 'effect', 'confirmed'].includes(otherKind))
        expect(exchange.failureKind).toBe('response')
    },
  )

  it('allows an interrupted request only after the server has proven a safe unchanged outcome', () => {
    const interrupted = {
      ...base,
      state: 'failed',
      error: 'harness_interrupted',
      turns: [{ id: 'turn', state: 'failed', result: { error: 'harness_interrupted' } }],
    }
    expect(toExchange(interrupted).canRestore).toBe(false)
    expect(
      toExchange({
        ...interrupted,
        turns: [
          {
            ...interrupted.turns[0]!,
            result: {
              error: 'harness_interrupted',
              execution: { effects: 'none', completion: 'failed', safe_to_resubmit: true },
            },
          },
        ],
      }).canRestore,
    ).toBe(true)
  })

  it('shows what was recorded next to the reply', () => {
    const exchange = toExchange({
      ...base,
      turns: [
        {
          id: 't1',
          reply_message_id: 'r1',
          state: 'completed',
          result: {
            reply: 'Записал.',
            services: [
              { service: 'nutrition', status: 'completed', receipt_text: 'Йогурт 150 г — 90 ккал' },
              { service: 'tasks', status: 'rejected', message: 'Список не найден' },
            ],
          },
        },
      ],
    })
    expect(exchange.status).toBe('done')
    expect(exchange.replies[0]).toMatchObject({
      messageId: 'r1',
      text: 'Записал.',
      receipts: ['Йогурт 150 г — 90 ккал'],
      refusals: ['Список не найден'],
      confirmationPending: false,
      unknownOutcome: false,
    })
  })

  it('separates waiting, questions, confirmations and unknown outcomes', () => {
    expect(toExchange({ ...base, state: 'processing' }).status).toBe('pending')
    expect(toExchange({ ...base, state: 'failed', error: 'model_unavailable' })).toMatchObject({
      status: 'failed',
      error: 'model_unavailable',
    })
    const question = toExchange({
      ...base,
      turns: [
        { id: 't', state: 'completed', result: { reply: 'Уточню.', question: 'Сколько граммов?' } },
      ],
    })
    expect(question.status).toBe('clarification')
    expect(question.replies[0]!.question).toBe('Сколько граммов?')
    // A queued message moved into the running request is done as a message of its own.
    expect(toExchange({ ...base, state: 'clarification' })).toMatchObject({
      status: 'done',
      queued: false,
    })
    const confirm = toExchange({
      ...base,
      turns: [
        {
          id: 't',
          state: 'completed',
          result: { reply: 'Установить цель?', confirmation_pending: true },
        },
      ],
    })
    expect(confirm.replies[0]!.confirmationPending).toBe(true)
    const unknown = toExchange({
      ...base,
      turns: [{ id: 't', state: 'resolving', result: { reply: '', error: 'outcome_pending' } }],
    })
    expect(unknown.replies[0]!.unknownOutcome).toBe(true)
  })

  it('keeps effect facts separate from failed prose and never guesses no changes', () => {
    const failed = (execution?: {
      effects: 'none' | 'confirmed' | 'unknown'
      completion: 'failed'
      safe_to_resubmit: boolean
    }) =>
      toExchange({
        ...base,
        state: 'failed',
        error: 'model_rate_limited',
        turns: [
          {
            id: 't',
            state: 'failed',
            result: {
              error: 'model_rate_limited',
              question: 'Не удалось завершить ответ. Подтверждённые результаты показаны ниже.',
              execution,
            },
          },
        ],
      }).replies[0]!
    expect(failed().execution).toBeNull()
    expect(failed().question).toBeNull()
    expect(
      failed({ effects: 'unknown', completion: 'failed', safe_to_resubmit: false }).unknownOutcome,
    ).toBe(true)
    expect(
      failed({ effects: 'confirmed', completion: 'failed', safe_to_resubmit: false }).execution
        ?.safe_to_resubmit,
    ).toBe(false)
    expect(
      failed({ effects: 'none', completion: 'failed', safe_to_resubmit: true }).execution?.effects,
    ).toBe('none')
  })

  it('reads a history page and ignores unknown result fields', () => {
    const page = History.parse({
      messages: [
        {
          ...base,
          routing: { harness: 'pi' },
          turns: [{ id: 't', state: 'completed', result: { future: 1 } }],
        },
      ],
      before: null,
    })
    expect(toExchange(page.messages[0]!).replies[0]!.text).toBe('')
  })

  it('carries the photo preview and keeps the stored photo for a server without one', () => {
    const url = '/v1/conversation/images/photo'
    const page = History.parse({
      messages: [
        {
          ...base,
          images: [
            { id: 'photo', width: 1600, height: 1200, url, preview_url: `${url}/preview?v=1` },
          ],
        },
        { ...base, id: 'm2', images: [{ id: 'old', width: 600, height: 400, url: `${url}-old` }] },
      ],
      before: null,
    })
    expect(toExchange(page.messages[0]!).images).toEqual([
      { id: 'photo', width: 1600, height: 1200, url, previewUrl: `${url}/preview?v=1` },
    ])
    expect(toExchange(page.messages[1]!).images).toEqual([
      { id: 'old', width: 600, height: 400, url: `${url}-old`, previewUrl: null },
    ])
  })

  it('keeps only cited links when a plugin result names other things sources', () => {
    const citation = { url: 'https://example.org/synthetic', title: 'Synthetic page' }
    const page = History.parse({
      messages: [
        {
          ...base,
          turns: [
            {
              id: 't',
              state: 'completed',
              result: {
                services: [
                  // The personal section lists its data sources under the same name.
                  {
                    service: 'personal',
                    status: 'completed',
                    sources: [{ id: 'source', name: 'Synthetic scale', kinds: ['weight'] }],
                  },
                  { service: 'research', research_status: 'ok', sources: [citation] },
                  { service: 'synthetic', sources: { count: 1 } },
                ],
              },
            },
          ],
        },
      ],
      before: null,
    })
    expect(toExchange(page.messages[0]!).replies[0]!.sources).toEqual([citation])
  })

  it('preserves receipt identities and separates a failed answer from committed changes', () => {
    const receipt = {
      operation_id: 'op',
      kind: 'entry.add_many',
      applied_entries: [{ entry_id: 'food' }],
    }
    const effect = {
      operation_ref: 'op',
      kind: 'entry.add_many',
      entities: [{ id: 'food', kind: 'entry', revision: 2, label: 'Йогурт', date: '2026-10-01' }],
    }
    const page = History.parse({
      messages: [
        {
          ...base,
          state: 'failed',
          error: 'model_unavailable',
          turns: [
            {
              id: 'turn',
              state: 'failed',
              result: {
                error: 'model_unavailable',
                question: 'Проверьте результат',
                services: [
                  {
                    service: 'nutrition',
                    status: 'completed',
                    operation_key: 'key',
                    receipts: [receipt],
                    effects: [effect],
                  },
                ],
              },
            },
          ],
        },
      ],
      before: null,
    })
    const exchange = toExchange(page.messages[0]!)
    expect(exchange.failureKind).toBe('response')
    expect(exchange.canRestore).toBe(false)
    expect(exchange.replies[0]).toMatchObject({
      committed: true,
      question: null,
      unknownOutcome: false,
      recorded: [{ operationKey: 'key', receipts: [receipt], effects: [effect] }],
    })
  })

  it('does not offer a fresh replay for unknown or interrupted operations', () => {
    expect(toExchange({ ...base, state: 'failed', error: 'harness_interrupted' }).canRestore).toBe(
      false,
    )
    expect(toExchange({ ...base, state: 'failed', error: 'outcome_pending' }).canRestore).toBe(
      false,
    )
    const unknown = toExchange({
      ...base,
      state: 'failed',
      turns: [
        {
          id: 'turn',
          state: 'failed',
          result: {
            services: [{ status: 'unknown', service: 'nutrition' }],
            error: 'outcome_pending',
          },
        },
      ],
    })
    expect(unknown.failureKind).toBe('unknown')
    expect(unknown.canRestore).toBe(false)
    const interrupted = toExchange({
      ...base,
      state: 'failed',
      turns: [{ id: 'turn', state: 'failed', result: { error: 'harness_interrupted' } }],
    })
    expect(interrupted.canRestore).toBe(false)
  })

  it.each(['resolved', 'cancelled', 'superseded', 'expired'] as const)(
    'retires %s proposal controls from old history',
    (status) => {
      const exchange = toExchange({
        ...base,
        turns: [
          {
            id: 'turn',
            state: 'completed',
            result: {
              question: 'Подтвердить?',
              confirmation_pending: true,
              proposal_status: status,
            },
          },
        ],
      })
      expect(exchange.replies[0]).toMatchObject({
        question: null,
        confirmationPending: false,
        proposalStatus: status,
      })
      expect(exchange.status).toBe('done')
    },
  )

  it('keeps an unrelated clarification when an older proposal is resolved', () => {
    const exchange = toExchange({
      ...base,
      turns: [
        {
          id: 'turn',
          state: 'completed',
          result: {
            question: 'Как назвать задачу?',
            confirmation_pending: false,
            proposal_status: 'resolved',
          },
        },
      ],
    })
    expect(exchange.replies[0]?.question).toBe('Как назвать задачу?')
    expect(exchange.replies[0]?.confirmationPending).toBe(false)
  })
})
