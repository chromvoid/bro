import { describe, expect, it } from 'vitest'
import { DONE_MS, MoodTracker, OOPS_MS, type MoodExchange } from './brandMood'

const exchange = (
  id: string,
  status: MoodExchange['status'],
  { committed = false, controlState = null as string | null } = {},
): MoodExchange => ({ id, status, controlState, replies: [{ committed }] })

describe('MoodTracker', () => {
  it('stays plain for history that loads already finished', () => {
    const moods = new MoodTracker()
    const history = [exchange('a', 'failed'), exchange('b', 'done', { committed: true })]
    expect(moods.update(history, false, 0)).toEqual({ mood: 'ok', until: null })
    // Older pages arrive above the newest exchange: they never flash either.
    expect(moods.update([exchange('older', 'failed'), ...history], false, 1)).toEqual({
      mood: 'ok',
      until: null,
    })
  })

  it('thinks while sending or while a turn runs', () => {
    const moods = new MoodTracker()
    expect(moods.update([], true, 0).mood).toBe('thinking')
    expect(moods.update([exchange('a', 'pending')], false, 1).mood).toBe('thinking')
  })

  it('shows done only after a turn that confirmed a write, then returns', () => {
    const moods = new MoodTracker()
    moods.update([exchange('a', 'pending')], false, 0)
    expect(moods.update([exchange('a', 'done', { committed: true })], false, 100)).toEqual({
      mood: 'done',
      until: 100 + DONE_MS,
    })
    expect(
      moods.update([exchange('a', 'done', { committed: true })], false, 100 + DONE_MS).mood,
    ).toBe('ok')
    moods.update([exchange('a', 'done'), exchange('b', 'pending')], false, 200 + DONE_MS)
    expect(
      moods.update([exchange('a', 'done'), exchange('b', 'done')], false, 300 + DONE_MS).mood,
    ).toBe('ok')
  })

  it('is sad for a minute after a failure but not after a stop the person asked for', () => {
    const moods = new MoodTracker()
    moods.update([exchange('a', 'pending'), exchange('b', 'pending')], false, 0)
    const stopped = exchange('a', 'failed', { controlState: 'settled' })
    expect(moods.update([stopped, exchange('b', 'pending')], false, 10).mood).toBe('thinking')
    expect(moods.update([stopped, exchange('b', 'failed')], false, 20)).toEqual({
      mood: 'oops',
      until: 20 + OOPS_MS,
    })
    expect(moods.update([stopped, exchange('b', 'failed')], false, 20 + OOPS_MS).mood).toBe('ok')

    const control = new MoodTracker()
    control.update([exchange('c', 'pending')], false, 0)
    expect(control.update([stopped], false, 1).mood).toBe('ok')
  })

  it('flashes for a newest exchange that first appears already finished', () => {
    const moods = new MoodTracker()
    moods.update([exchange('a', 'done')], true, 0)
    expect(
      moods.update([exchange('a', 'done'), exchange('b', 'done', { committed: true })], false, 5)
        .mood,
    ).toBe('done')
  })

  it('a newer plain outcome replaces an earlier flash', () => {
    const moods = new MoodTracker()
    moods.update([exchange('a', 'pending')], false, 0)
    expect(moods.update([exchange('a', 'failed')], false, 1).mood).toBe('oops')
    moods.update([exchange('a', 'failed'), exchange('b', 'pending')], false, 2)
    expect(
      moods.update([exchange('a', 'failed'), exchange('b', 'clarification')], false, 3).mood,
    ).toBe('ok')
  })
})
