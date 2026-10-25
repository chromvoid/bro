import { effectScope, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCalendarDay } from './useCalendarDay'

const scopes: ReturnType<typeof effectScope>[] = []
async function setup(zone = 'Europe/Moscow') {
  const scope = effectScope()
  scopes.push(scope)
  const timeZone = ref(zone)
  const today = scope.run(() => useCalendarDay(timeZone))!
  await nextTick()
  return { today, timeZone, scope }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T20:59:45Z'))
})
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
  vi.restoreAllMocks()
  vi.clearAllTimers()
  vi.useRealTimers()
})

describe('live account calendar day', () => {
  it('advances an open view at account midnight rather than UTC midnight', async () => {
    const { today, timeZone } = await setup()
    expect(today.value).toBe('2026-10-05')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(today.value).toBe('2026-10-06')
    timeZone.value = 'UTC'
    expect(today.value).toBe('2026-10-05')
  })

  it('refreshes immediately when a suspended page becomes visible or focused', async () => {
    const { today } = await setup()
    vi.setSystemTime(new Date('2026-10-06T05:00:00Z'))
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(today.value).toBe('2026-10-06')
    vi.setSystemTime(new Date('2026-10-07T05:00:00Z'))
    window.dispatchEvent(new Event('focus'))
    expect(today.value).toBe('2026-10-07')
  })

  it('releases its timer and wake listeners when the view closes', async () => {
    const { today, scope } = await setup()
    scope.stop()
    vi.setSystemTime(new Date('2026-10-07T05:00:00Z'))
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(today.value).toBe('2026-10-05')
    expect(vi.getTimerCount()).toBe(0)
  })
})
