import { describe, expect, it } from 'vitest'
import { History, Metric, Observation, SourceMetric, type BodyKind } from './api'
import {
  dashboardBody,
  dashboardBodyChanges,
  dashboardBodyDelta,
  previousDashboardBody,
} from './dashboardBody'

function record(
  kind: BodyKind,
  value: string | null,
  scan = 'latest',
  source = 'inbody',
  at = '2026-10-02T09:00:00Z',
) {
  return Observation.parse({
    id: `${source}-${scan}-${kind}`,
    source_id: source,
    source_name: source,
    kind,
    start_at: at,
    end_at: at,
    timezone: 'UTC',
    method: 'device_report',
    origin: { record_id: scan },
    data: { value },
    revision: 1,
  })
}
function metric(entry: Observation) {
  return Metric.parse({
    kind: entry.kind,
    status: 'available',
    value: entry.data.value,
    unit: '%',
    records: [entry],
  })
}
function history(records: Observation[]) {
  return History.parse({ from: '2025-10-06', to: '2026-10-05', records, daily: [] })
}

describe('dashboard body scan selection', () => {
  it('uses the preferred overview record instead of a newer foreign source', () => {
    const selected = record('skeletal_muscle_mass', '34')
    const snapshot = dashboardBody(
      { skeletal_muscle_mass: metric(selected) },
      [],
      history([
        selected,
        record('body_fat_percent', '22'),
        record('skeletal_muscle_mass', '37', 'foreign', 'other', '2026-10-03T09:00:00Z'),
      ]),
      '2026-10-05',
      'UTC',
    )
    expect(snapshot.current?.sourceId).toBe('inbody')
    expect(snapshot.current?.values.body_fat_percent?.data.value).toBe('22')
  })

  it('does not merge a previous fat reading into a newer scan or join timestamps alone', () => {
    const selected = record('skeletal_muscle_mass', '34')
    const fat = record('body_fat_percent', '22', 'old')
    const snapshot = dashboardBody(
      { skeletal_muscle_mass: metric(selected), body_fat_percent: metric(fat) },
      [],
      history([selected, fat]),
      '2026-10-05',
      'UTC',
    )
    expect(snapshot.current?.values.body_fat_percent).toBeUndefined()
    expect(snapshot.current?.records.map((entry) => entry.id)).toEqual([selected.id])
  })

  it('keeps a preferred caliper value out of the device figure, including corrected estimates', () => {
    const caliper = record('body_fat_percent', '18', 'folds', 'caliper')
    caliper.method = 'self_report'
    caliper.corrected = true
    caliper.original = {
      data: { value: '19' },
      origin: { provider: 'caliper', record_id: 'folds' },
    }
    const scan = record('body_fat_percent', '22')
    const sources = [
      SourceMetric.parse({ ...metric(scan), source_id: 'inbody' }),
      SourceMetric.parse({ ...metric(caliper), source_id: 'caliper' }),
    ]
    const snapshot = dashboardBody(
      { body_fat_percent: metric(caliper) },
      sources,
      history([scan, caliper]),
      '2026-10-05',
      'UTC',
    )
    expect(snapshot.current?.sourceId).toBe('inbody')
    expect(
      dashboardBody(
        { body_fat_percent: metric(caliper) },
        [],
        history([caliper]),
        '2026-10-05',
        'UTC',
      ).current,
    ).toBeUndefined()
  })

  it('preserves zero and missing axes without using future records at a timezone boundary', () => {
    const selected = record('body_fat_percent', '0', 'zero', 'inbody', '2026-10-04T21:30:00Z')
    const future = record('skeletal_muscle_mass', '36', 'future', 'inbody', '2026-10-05T21:30:00Z')
    const snapshot = dashboardBody(
      { body_fat_percent: metric(selected) },
      [],
      history([selected, future]),
      '2026-10-05',
      'Europe/Moscow',
    )
    expect(snapshot.current?.values.body_fat_percent?.data.value).toBe('0')
    expect(snapshot.current?.values.skeletal_muscle_mass).toBeUndefined()
    expect(snapshot.analyses).toHaveLength(1)
  })

  it('does not select a conflicting metric or resurrect an excluded history record', () => {
    const selected = record('body_fat_percent', '22')
    const conflict = metric(selected)
    conflict.status = 'conflict'
    conflict.value = null
    expect(
      dashboardBody({ body_fat_percent: conflict }, [], history([selected]), '2026-10-05', 'UTC')
        .current,
    ).toBeUndefined()
    const excluded = { ...selected, override_revision: 1, excluded: true }
    const snapshot = dashboardBody(
      { body_fat_percent: metric(selected) },
      [],
      history([excluded]),
      '2026-10-05',
      'UTC',
    )
    expect(snapshot.current?.values.body_fat_percent).toBeUndefined()
  })

  it('retains a newer overview correction when source metrics and history are stale', () => {
    const old = record('body_fat_percent', '22')
    const corrected = {
      ...old,
      override_revision: 2,
      corrected: true,
      data: { value: '19' },
      original: { data: old.data, origin: old.origin },
    }
    const source = SourceMetric.parse({ ...metric(old), source_id: 'inbody' })
    const snapshot = dashboardBody(
      { body_fat_percent: metric(corrected) },
      [source],
      history([old]),
      '2026-10-05',
      'UTC',
    )
    expect(snapshot.current?.values.body_fat_percent?.data.value).toBe('19')
    expect(snapshot.current?.values.body_fat_percent?.corrected).toBe(true)
    expect(snapshot.current?.values.body_fat_percent?.override_revision).toBe(2)
  })

  it('retains a newer overview exclusion even when stale history still reports a value', () => {
    const old = record('body_fat_percent', '22')
    const muscle = record('skeletal_muscle_mass', '34')
    const excluded = { ...old, override_revision: 2, excluded: true }
    const snapshot = dashboardBody(
      { body_fat_percent: metric(excluded), skeletal_muscle_mass: metric(muscle) },
      [],
      history([old, muscle]),
      '2026-10-05',
      'UTC',
    )
    expect(snapshot.current?.values.body_fat_percent).toBeUndefined()
    expect(snapshot.current?.records.find((entry) => entry.id === old.id)?.excluded).toBe(true)
  })

  it('uses a newer history correction, but preserves overview when both versions are equal', () => {
    const old = record('body_fat_percent', '22')
    const corrected = { ...old, override_revision: 1, corrected: true, data: { value: '20' } }
    const fresh = dashboardBody(
      { body_fat_percent: metric(old) },
      [],
      history([corrected]),
      '2026-10-05',
      'UTC',
    )
    expect(fresh.current?.values.body_fat_percent?.data.value).toBe('20')
    const equal = dashboardBody(
      { body_fat_percent: metric(old) },
      [],
      history([{ ...old, data: { value: '99' } }]),
      '2026-10-05',
      'UTC',
    )
    expect(equal.current?.values.body_fat_percent?.data.value).toBe('22')
  })

  it('orders source revision before override revision rather than by query arrival', () => {
    const latest = { ...record('body_fat_percent', '22'), revision: 2 }
    const stale = { ...latest, revision: 1, override_revision: 9, data: { value: '99' } }
    const snapshot = dashboardBody(
      { body_fat_percent: metric(latest) },
      [],
      history([stale]),
      '2026-10-05',
      'UTC',
    )
    expect(snapshot.current?.values.body_fat_percent?.data.value).toBe('22')
    const fresh = dashboardBody(
      { body_fat_percent: metric(stale) },
      [],
      history([latest]),
      '2026-10-05',
      'UTC',
    )
    expect(fresh.current?.values.body_fat_percent?.revision).toBe(2)
  })

  it('compares strictly earlier same-source same-method scans and rejects ambiguous endpoints', () => {
    const selected = record('body_fat_percent', '22')
    const old = record('body_fat_percent', '23', 'old', 'inbody', '2026-09-02T09:00:00Z')
    const differentMethod = { ...old, id: 'different-method', method: 'derived' }
    expect(dashboardBodyDelta(selected, differentMethod)).toBeNull()
    expect(dashboardBodyDelta(selected, old)).toBe('−1 п. п.')
    const snapshot = dashboardBody(
      { body_fat_percent: metric(selected) },
      [],
      history([selected, old]),
      '2026-10-05',
      'UTC',
    )
    expect(
      previousDashboardBody(snapshot.analyses, snapshot.current)?.values.body_fat_percent?.id,
    ).toBe(old.id)
    const duplicate = record('body_fat_percent', '24', 'duplicate', 'inbody', old.start_at)
    const ambiguous = dashboardBody(
      { body_fat_percent: metric(selected) },
      [],
      history([selected, old, duplicate]),
      '2026-10-05',
      'UTC',
    )
    expect(previousDashboardBody(ambiguous.analyses, ambiguous.current)).toBeUndefined()
  })

  it('shares a previous caption only for compatible values from one identified scan', () => {
    const fat = record('body_fat_percent', '22')
    const muscle = record('skeletal_muscle_mass', '34')
    const oldFat = record('body_fat_percent', '23', 'old', 'inbody', '2026-09-02T09:00:00Z')
    const oldMuscle = record('skeletal_muscle_mass', '32', 'old', 'inbody', oldFat.start_at)
    const snapshot = dashboardBody(
      { body_fat_percent: metric(fat), skeletal_muscle_mass: metric(muscle) },
      [],
      history([fat, muscle, oldFat, oldMuscle]),
      '2026-10-05',
      'UTC',
    )
    const comparison = dashboardBodyChanges(snapshot.current, snapshot.analyses)
    expect(comparison.changes).toMatchObject([
      { kind: 'body_fat_percent', value: '−1 п. п.', at: oldFat.start_at },
      { kind: 'skeletal_muscle_mass', value: '+2 кг', at: oldMuscle.start_at },
    ])
    expect(comparison.sharedPrevious?.records.map((entry) => entry.id)).toEqual(
      expect.arrayContaining([oldFat.id, oldMuscle.id]),
    )
  })

  it('keeps equal-time previous values from different scan identities separate', () => {
    const fat = record('body_fat_percent', '22')
    const muscle = record('skeletal_muscle_mass', '34')
    const oldFat = record('body_fat_percent', '23', 'fat-only', 'inbody', '2026-09-02T09:00:00Z')
    const oldMuscle = record('skeletal_muscle_mass', '32', 'muscle-only', 'inbody', oldFat.start_at)
    const snapshot = dashboardBody(
      { body_fat_percent: metric(fat), skeletal_muscle_mass: metric(muscle) },
      [],
      history([fat, muscle, oldFat, oldMuscle]),
      '2026-10-05',
      'UTC',
    )
    const comparison = dashboardBodyChanges(snapshot.current, snapshot.analyses)
    expect(comparison.changes).toHaveLength(2)
    expect(comparison.changes[0]?.previousAnalysisId).not.toBe(
      comparison.changes[1]?.previousAnalysisId,
    )
    expect(comparison.sharedPrevious).toBeUndefined()
  })

  it('retains metric-specific endpoints and rejects an incompatible algorithm', () => {
    const fat = record('body_fat_percent', '22')
    const muscle = record('skeletal_muscle_mass', '34')
    const oldFat = record('body_fat_percent', '23', 'old', 'inbody', '2026-09-02T09:00:00Z')
    const oldMuscle = record(
      'skeletal_muscle_mass',
      '32',
      'older',
      'inbody',
      '2026-08-02T09:00:00Z',
    )
    const snapshot = dashboardBody(
      { body_fat_percent: metric(fat), skeletal_muscle_mass: metric(muscle) },
      [],
      history([fat, muscle, oldFat, oldMuscle]),
      '2026-10-05',
      'UTC',
    )
    const comparison = dashboardBodyChanges(snapshot.current, snapshot.analyses)
    expect(comparison.changes.map((entry) => entry.at)).toEqual([
      oldFat.start_at,
      oldMuscle.start_at,
    ])
    expect(comparison.sharedPrevious).toBeUndefined()
    const earlier = snapshot.current!.previous.skeletal_muscle_mass!
    earlier.record = { ...earlier.record, algorithm_version: 'different-version' }
    expect(
      dashboardBodyChanges(snapshot.current, snapshot.analyses).changes.map((entry) => entry.kind),
    ).toEqual(['body_fat_percent'])
    expect(dashboardBodyChanges(undefined, snapshot.analyses).changes).toEqual([])
  })
})
