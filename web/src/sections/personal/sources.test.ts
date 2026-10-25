import { describe, expect, it } from 'vitest'
import { ResolvedPage } from './resolutionContract'
import {
  combinedComparisons,
  contributors,
  dayAmount,
  latestDay,
  pinOf,
  policyEntries,
  rankedContributions,
  readingsOf,
  roleOf,
  rowItems,
  shortNames,
  SOURCE_ROWS,
  SourceEntry,
  sourcesOf,
} from './sources'

const DAY = '2026-10-13'
const source = (id: string, kind: string, metrics: string[], extra: Record<string, unknown> = {}) =>
  SourceEntry.parse({
    id,
    name: id === 'ring' ? 'R20_5AF2' : id,
    kinds: [],
    status: 'connected',
    revision: 1,
    metric_keys: metrics,
    device: { kind },
    created_at: '2026-10-01T10:00:00Z',
    ...extra,
  })
const ring = source('ring', 'ring', ['heart_rate', 'steps', 'sleep', 'running_steps'])
const strap = source('strap', 'whoop', ['heart_rate', 'steps', 'temperature'], {
  created_at: '2026-10-13T17:00:00Z',
})
const inbody = source('inbody', 'ddx', ['weight', 'body_fat_percent', 'skeletal_muscle_mass'])
const row = (key: string) => SOURCE_ROWS.find((item) => item.key === key)!

function page(daily: unknown[], policies: unknown[] = []) {
  return ResolvedPage.parse({
    from: DAY,
    to: DAY,
    timezone: 'UTC',
    computed_at: `${DAY}T20:00:00Z`,
    daily,
    series: [],
    next_cursor: null,
    fingerprint: 'f',
    policies,
  })
}
const day = (kind: string, resolution: Record<string, unknown>, date = DAY) => ({
  date,
  kind,
  status: 'available',
  value: null,
  unit: '',
  resolution: { status: 'available', fingerprint: 'f', resolver_version: 'v6', ...resolution },
})

describe('measurement sources', () => {
  it('lists the active sources that report a row and skips switched-off ones', () => {
    const off = source('old', 'ring', ['heart_rate'], {
      device: { kind: 'ring', collector_enabled: false },
    })
    expect(sourcesOf(row('heart_rate'), [ring, strap, inbody, off]).map((item) => item.id)).toEqual(
      ['ring', 'strap'],
    )
    expect(sourcesOf(row('workouts'), [ring, strap]).map((item) => item.id)).toEqual(['ring'])
  })

  it('reads the pin in effect on a day and treats an empty order as automatic', () => {
    const policies = [
      { metric: 'heart_rate', source_ids: ['strap'], effective_from: '2026-10-01', revision: 1 },
      { metric: 'heart_rate', source_ids: [], effective_from: '2026-10-12', revision: 1 },
      { metric: 'heart_rate', source_ids: ['ring'], effective_from: '2026-10-20', revision: 1 },
    ]
    expect(pinOf(row('heart_rate'), policies, '2026-10-05')).toBe('strap')
    expect(pinOf(row('heart_rate'), policies, DAY)).toBeNull()
  })

  it('pins only the metrics the chosen source reports and carries existing revisions', () => {
    const policies = [
      { id: 'p1', metric: 'body_fat_percent', source_ids: [], effective_from: DAY, revision: 3 },
    ]
    const pinned = policyEntries(row('body'), 'inbody', [inbody], policies, DAY)
    expect(pinned.map((item) => [item.metric, item.source_ids, item.revision])).toEqual([
      ['body_fat_percent', ['inbody'], 3],
      ['skeletal_muscle_mass', ['inbody'], 0],
    ])
    const automatic = policyEntries(row('heart_rate'), null, [ring, strap], [], DAY)
    expect(automatic.map((item) => [item.metric, item.source_ids])).toEqual([['heart_rate', []]])
  })

  it('ranks today’s contributors by value, then by readings, and tells each source’s role', () => {
    const today = page([
      day('steps', {
        contributions: [
          { source_id: 'strap', count: 2, value: '528', spans: [] },
          { source_id: 'ring', count: 15, value: '8432', spans: [] },
        ],
      }),
      day('heart_rate', {
        contributions: [
          { source_id: 'ring', count: 200, spans: [] },
          { source_id: 'strap', count: 27, spans: [] },
        ],
      }),
    ])
    expect(contributors(today.daily[0]).map((item) => item.source_id)).toEqual(['ring', 'strap'])
    expect(roleOf('strap', row('steps'), today, DAY)).toBe('backup')
    expect(roleOf('ring', row('heart_rate'), today, DAY)).toBe('primary')
    expect(roleOf('inbody', row('heart_rate'), today, DAY)).toBeNull()
  })

  it('ranks any decision’s contributors and reads a malformed list as none', () => {
    expect(
      rankedContributions([
        { source_id: 'strap', count: 27, spans: [] },
        { source_id: 'ring', count: 200, spans: [] },
        { source_id: null, count: 1, value: '75', spans: [] },
      ]).map((item) => item.source_id),
    ).toEqual([null, 'ring', 'strap'])
    expect(rankedContributions(undefined)).toEqual([])
    expect(rankedContributions([{ source_id: 'ring' }])).toEqual([])
  })

  it('combines a week of comparisons weighted by the matched readings', () => {
    const comparison = (count: number, mean: number, outside: number) => ({
      source_ids: ['ring', 'strap'],
      count,
      totals: [count * 70, count * 70 - count * mean],
      mean_difference: mean,
      mean_absolute_difference: mean,
      outside_reference: outside,
    })
    const week = page([
      day('heart_rate', { comparisons: [comparison(10, 2, 0)] }, '2026-10-12'),
      day('heart_rate', { comparisons: [comparison(30, 6, 3)] }),
    ])
    const [combined] = combinedComparisons(week)
    expect(combined!.count).toBe(40)
    expect(combined!.mean_absolute_difference).toBe(5)
    expect(combined!.outside_reference).toBe(3)
  })

  it('names devices briefly and tells two connections of one kind apart by their day', () => {
    const t = (key: string, named?: Record<string, unknown>) =>
      key === 'sources.since'
        ? `${named?.name} с ${named?.date}`
        : key.replace('sources.short.', '')
    const again = source('ring-2', 'ring', ['heart_rate'], { created_at: '2026-10-09T08:00:00Z' })
    const names = shortNames(t, [ring, again, strap], (item) => item.created_at!.slice(5, 10))
    expect(names.get('strap')).toBe('whoop')
    expect(names.get('ring')).toBe('ring с 10-01')
    expect(names.get('ring-2')).toBe('ring с 10-09')
  })

  it('keeps a device’s reported metrics of a row and a night known only by its periods', () => {
    const items = page([
      { ...day('workouts', {}), value: '1', unit: 'workouts' },
      { ...day('running_workouts', {}), value: null },
      day('sleep', {
        sessions: [{ id: 's', start_at: `${DAY}T00:00:00Z`, end_at: `${DAY}T06:00:00Z` }],
      }),
      { ...day('workouts', {}, '2026-10-12'), value: '2' },
    ]).daily
    expect(rowItems(row('workouts'), items, DAY).map((item) => item.kind)).toEqual(['workouts'])
    expect(rowItems(row('sleep'), items, DAY).map((item) => item.kind)).toEqual(['sleep'])
    expect(rowItems(row('steps'), items, DAY)).toEqual([])
  })

  it('reads one vital’s readings in time order and skips ranges without a value', () => {
    const series = [
      { id: 'b', metric: 'heart_rate', start_at: `${DAY}T10:05:00Z`, value: '80', unit: 'bpm' },
      { id: 'a', metric: 'heart_rate', start_at: `${DAY}T10:00:00Z`, value: '72', unit: 'bpm' },
      { id: 'c', metric: 'spo2', start_at: `${DAY}T10:00:00Z`, value: '97', unit: '%' },
      { id: 'd', metric: 'heart_rate', start_at: `${DAY}T10:10:00Z`, value: null, unit: 'bpm' },
    ]
    expect(readingsOf(series, 'heart_rate')).toEqual([
      { id: 'a', at: Date.parse(`${DAY}T10:00:00Z`), value: 72, unit: 'bpm' },
      { id: 'b', at: Date.parse(`${DAY}T10:05:00Z`), value: 80, unit: 'bpm' },
    ])
  })

  it('opens a device’s data on its latest measured day, never later than today', () => {
    const synced = source('strap', 'whoop', ['heart_rate'], {
      last_measured_at: '2026-10-13T20:55:00Z',
      last_received_at: '2026-10-13T21:14:00Z',
    })
    expect(latestDay(synced, 'Europe/Moscow', '2026-10-14')).toBe('2026-10-13')
    const unknown = source('scale', 'custom', ['weight'], {
      last_received_at: '2026-10-13T21:14:00Z',
    })
    expect(latestDay(unknown, 'Europe/Moscow', '2026-10-14')).toBe('2026-10-14')
    const ahead = source('clock', 'custom', ['weight'], {
      last_measured_at: '2026-10-20T10:00:00Z',
    })
    expect(latestDay(ahead, 'UTC', '2026-10-14')).toBe('2026-10-14')
    expect(latestDay(null, 'UTC', '2026-10-14')).toBe('2026-10-14')
  })

  it('writes a device’s day value as the dashboard does', () => {
    const count = (key: string, value: number) => `${value} ${key}`
    const value = (kind: string, amount: string | null, unit: string) =>
      dayAmount(count, { kind, value: amount, unit }, 'metric')
    expect(value('sleep', '450', 'min')).toBe('7 ч 30 мин')
    expect(value('heart_rate', '70.5', 'bpm')).toBe('70,5 уд/мин')
    expect(value('workouts', '2', 'workouts')).toBe('2 sources.workouts')
    expect(value('hrv', null, 'ms')).toBeNull()
  })
})
