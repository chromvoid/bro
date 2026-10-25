import { describe, expect, it } from 'vitest'
import { Observation, type Kind } from './api'
import { bodyAnalyses, bodyDelta, bodyPoints } from './body'

function record(
  id: string,
  scan: string,
  day: string,
  kind: Kind = 'body_fat_percent',
  value: string | null = '22.7',
  source = 'source-a',
): Observation {
  return Observation.parse({
    id,
    source_id: source,
    source_name: `Анализатор ${source}`,
    kind,
    start_at: `${day}T09:00:00Z`,
    end_at: `${day}T09:00:00Z`,
    timezone: 'UTC',
    method: 'device_report',
    origin: { record_id: scan, provider: 'Synthetic' },
    data: { value },
    revision: 1,
  })
}

describe('body analysis history', () => {
  it('groups only proven source identities and keeps corrected values in their original scan', () => {
    const original = record('fat', 'scan-1', '2026-09-10')
    const corrected = Observation.parse({
      ...original,
      start_at: '2026-10-01T10:00:00Z',
      origin: {},
      corrected: true,
      original: { data: original.data, start_at: original.start_at, origin: original.origin },
      data: { value: '22.1' },
    })
    const groups = bodyAnalyses([
      corrected,
      record('weight', 'scan-1', '2026-09-10', 'weight', '70'),
      record('other-source', 'scan-1', '2026-09-10', 'body_fat_percent', '21', 'source-b'),
      record('different-scan', 'scan-2', '2026-09-10'),
      { ...record('no-origin', 'unused', '2026-09-10'), origin: null },
      {
        ...record('manual-weight', 'unused', '2026-09-10', 'weight', '70'),
        source_id: null,
        origin: null,
      },
    ])
    expect(groups).toHaveLength(4)
    const scan = groups.find((group) => group.records.some((item) => item.id === 'fat'))!
    expect(scan.records.map((item) => item.id)).toEqual(['weight', 'fat'])
    expect(scan.measuredAt).toBe('2026-09-10T09:00:00Z')
    expect(scan.values.body_fat_percent?.data.value).toBe('22.1')
    expect(scan.previous).toEqual({})
  })

  it('compares each metric to its earlier available same-source reading and retains its date', () => {
    const groups = bodyAnalyses([
      record('fat-old', 'old', '2026-08-01', 'body_fat_percent', '23.4'),
      record('muscle-old', 'old', '2026-08-01', 'skeletal_muscle_mass', '30.2'),
      record('water-middle', 'middle', '2026-09-01', 'body_water', '39.2'),
      {
        ...record('excluded-middle', 'middle', '2026-09-01', 'body_fat_percent', '20'),
        excluded: true,
      },
      record('fat-other-source', 'other', '2026-09-15', 'body_fat_percent', '27', 'source-b'),
      record('fat-new', 'new', '2026-09-20', 'body_fat_percent', '22.7'),
      record('muscle-new', 'new', '2026-09-20', 'skeletal_muscle_mass', '30.5'),
    ])
    const latest = groups[0]!
    expect(latest.previous.body_fat_percent?.record.id).toBe('fat-old')
    expect(latest.previous.body_fat_percent?.measuredAt).toBe('2026-08-01T09:00:00Z')
    expect(
      bodyDelta(latest.values.body_fat_percent!, latest.previous.body_fat_percent!.record),
    ).toBe('−0,7 п. п.')
    expect(
      bodyDelta(latest.values.skeletal_muscle_mass!, latest.previous.skeletal_muscle_mass!.record),
    ).toBe('+0,3 кг')
    expect(groups.find((group) => group.sourceId === 'source-b')?.previous).toEqual({})
    expect(bodyPoints(groups, 'source-a', 'body_fat_percent')).toEqual([
      { x: Date.parse('2026-08-01T09:00:00Z'), y: 23.4 },
      { x: Date.parse('2026-09-20T09:00:00Z'), y: 22.7 },
    ])
  })

  it('does not pick between simultaneous analyses or duplicate values within an analysis', () => {
    const groups = bodyAnalyses([
      record('a', 'one', '2026-09-01'),
      record('b', 'two', '2026-09-01'),
      record('c', 'three', '2026-09-01'),
      record('d', 'four', '2026-09-20'),
      record('e', 'duplicate', '2026-09-25'),
      record('f', 'duplicate', '2026-09-25'),
    ])
    expect(groups.find((group) => group.records.some((item) => item.id === 'd'))?.previous).toEqual(
      {},
    )
    expect(groups[0]?.values.body_fat_percent).toBeUndefined()
    expect(groups[0]?.records).toHaveLength(2)
    expect(bodyPoints(groups, 'source-a', 'body_fat_percent')).toEqual([
      { x: Date.parse('2026-09-20T09:00:00Z'), y: 22.7 },
    ])
  })

  it('never substitutes missing or excluded values in a comparison', () => {
    const current = record('current', 'now', '2026-09-20')
    expect(bodyDelta(current, { ...current, source_id: 'other' })).toBeNull()
    expect(bodyDelta(current, { ...current, excluded: true })).toBeNull()
    expect(bodyDelta(current, { ...current, data: { value: null } })).toBeNull()
    expect(bodyDelta(current, { ...current, data: { value: '' } })).toBeNull()
    expect(bodyDelta(current, current)).toBe('0 п. п.')
  })
  it('joins raw snapshots by source and provider ID and keeps raw-only scans', () => {
    const archive = {
      id: 'archive-a',
      source_id: 'source-a',
      source_name: 'DDX',
      provider_record_id: 'scan',
      measured_at: '2026-09-10T09:00:00Z',
      revision: 2,
      received_at: '2026-10-03T09:00:00Z',
    }
    const groups = bodyAnalyses(
      [record('fat', 'scan', '2026-09-10')],
      [
        archive,
        { ...archive, id: 'archive-b', source_id: 'source-b' },
        { ...archive, id: 'raw-only', provider_record_id: 'raw' },
      ],
    )
    expect(groups).toHaveLength(3)
    expect(
      groups.find((group) => group.archive?.id === 'archive-a')?.values.body_fat_percent?.data
        .value,
    ).toBe('22.7')
    expect(groups.find((group) => group.archive?.id === 'archive-b')?.records).toEqual([])
    expect(groups.find((group) => group.archive?.id === 'raw-only')?.values).toEqual({})
  })
})
