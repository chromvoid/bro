import { describe, expect, it } from 'vitest'
import { estimateRow, feedRows, rowDays, rowOf } from './feedRows'
import { toExchange, type Message } from './model'
import type { FeedItem } from './timeline'

const exchange = (id: string, extra: Partial<Message> = {}) =>
  toExchange({ id, created_at: '2026-10-06T09:00:00Z', text: 'Синтетический вопрос', ...extra })
const item = (id: string, extra: Partial<Message> = {}): FeedItem => ({
  kind: 'exchange',
  key: id,
  at: '2026-10-06T09:00:00Z',
  exchange: exchange(id, extra),
})

describe('feed rows', () => {
  it('puts a heading before each day and keys rows by what they show', () => {
    const rows = feedRows([
      { day: '2026-10-05', label: 'Вчера', items: [item('a')] },
      { day: '2026-10-06', label: 'Сегодня', items: [item('b'), item('c')] },
    ])
    expect(rows.map((row) => row.key)).toEqual(['day-2026-10-05', 'a', 'day-2026-10-06', 'b', 'c'])
    expect(rows[0]).toMatchObject({ kind: 'day', label: 'Вчера' })
  })

  it('gives every row the day of the heading above it', () => {
    const rows = feedRows([
      { day: '2026-10-05', label: 'Вчера', items: [item('a')] },
      { day: '2026-10-06', label: 'Сегодня', items: [item('b'), item('c')] },
    ])
    expect(rowDays(rows)).toEqual(['Вчера', 'Вчера', 'Сегодня', 'Сегодня', 'Сегодня'])
  })

  it('estimates taller rows for long text, photos and answers', () => {
    const [short] = feedRows([{ day: 'd', label: 'd', items: [item('a')] }]).slice(1)
    const [long] = feedRows([
      {
        day: 'd',
        label: 'd',
        items: [
          item('b', {
            text: 'Синтетический текст '.repeat(40),
            images: [{ id: 'p', width: 400, height: 300, url: '/p' }],
            turns: [{ id: 't', state: 'completed', result: { reply: 'Ответ '.repeat(80) } }],
          }),
        ],
      },
    ]).slice(1)
    expect(estimateRow(long)).toBeGreaterThan(estimateRow(short) + 400)
    expect(estimateRow({ kind: 'day', key: 'day-d', day: 'd', label: 'd' })).toBeLessThan(60)
  })

  it('finds the row of a message or of the reply opened from search', () => {
    const rows = feedRows([
      {
        day: 'd',
        label: 'd',
        items: [
          item('a'),
          item('b', { turns: [{ id: 't', reply_message_id: 'reply-b', state: 'completed' }] }),
        ],
      },
    ])
    expect(rowOf(rows, 'a')).toBe(1)
    expect(rowOf(rows, 'reply-b')).toBe(2)
    expect(rowOf(rows, 'missing')).toBe(-1)
  })
})
