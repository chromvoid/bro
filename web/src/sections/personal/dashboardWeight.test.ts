import { describe, expect, it } from 'vitest'
import { History, Observation, type Kind } from './api'
import { SourceAnalysisSummary } from './bodyArchive'
import {
  dashboardAxis,
  dashboardDateX,
  dashboardFatMeasurements,
  dashboardFatSeries,
  dashboardLine,
  dashboardNearestPoint,
  dashboardValueY,
  dashboardWeightChange,
  dashboardWeightConflicts,
  dashboardWeightPoints,
  dashboardWeightRange,
  type DashboardWeightPoint,
} from './dashboardWeight'

const range = { from: '2026-09-29', to: '2026-10-05' }
function observation(id: string, day: string, value = '75', kind: Kind = 'weight') {
  return Observation.parse({
    id,
    source_id: 'source-a',
    source_name: 'Synthetic source',
    kind,
    start_at: `${day}T09:00:00Z`,
    end_at: `${day}T09:00:00Z`,
    timezone: 'UTC',
    local_date: day,
    method: 'device_report',
    origin: { provider: 'DDX Fitness', record_id: id },
    data: { value },
    revision: 1,
  })
}
function history(
  records: Observation[] = [],
  daily: unknown[] = [],
  extra: Record<string, unknown> = {},
) {
  return History.parse({ ...range, records, daily, ...extra })
}
const selected = (
  day: string,
  value: unknown,
  records: Observation[] = [],
  status = 'available',
) => ({
  date: day,
  kind: 'weight',
  value,
  status,
  target: null,
  records,
})
const point = (day: string, value: number): DashboardWeightPoint => ({
  key: day,
  day,
  value,
  kind: 'weight',
  records: [],
})
function archive(record: Observation) {
  return SourceAnalysisSummary.parse({
    id: `archive:${record.id}`,
    source_id: record.source_id,
    source_name: record.source_name,
    provider_record_id: record.origin!.record_id,
    measured_at: record.start_at,
    revision: 1,
    received_at: '2026-10-05T10:00:00Z',
  })
}

describe('dashboard weight selection', () => {
  it('uses exactly 7, 30 or 90 calendar days including the selected end day', () => {
    expect(dashboardWeightRange('2026-10-05', 7)).toEqual(range)
    expect(dashboardWeightRange('2026-10-05', 30)).toEqual({ from: '2026-09-06', to: '2026-10-05' })
    expect(dashboardWeightRange('2026-10-05', 90)).toEqual({ from: '2026-07-08', to: '2026-10-05' })
    expect(dashboardWeightRange('2024-03-01', 7)).toEqual({ from: '2024-02-24', to: '2024-03-01' })
    expect(dashboardWeightRange('2026-01-01', 7)).toEqual({ from: '2025-12-26', to: '2026-01-01' })
  })

  it('plots the server-selected value and exact record rather than the newest competing source', () => {
    const preferred = observation('preferred', '2026-10-01', '74.2')
    const latest = {
      ...observation('other', '2026-10-01', '80'),
      source_id: 'source-b',
      start_at: '2026-10-01T20:00:00Z',
    }
    const result = dashboardWeightPoints(
      history([preferred, latest], [selected('2026-10-01', '74.2', [preferred])]),
      range,
    )
    expect(result.map((entry) => [entry.day, entry.value, entry.records[0]?.id])).toEqual([
      ['2026-10-01', 74.2, 'preferred'],
    ])
  })

  it('keeps the API calendar date when the observation timestamp or local date differs', () => {
    const record = observation('calendar', '2026-09-30')
    const result = dashboardWeightPoints(
      history([record], [selected('2026-10-01', '75', [record])]),
      range,
    )
    expect(result[0]?.day).toBe('2026-10-01')
    expect(result[0]?.records[0]?.start_at).toBe('2026-09-30T09:00:00Z')
  })

  it('skips conflicts, missing values, invalid days, duplicates and records outside both boundaries', () => {
    const raw = history(
      [],
      [
        selected('2026-09-28', '73'),
        selected('2026-09-29', '74'),
        selected('2026-09-30', '75', [], 'conflict'),
        selected('2026-10-01', null),
        selected('2026-10-02', '  '),
        selected('2026-10-03', '0'),
        selected('2026-10-04', 'Infinity'),
        selected('2026-10-05', '76'),
        selected('2026-10-06', '77'),
        selected('2026-02-30', '78'),
        selected('2026-10-02', '79'),
        selected('2026-10-02', '80'),
        selected('2026-10-03', '81', [], 'unavailable'),
      ],
    )
    expect(dashboardWeightPoints(raw, range).map((entry) => [entry.day, entry.value])).toEqual([
      ['2026-09-29', 74],
      ['2026-10-05', 76],
    ])
    expect(dashboardWeightConflicts(raw, range)).toEqual(['2026-09-30'])
    expect(dashboardWeightChange(dashboardWeightPoints(raw, range))).toBe(2)
    expect(dashboardWeightChange([])).toBeNull()
    expect(dashboardWeightChange([point('2026-10-05', 76)])).toBeNull()
  })

  it('never carries earlier measurements forward into an empty history period', () => {
    expect(dashboardWeightPoints(history([observation('old', '2026-09-20')]), range)).toEqual([])
    expect(dashboardWeightPoints(undefined, range)).toEqual([])
    expect(dashboardWeightConflicts(undefined, range)).toEqual([])
  })

  it('does not claim provenance from equal competing records or select an inconsistent duplicate day', () => {
    const a = observation('equal-a', '2026-10-01')
    const b = { ...observation('equal-b', '2026-10-01'), source_id: 'source-b' }
    const raw = history(
      [a, b],
      [{ date: '2026-10-01', kind: 'weight', value: '75', status: 'available', target: null }],
    )
    expect(dashboardWeightPoints(raw, range)[0]?.records).toEqual([])
    expect(
      dashboardWeightPoints(
        history(
          [a],
          [selected('2026-10-01', '75', [a]), selected('2026-10-01', null, [], 'conflict')],
        ),
        range,
      ),
    ).toEqual([])
  })
})

describe('dashboard InBody fat measurements', () => {
  it('accepts verified DDX origin without a device label and rejects names or generic methods as evidence', () => {
    const accepted = observation('ddx', '2026-10-01', '22.5', 'body_fat_percent')
    const generic = {
      ...observation('generic', '2026-10-02', '23', 'body_fat_percent'),
      source_name: 'InBody',
      origin: { provider: 'Synthetic', device: 'InBody', record_id: 'generic' },
    }
    const caliper = {
      ...observation('caliper', '2026-10-03', '20', 'body_fat_percent'),
      method: 'derived',
      origin: { provider: 'caliper', record_id: 'caliper' },
    }
    const series = dashboardFatSeries(history([accepted, generic, caliper]), range)
    expect(series).toHaveLength(1)
    expect(series[0]?.points.map((entry) => [entry.day, entry.value])).toEqual([
      ['2026-10-01', 22.5],
    ])
  })

  it('also recognizes a verified DDX source connection after a provider origin is absent', () => {
    const record = {
      ...observation('connected', '2026-10-02', '23', 'body_fat_percent'),
      origin: null,
    }
    const raw = history([record], [], {
      sources: [
        {
          id: 'source-a',
          name: 'Synthetic',
          kinds: ['body_fat_percent'],
          status: 'unavailable',
          revision: 1,
          connection: {
            provider: 'ddx',
            generation: 1,
            revision: 1,
            auth_state: 'auth_required',
            automatic: false,
          },
        },
      ],
    })
    expect(dashboardFatSeries(raw, range)[0]?.points[0]?.value).toBe(23)
  })

  it('keeps corrected scalar values on the original source day and attaches the matching archive', () => {
    const original = observation('corrected', '2026-10-01', '22.5', 'body_fat_percent')
    const corrected = Observation.parse({
      ...original,
      start_at: '2026-10-05T09:00:00Z',
      local_date: '2026-10-05',
      corrected: true,
      origin: {},
      data: { value: '22.1' },
      original: {
        start_at: '2026-09-30T22:00:00Z',
        local_date: '2026-10-01',
        timezone: 'Europe/Moscow',
        data: original.data,
        origin: original.origin,
      },
    })
    const saved = { ...archive(original), measured_at: '2026-09-30T22:00:00Z' }
    const result = dashboardFatSeries(history([corrected], [], { source_analyses: [saved] }), range)
    expect(result[0]?.points[0]).toMatchObject({ day: '2026-10-01', value: 22.1, archive: saved })
    const missingOriginalDate = {
      ...corrected,
      original: { ...corrected.original!, local_date: undefined },
    }
    expect(dashboardFatSeries(history([missingOriginalDate]), range)[0]?.points[0]?.day).toBe(
      '2026-10-01',
    )
  })

  it('rejects excluded, blank, out-of-range and ambiguous values while retaining details', () => {
    const a = observation('duplicate-a', '2026-10-01', '21', 'body_fat_percent')
    const b = {
      ...observation('duplicate-b', '2026-10-01', '23', 'body_fat_percent'),
      origin: a.origin,
    }
    const excluded = {
      ...observation('excluded', '2026-10-02', '23', 'body_fat_percent'),
      excluded: true,
    }
    const blank = observation('blank', '2026-10-03', ' ', 'body_fat_percent')
    const invalid = observation('invalid', '2026-10-04', '101', 'body_fat_percent')
    const outside = observation('outside', '2026-10-06', '24', 'body_fat_percent')
    const raw = history([a, b, excluded, blank, invalid, outside])
    expect(dashboardFatSeries(raw, range)).toEqual([])
    expect(
      dashboardFatMeasurements(raw, range)
        .map((entry) => entry.record.id)
        .sort(),
    ).toEqual(['blank', 'duplicate-a', 'duplicate-b', 'excluded', 'invalid'])
  })

  it('does not choose between simultaneous analyses, including equivalent timezone-offset strings', () => {
    const a = observation('scan-a', '2026-10-01', '21', 'body_fat_percent')
    const b = {
      ...observation('scan-b', '2026-10-01', '23', 'body_fat_percent'),
      start_at: '2026-10-01T12:00:00+03:00',
    }
    const c = observation('scan-c', '2026-10-02', '22', 'body_fat_percent')
    expect(
      dashboardFatSeries(history([a, b, c]), range)[0]?.points.map((entry) => entry.value),
    ).toEqual([22])
  })

  it('keeps independent sources separate and cannot turn a raw-only source archive into a scalar', () => {
    const a = observation('scan-a', '2026-10-01', '21', 'body_fat_percent')
    const b = {
      ...observation('scan-b', '2026-10-02', '23', 'body_fat_percent'),
      source_id: 'source-b',
    }
    const saved = archive(observation('raw-only', '2026-10-05', '24', 'body_fat_percent'))
    const result = dashboardFatSeries(history([a, b], [], { source_analyses: [saved] }), range)
    expect(result.map((group) => [group.sourceId, group.points.length])).toEqual([
      ['source-b', 1],
      ['source-a', 1],
    ])
    expect(dashboardFatSeries(history([], [], { source_analyses: [saved] }), range)).toEqual([])
  })
})

describe('dashboard chart geometry', () => {
  it('lets touch select either same-day series by its actual plotted height', () => {
    const weight = { kind: 'weight', x: 160, y: 35 }
    const fat = { kind: 'fat', x: 160, y: 135 }
    expect(dashboardNearestPoint([weight, fat], { x: 162, y: 37 })).toBe(weight)
    expect(dashboardNearestPoint([weight, fat], { x: 158, y: 130 })).toBe(fat)
    expect(dashboardNearestPoint([], { x: 0, y: 0 })).toBeNull()
  })

  it('places every series on one calendar axis and keeps the selected range even for sparse measurements', () => {
    expect(dashboardDateX('2026-10-01', range, 320)).toBeCloseTo(40 + (2.5 / 7) * 240)
    expect(dashboardDateX(range.from, range, 320)).toBeGreaterThan(40)
    expect(dashboardDateX(range.to, range, 320)).toBeLessThan(280)
    expect(dashboardWeightChange([point(range.from, 75), point(range.to, 75)])).toBe(0)
  })

  it('keeps a single or flat measurement finite and separates kg from the shared fat scale', () => {
    expect(dashboardAxis([])).toBeNull()
    for (const value of [0, 22.5, 75]) {
      const axis = dashboardAxis([point(range.to, value)])!
      expect(axis.max).toBeGreaterThan(axis.min)
      expect(dashboardValueY(value, axis)).toBeGreaterThanOrEqual(24)
      expect(dashboardValueY(value, axis)).toBeLessThanOrEqual(140)
    }
    const low = [point(range.from, 20)],
      high = [point(range.to, 30)]
    const sharedAxis = dashboardAxis([...low, ...high])!
    expect(dashboardLine(low, range, 320, [], sharedAxis)).toContain(',140.0')
    expect(dashboardLine(high, range, 320, [], sharedAxis)).toContain(',24.0')
  })

  it('breaks a weight line across conflicts instead of hiding a conflict in a continuous line', () => {
    const records = [point(range.from, 75), point(range.to, 74)]
    expect(dashboardLine(records, range, 320).match(/M/g)).toHaveLength(1)
    expect(dashboardLine(records, range, 320, ['2026-10-01']).match(/M/g)).toHaveLength(2)
    expect(dashboardLine([], range, 320)).toBe('')
  })
})
