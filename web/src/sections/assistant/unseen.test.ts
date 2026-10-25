import { describe, expect, it } from 'vitest'
import { toExchange, type Exchange } from './model'
import { markSeen, unseenReplies } from './unseen'

function exchange(id: string, answered: boolean): Exchange {
  return toExchange({
    id,
    created_at: '2026-10-07T09:00:00Z',
    text: `Вопрос ${id}`,
    state: answered ? 'completed' : 'processing',
    turns: answered ? [{ id: `turn-${id}`, state: 'completed', result: { reply: 'Ответ' } }] : [],
  })
}

describe('answers that came while the reader was away', () => {
  it('counts answers completed after the reader left', () => {
    const before = [exchange('a', true), exchange('b', false)]
    const mark = markSeen(before)
    expect(unseenReplies(before, mark)).toBe(0)
    expect(unseenReplies([exchange('a', true), exchange('b', true)], mark)).toBe(1)
    expect(
      unseenReplies([exchange('a', true), exchange('b', true), exchange('c', true)], mark),
    ).toBe(2)
  })

  it('does not count earlier history loaded meanwhile', () => {
    const mark = markSeen([exchange('b', true), exchange('c', false)])
    const loaded = [exchange('a', true), exchange('b', true), exchange('c', true)]
    expect(unseenReplies(loaded, mark)).toBe(1)
  })
})
