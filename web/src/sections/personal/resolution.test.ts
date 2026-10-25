import { describe, expect, it } from 'vitest'
import { Metric } from './api'
import { ResolvedPage, consistentPages, groupFacts } from './resolution'

import { decision, page, sleepDecision } from './resolverFixtures'

describe('the additive resolver contract', () => {
  it('retains semantic statistics, alternatives and protocol state without adding a range mean', () => {
    const parsed = ResolvedPage.parse(page())
    expect(parsed.daily[0]?.resolution.statistics?.ranges?.min).toBe('94')
    expect(parsed.daily[0]?.resolution.statistics?.ranges?.mean).toBeNull()
    expect(parsed.daily[0]?.resolution.protocol?.verified).toBe(false)
    expect(parsed.daily[0]?.resolution.alternatives[0]?.reason).toBe('partial_overlap')
  })
  it('accepts legacy scalar responses and their additive decisions', () => {
    const legacy = { kind: 'sleep', value: 109, unit: 'min', status: 'available', complete: false }
    expect(Metric.parse(legacy).resolution).toBeUndefined()
    expect(
      Metric.parse({ ...legacy, resolution: decision() }).resolution?.selected[0]?.revision,
    ).toBe(2)
  })
  it('rejects merging cursor pages from different decisions', () => {
    const first = ResolvedPage.parse(page())
    expect(consistentPages([first, first])).toBe(true)
    expect(consistentPages([first, { ...first, fingerprint: 'changed-after-first-page' }])).toBe(
      false,
    )
  })
  it('types sleep sessions and stages without inventing a duration for an unknown stage', () => {
    const parsed = Metric.parse({
      kind: 'sleep',
      status: 'available',
      value: null,
      unit: 'min',
      resolution: sleepDecision(),
    })
    expect(parsed.value).toBeNull()
    expect(parsed.resolution?.sessions?.[0]?.asleep_minutes).toBeNull()
    expect(parsed.resolution?.sessions?.[0]?.stages[1]?.stage).toBe('unknown:1')
    expect(parsed.resolution?.sessions?.[1]?.asleep_minutes).toBe('30')
    expect(parsed.resolution?.quality?.flags).toEqual(['unknown_sleep_stage'])
    expect(parsed.resolution?.alternatives[0]?.superseded_by).toBe('night')
  })
  it('accepts synthetic legacy policy baselines and an explicitly unlinked identity', () => {
    const parsed = ResolvedPage.parse({
      ...page(),
      policies: [
        {
          id: null,
          metric: 'weight',
          source_ids: [null],
          effective_from: '0001-01-01',
          revision: 0,
        },
      ],
      source_identities: { ring: { physical_id: null, confirmed: false, revision: 2 } },
    })
    expect(parsed.policies[0]?.id).toBeNull()
    expect(parsed.source_identities?.ring?.physical_id).toBeNull()
  })
})

describe('grouped alternatives', () => {
  it('folds the same source and reason into one line with a count', () => {
    const fact = (id: string, source: string | null, reason: string) => ({
      id,
      source_id: source,
      source_name: source ? `Кольцо ${source}` : null,
      reason,
    })
    const groups = groupFacts([
      fact('a', 'ring', 'source_selection_required'),
      fact('b', 'ring', 'source_selection_required'),
      fact('c', 'ring', 'source_selection_required'),
      fact('d', 'phone', 'source_selection_required'),
      fact('e', 'ring', 'outside_window'),
    ])
    expect(groups.map(({ fact, count }) => [fact.id, count])).toEqual([
      ['a', 3],
      ['d', 1],
      ['e', 1],
    ])
  })
})
