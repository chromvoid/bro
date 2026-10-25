import type { QueryClient } from '@tanstack/vue-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { openAccount } from './account'
import { Api } from './http'

const mocks = vi.hoisted(() => ({
  open: vi.fn(),
  unavailable: vi.fn(),
  start: vi.fn(),
  stop: vi.fn(),
  change: null as ((event: { topic: string | null }) => void) | null,
}))
vi.mock('./storage', () => ({
  AccountStore: { open: mocks.open, unavailable: mocks.unavailable },
  persistStorage: async () => false,
}))
vi.mock('./accountEvents', () => ({
  AccountEvents: class {
    constructor(
      private options: { onResync(): void; onChange(event: { topic: string | null }): void },
    ) {
      mocks.change = options.onChange
    }
    start() {
      mocks.start()
      this.options.onResync()
    }
    stop() {
      mocks.stop()
    }
  },
}))

describe('read-only account without durable browser storage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
  it('refreshes local schedules for authenticated task, timer, access and settings events', async () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    const store = {
      userId: 'synthetic',
      close: vi.fn(),
      submissions: vi.fn(async () => []),
      value: vi.fn(async () => null),
    }
    mocks.open.mockResolvedValue(store)
    const changed = vi.fn()
    const accessChanged = vi.fn()
    const account = await openAccount(
      'synthetic',
      new Api(vi.fn()),
      { isFetching: () => 0, invalidateQueries: vi.fn(async () => {}) } as unknown as QueryClient,
      { changed, accessChanged, denied: vi.fn() },
    )
    changed.mockClear()
    try {
      for (const topic of [
        'timers.changed',
        'tasks.changed',
        'notifications.changed',
        'access.changed',
        'resource.changed',
        null,
      ])
        mocks.change?.({ topic })
      expect(changed).toHaveBeenCalledTimes(6)
      expect(accessChanged).toHaveBeenCalledOnce()
      mocks.change?.({ topic: 'nutrition.changed' })
      expect(changed).toHaveBeenCalledTimes(6)
    } finally {
      account.close()
    }
  })
  it('leaves a claimed domain to its screen and invalidates the rest of the event', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('BroadcastChannel', undefined)
    mocks.open.mockResolvedValue({
      userId: 'synthetic',
      close: vi.fn(),
      value: vi.fn(async () => null),
      submissions: vi.fn(async () => []),
    })
    const invalidateQueries = vi.fn(async () => {})
    const account = await openAccount(
      'synthetic',
      new Api(vi.fn()),
      { isFetching: () => 0, invalidateQueries } as unknown as QueryClient,
      { accessChanged: vi.fn(), denied: vi.fn() },
    )
    try {
      await vi.advanceTimersByTimeAsync(300)
      invalidateQueries.mockClear()
      let accept = true
      const release = account.changes.claim('conversation', () => accept)
      mocks.change?.({ topic: 'assistant.changed' })
      await vi.advanceTimersByTimeAsync(300)
      expect(invalidateQueries.mock.calls).toEqual([[{ queryKey: ['community'] }]])
      invalidateQueries.mockClear()
      accept = false
      mocks.change?.({ topic: 'assistant.changed' })
      await vi.advanceTimersByTimeAsync(300)
      expect(invalidateQueries.mock.calls).toEqual([
        [{ queryKey: ['conversation'] }],
        [{ queryKey: ['community'] }],
      ])
      release()
    } finally {
      account.close()
      vi.useRealTimers()
    }
  })
  it('keeps server reads and invalidation available while the draft/journal explicitly reject writes', async () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    const failure = new Error('IndexedDB unavailable')
    const store = {
      userId: 'synthetic',
      close: vi.fn(),
      submissions: vi.fn(async () => {
        throw failure
      }),
      saveSubmission: vi.fn(async () => {
        throw failure
      }),
      submission: vi.fn(async () => {
        throw failure
      }),
    }
    mocks.open.mockRejectedValue(failure)
    mocks.unavailable.mockReturnValue(store)
    const api = new Api(vi.fn())
    const invalidateQueries = vi.fn(async () => {})
    const account = await openAccount(
      'synthetic',
      api,
      {
        isFetching: () => 0,
        invalidateQueries,
      } as unknown as QueryClient,
      { accessChanged: vi.fn(), denied: vi.fn() },
    )
    try {
      expect(mocks.unavailable).toHaveBeenCalledWith('synthetic')
      expect(mocks.start).toHaveBeenCalled()
      await expect(account.journal.pending()).rejects.toBe(failure)
      await expect(
        account.journal.submit({ endpoint: '/v1/test', lookupPrefix: null, body: {} }),
      ).rejects.toBe(failure)
    } finally {
      account.close()
    }
    expect(mocks.stop).toHaveBeenCalled()
    expect(store.close).toHaveBeenCalledOnce()
  })
})
