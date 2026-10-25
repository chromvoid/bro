import { describe, expect, it } from 'vitest'
import { Limits, dailyLimitReached, nextReset, resetLabel, usageOf } from './limits'

describe('daily limits', () => {
  it('resets at the next UTC midnight, shown in the account zone', () => {
    const now = new Date('2026-09-23T15:00:00Z')
    expect(nextReset(now).toISOString()).toBe('2026-09-24T00:00:00.000Z')
    expect(resetLabel('Europe/Belgrade', now)).toBe('завтра в 02:00')
    // Late evening in Tokyo is already the next day there.
    expect(resetLabel('Asia/Tokyo', new Date('2026-09-23T20:00:00Z'))).toBe('сегодня в 09:00')
    expect(resetLabel('America/New_York', now)).toBe('сегодня в 20:00')
  })

  it('shows dictation in minutes and marks an exhausted limit', () => {
    const { limits } = Limits.parse({
      limits: [
        { kind: 'assistant', maximum: 100, used: '100' },
        { kind: 'dictation_ms', maximum: 1_800_000, used: '240500' },
        { kind: 'research', maximum: null, used: '3' },
        { kind: 'tts_chars', maximum: 500, used: '250' },
      ],
      timezone: 'UTC',
    })
    expect(usageOf(limits[0]!)).toEqual({
      used: '100',
      maximum: '100',
      share: 100,
      exhausted: true,
    })
    expect(usageOf(limits[1]!)).toMatchObject({ used: '4,1', maximum: '30', exhausted: false })
    expect(usageOf(limits[2]!)).toEqual({ used: '3', maximum: null, share: null, exhausted: false })
    expect(usageOf(limits[3]!)).toEqual({
      used: '250',
      maximum: '500',
      share: 50,
      exhausted: false,
    })
  })
})

describe('telling a daily limit from a busy server', () => {
  const counters = (used: number) => async () => ({
    limits: [{ kind: 'assistant', maximum: 20, used }],
    timezone: 'UTC',
  })

  it('reports the limit only when its counter is exhausted', async () => {
    expect(await dailyLimitReached('assistant', counters(20))).toBe(true)
    expect(await dailyLimitReached('assistant', counters(3))).toBe(false)
    expect(await dailyLimitReached('research', counters(20))).toBe(false)
    expect(
      await dailyLimitReached('assistant', async () => {
        throw new Error('offline')
      }),
    ).toBe(false)
  })
})
