import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Invalidator, affectedDomains } from './topics'

describe('affectedDomains', () => {
  it('refreshes only the changed domain', () => {
    expect(affectedDomains('nutrition.changed')).toEqual(['nutrition'])
    expect(affectedDomains('nutrition.catalog')).toEqual(['catalog'])
    expect(affectedDomains('assistant.changed')).toEqual(['conversation', 'community'])
    expect(affectedDomains('notifications.changed')).toEqual(['notifications'])
    expect(affectedDomains('calls.changed')).toEqual(['calls'])
    expect(affectedDomains('resource.changed')).toEqual([
      'community',
      'shared',
      'tasks',
      'challenges',
    ])
  })

  it('refreshes everything for access changes and unknown topics', () => {
    expect(affectedDomains('access.changed')).toBe('all')
    expect(affectedDomains('future.changed')).toBe('all')
    expect(affectedDomains(null)).toBe('all')
  })
})

describe('Invalidator', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  function queries() {
    const fetching = new Set<string>()
    const invalidated: string[] = []
    return {
      fetching,
      invalidated,
      isFetching: (filters?: { queryKey: readonly unknown[] }) =>
        filters ? (fetching.has(String(filters.queryKey[0])) ? 1 : 0) : fetching.size,
      invalidateQueries: async (filters?: { queryKey: readonly unknown[] }) => {
        invalidated.push(filters ? String(filters.queryKey[0]) : '*')
      },
    }
  }

  it('merges a burst of events into one reload per domain', () => {
    const fake = queries()
    const invalidator = new Invalidator(fake, 250)
    for (let index = 0; index < 5; index += 1) invalidator.add(['personal'])
    invalidator.add(['tasks'])
    expect(fake.invalidated).toEqual([])
    vi.advanceTimersByTime(250)
    expect(fake.invalidated).toEqual(['personal', 'tasks'])
  })

  it('never cancels a running reload and follows it with one more', () => {
    const fake = queries()
    const invalidator = new Invalidator(fake, 250)
    fake.fetching.add('personal')
    invalidator.add(['personal'])
    vi.advanceTimersByTime(250)
    invalidator.add(['personal'])
    vi.advanceTimersByTime(250)
    expect(fake.invalidated).toEqual([])
    fake.fetching.delete('personal')
    vi.advanceTimersByTime(250)
    expect(fake.invalidated).toEqual(['personal'])
    vi.advanceTimersByTime(1000)
    expect(fake.invalidated).toEqual(['personal'])
  })

  it('reloads everything once nothing is loading', () => {
    const fake = queries()
    const invalidator = new Invalidator(fake, 250)
    fake.fetching.add('tasks')
    invalidator.add(['personal'])
    invalidator.add('all')
    vi.advanceTimersByTime(250)
    expect(fake.invalidated).toEqual([])
    fake.fetching.clear()
    vi.advanceTimersByTime(250)
    expect(fake.invalidated).toEqual(['*'])
    invalidator.stop()
  })
})
