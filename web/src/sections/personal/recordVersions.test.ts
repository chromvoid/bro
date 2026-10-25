import { describe, expect, it } from 'vitest'
import {
  RecordVersionPage,
  coherentRecordVersions,
  versionDetails,
  versionRange,
  versionValue,
} from './recordVersions'

function page() {
  return RecordVersionPage.parse({
    id: 'synthetic-record',
    kind: 'sleep',
    source_id: 'synthetic-source',
    source_name: 'Synthetic ring',
    source_status: 'disabled',
    current_revision: 2,
    count: 2,
    fingerprint: 'synthetic-lineage',
    next_cursor: 'second',
    annotation: {
      excluded: true,
      corrected: true,
      revision: 3,
      observation: { data: { value: 480 } },
    },
    versions: [
      {
        revision: 2,
        operation_id: null,
        created_at: null,
        snapshot: {
          id: 'synthetic-record',
          data: { value: null, unknown_stage: { code: 7 } },
          legacy_null: null,
          unknown: { nested: [false, null, 'retained'] },
        },
      },
    ],
  })
}

describe('exact saved record version contracts', () => {
  it('keeps unknown and null historical fields without a current value or timestamp fallback', () => {
    const parsed = page()
    const version = parsed.versions[0]!
    expect(version.snapshot).toEqual({
      id: 'synthetic-record',
      data: { value: null, unknown_stage: { code: 7 } },
      legacy_null: null,
      unknown: { nested: [false, null, 'retained'] },
    })
    expect(version.created_at).toBeNull()
    expect(versionValue(version, 'sleep', 'metric')).toBeNull()
    expect(versionDetails(version, parsed.source_name).data).toEqual(version.snapshot.data)
    expect(version.snapshot).not.toHaveProperty('excluded')
  })
  it('preserves a reported sensor range without constructing an average', () => {
    const version = {
      revision: 1,
      operation_id: null,
      created_at: null,
      snapshot: {
        value: null,
        reported_min: '94',
        reported_max: 99,
        unit: '%',
        unknown_origin: null,
      },
    }
    expect(versionValue(version, 'spo2', 'metric')).toBeNull()
    expect(versionRange(version, 'metric')).toEqual({ minimum: '94 %', maximum: '99 %' })
    expect(versionDetails(version, null).reported_max).toBe('99')
    expect(version.snapshot.reported_max).toBe(99)
  })
  it('retains unknown legacy time fields without rendering them as a fabricated instant', () => {
    for (const start_at of ['synthetic-unknown-time', '1', '2026-10-05']) {
      const version = {
        revision: 1,
        operation_id: null,
        created_at: null,
        snapshot: {
          start_at,
          end_at: null,
          received_at: 'unknown',
          timezone: null,
        },
      }
      expect(versionDetails(version, null).start_at).toBeUndefined()
      expect(versionDetails(version, null).received_at).toBeUndefined()
      expect(version.snapshot).toEqual({
        start_at,
        end_at: null,
        received_at: 'unknown',
        timezone: null,
      })
    }
  })
  it('refuses joined mixed fingerprints, context, counts and repeated revisions', () => {
    const first = page()
    const second = {
      ...first,
      next_cursor: null,
      versions: [{ ...first.versions[0]!, revision: 1 }],
    }
    expect(coherentRecordVersions([first, second])).toBe(true)
    for (const change of [
      { fingerprint: 'changed' },
      { source_id: 'other' },
      { current_revision: 3 },
      { count: 3 },
      { kind: 'steps' as const },
      { id: 'other' },
      { versions: first.versions },
    ])
      expect(coherentRecordVersions([first, { ...second, ...change }])).toBe(false)
  })
})
