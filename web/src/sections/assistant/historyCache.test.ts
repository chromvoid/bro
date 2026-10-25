import { describe, expect, it } from 'vitest'
import {
  applyMessages,
  indexHistory,
  mergeHead,
  order,
  rechunk,
  shareMessages,
  type HistoryData,
} from './historyCache'
import type { History, Message } from './model'

/** Synthetic message n, created n minutes after a fixed start. */
function message(n: number, extra: Partial<Message> = {}): Message {
  const minute = String(n % 60).padStart(2, '0')
  const hour = String(8 + Math.floor(n / 60)).padStart(2, '0')
  return {
    id: `m${String(n).padStart(3, '0')}`,
    created_at: `2026-10-06T${hour}:${minute}:00.000000+00:00`,
    text: `Synthetic ${n}`,
    state: 'completed',
    ...extra,
  } as Message
}

const range = (from: number, to: number) =>
  Array.from({ length: to - from + 1 }, (_, i) => message(from + i))
const ids = (data: HistoryData) =>
  data.pages.map((page) => page.messages.map((item) => Number(item.id.slice(1))))

/** Two loaded pages: 11–20 (newest, cursor m011) and 1–10 (cursor m001, more before). */
function loaded(more = true): HistoryData {
  return {
    pages: [
      { messages: range(11, 20), before: 'm011' },
      { messages: range(1, 10), before: more ? 'm001' : null },
    ],
    pageParams: [null, 'm011'],
  }
}

describe('history order', () => {
  it('follows created_at with microseconds, then id', () => {
    const a = { ...message(1), created_at: '2026-10-06T08:00:00.000100+00:00' }
    const b = { ...message(1), id: 'm000', created_at: '2026-10-06T08:00:00.000200+00:00' }
    expect(order(a, b)).toBeLessThan(0)
    expect(order(message(1), { ...message(1), id: 'm002' })).toBeLessThan(0)
  })
})

describe('applyMessages', () => {
  it('replaces a changed exchange in place and keeps the others identical', () => {
    const data = loaded()
    const changed = message(5, { state: 'failed' })
    const next = applyMessages(data, [changed], [])
    expect(next.pages[1]!.messages[4]).toBe(changed)
    expect(next.pages[0]).toBe(data.pages[0])
    expect(next.pages[1]!.messages[3]).toBe(data.pages[1]!.messages[3])
  })

  it('adds a new message to the newest page only at the head', () => {
    expect(ids(applyMessages(loaded(), [message(21)], [])).at(0)).toEqual(
      range(11, 21).map((_, i) => 11 + i),
    )
    expect(ids(applyMessages(loaded(), [message(21)], [], false))).toEqual(ids(loaded()))
  })

  it('places a message inside the loaded range and leaves an older one for "earlier"', () => {
    const between = { ...message(10), id: 'm010b', created_at: '2026-10-06T08:10:30.000000+00:00' }
    const next = applyMessages(loaded(), [between], [])
    expect(next.pages[1]!.messages.at(-1)!.id).toBe('m010b')
    const older = { ...message(0), created_at: '2026-10-06T07:00:00.000000+00:00' }
    expect(ids(applyMessages(loaded(), [older], []))).toEqual(ids(loaded()))
    // When the oldest page is the start of the conversation, nothing older exists to wait for.
    expect(ids(applyMessages(loaded(false), [older], []))[1]![0]).toBe(0)
  })

  it('removes a missing message and moves the cursor off it', () => {
    const next = applyMessages(loaded(), [], ['m001', 'm015'])
    expect(ids(next)[0]).not.toContain(15)
    expect(next.pages[1]!.messages[0]!.id).toBe('m002')
    expect(next.pages[1]!.before).toBe('m002')
  })
})

describe('mergeHead', () => {
  it('appends new messages after the loaded ones and keeps older pages', () => {
    const head: History = { messages: range(13, 22), before: 'm013' }
    const next = mergeHead(loaded(), head)
    expect(ids(next)).toEqual([
      range(11, 22).map((_, i) => 11 + i),
      range(1, 10).map((_, i) => 1 + i),
    ])
    expect(next.pages[0]!.before).toBe('m011')
    expect(next.pageParams).toEqual([null, 'm011'])
  })

  it('drops messages that the fresh page no longer lists within its range', () => {
    const head: History = {
      messages: range(13, 20).filter((item) => item.id !== 'm017'),
      before: 'm013',
    }
    expect(ids(mergeHead(loaded(), head))[0]).toEqual([11, 12, 13, 14, 15, 16, 18, 19, 20])
  })

  it('replaces the history when the fresh page does not reach back to it or is all of it', () => {
    const gap: History = { messages: range(40, 49), before: 'm040' }
    expect(mergeHead(loaded(), gap)).toEqual({ pages: [gap], pageParams: [null] })
    const whole: History = { messages: range(1, 21), before: null }
    expect(mergeHead(loaded(), whole)).toEqual({ pages: [whole], pageParams: [null] })
  })

  it('continues from the fresh cursor when the fresh page covers the newest page', () => {
    const data: HistoryData = {
      pages: [{ messages: range(15, 20), before: 'm015' }],
      pageParams: [null],
    }
    const head: History = { messages: range(11, 21), before: 'm011' }
    const next = mergeHead(data, head)
    expect(ids(next)).toEqual([range(11, 21).map((_, i) => 11 + i)])
    expect(next.pages[0]!.before).toBe('m011')
  })
})

describe('rechunk', () => {
  it('splits a grown newest page back into server-sized pages with a consistent cursor', () => {
    const data: HistoryData = {
      pages: [{ messages: range(1, 101), before: 'm001' }],
      pageParams: [null],
    }
    const next = rechunk(data)
    expect(next.pages.map((page) => page.messages.length)).toEqual([50, 51])
    expect(next.pages[0]!.before).toBe('m052')
    expect(next.pages[1]!.before).toBe('m001')
    expect(next.pageParams).toEqual([null, 'm052'])
  })
})

describe('indexHistory', () => {
  it('maps turns, clarification sources and open proposals to their messages', () => {
    const data: HistoryData = {
      pages: [
        {
          messages: [
            message(1, {
              turns: [{ id: 't1', state: 'completed', result: { confirmation_pending: true } }],
            } as never),
            message(2, { clarification_receipt: { message_id: 'm001' } } as never),
          ],
          before: null,
        },
      ],
      pageParams: [null],
    }
    const index = indexHistory(data)
    expect(index.turns.get('t1')).toBe('m001')
    expect(index.sources.get('m001')).toEqual(['m002'])
    expect([...index.proposals]).toEqual(['m001'])
  })
})

describe('shareMessages', () => {
  it('keeps unchanged messages identical when a new message shifts every page', () => {
    const previous = loaded()
    const refetched: HistoryData = {
      pages: [
        { messages: range(12, 21), before: 'm012' },
        { messages: range(2, 11), before: 'm002' },
      ],
      pageParams: [null, 'm012'],
    }
    const shared = shareMessages(previous, structuredClone(refetched)) as HistoryData
    expect(shared.pages[0]!.messages[0]).toBe(previous.pages[0]!.messages[1])
    expect(shared.pages[1]!.messages.at(-1)).toBe(previous.pages[0]!.messages[0])
    expect(shared.pages[0]!.messages.at(-1)!.id).toBe('m021')
  })
})
