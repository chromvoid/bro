import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import type { Permission } from './position'

const state = vi.hoisted(() => ({
  permission: 'granted' as Permission,
  fix: {
    captured_at: '2026-10-09T18:10:00.000Z',
    latitude: 55.73812,
    longitude: 37.58004,
    accuracy_m: 40,
  },
  sent: [] as unknown[],
}))
vi.mock('./position', () => ({
  positionSource: async () => ({
    kind: 'browser',
    status: async () => state.permission,
    request: async () => state.permission,
    current: async () => state.fix,
  }),
}))
vi.mock('./api', () => ({ sendFix: async (fix: unknown) => void state.sent.push(fix) }))

import { REPORT_EVERY, useLocationReporter } from './useLocationReporter'

let visibility: DocumentVisibilityState = 'visible'

function show(next: DocumentVisibilityState) {
  visibility = next
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('location reporter', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
    visibility = 'visible'
    state.permission = 'granted'
    state.sent = []
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('sends on start, on return after ten minutes and on the interval, never more often', async () => {
    const scope = effectScope()
    scope.run(() => useLocationReporter())
    await vi.advanceTimersByTimeAsync(0)
    expect(state.sent).toHaveLength(1)
    show('hidden')
    show('visible')
    await vi.advanceTimersByTimeAsync(0)
    expect(state.sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(REPORT_EVERY)
    expect(state.sent).toHaveLength(2)
    scope.stop()
    await vi.advanceTimersByTimeAsync(REPORT_EVERY * 3)
    expect(state.sent).toHaveLength(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('never asks and never sends without a granted permission or while hidden', async () => {
    state.permission = 'prompt'
    const scope = effectScope()
    scope.run(() => useLocationReporter())
    await vi.advanceTimersByTimeAsync(REPORT_EVERY * 2)
    expect(state.sent).toHaveLength(0)
    state.permission = 'granted'
    visibility = 'hidden'
    await vi.advanceTimersByTimeAsync(REPORT_EVERY)
    expect(state.sent).toHaveLength(0)
    show('visible')
    await vi.advanceTimersByTimeAsync(0)
    expect(state.sent).toHaveLength(1)
    scope.stop()
  })
})
