import { describe, expect, it } from 'vitest'
import {
  ActivityReport,
  activityReportKey,
  activityReportSnapshot,
  archiveWorkout,
  coherentReportPages,
} from './activityReports'

const report = (revision: number, sourceVersion = revision) =>
  ActivityReport.parse({
    id: 'report',
    source_id: 'ring-a',
    source_name: 'Ring A',
    revision,
    source_version: sourceVersion,
    kind: 'running_workouts',
    shape: 'workout_report',
    local_date: '2026-10-08',
    timezone: 'UTC',
    observed_at: '2026-10-08T23:59:00Z',
    start_at: '2026-10-08T23:50:00Z',
    end_at: '2026-10-09T00:10:00Z',
    data: { sport_type: 'running', duration_seconds: null, steps: null, distance_meters: null },
    origin: {},
    quality: [],
    is_current: revision === 2,
    current_revision: 2,
    annotation_revision: 3,
    excluded: true,
  })

describe('original activity report archive', () => {
  it('preserves saved JSON types, absent fields and unknown extensions separately from current state', () => {
    const saved = ActivityReport.parse({
      ...report(1),
      source_status: 'paused',
      annotation_id: 'current-annotation',
      original_extension: { nullable: null },
      data: {
        sport_type: 'boxing',
        distance_meters: '00100.00',
        sport_plan: {
          requested_id: 20,
          name: 'boxing',
          family: 'combat',
          candidate_wire_mode: 20,
          actual_wire_mode: null,
          qualification: 'protocol_unverified',
          evidence_ref: 'synthetic',
          unknown_extension: { marker: 'kept' },
        },
        unknown_measurement: { nullable: null, text: '003.00' },
      },
    })
    const before = JSON.stringify(saved)
    const snapshot = activityReportSnapshot(saved)
    expect(snapshot.data).toEqual(saved.data)
    expect(snapshot.original_extension).toEqual({ nullable: null })
    expect(snapshot.data).not.toHaveProperty('duration_seconds')
    expect(snapshot.data).not.toHaveProperty('steps')
    expect(snapshot).not.toHaveProperty('source_name')
    expect(snapshot).not.toHaveProperty('source_status')
    expect(snapshot).not.toHaveProperty('is_current')
    expect(snapshot).not.toHaveProperty('current_revision')
    expect(snapshot).not.toHaveProperty('annotation_id')
    expect(snapshot).not.toHaveProperty('annotation_revision')
    expect(snapshot).not.toHaveProperty('excluded')
    expect(snapshot).not.toHaveProperty('resolution_disposition')
    expect(archiveWorkout(saved)?.data.distance_meters).toBe(100)
    expect(archiveWorkout(saved)?.data.sport_plan).not.toHaveProperty('unknown_extension')
    expect(JSON.stringify(saved)).toBe(before)
  })
  it('keeps old and excluded versions addressable without a selected resolver entry', () => {
    const old = report(1)
    const current = report(2)
    expect(activityReportKey(old)).not.toBe(activityReportKey(current))
    expect(archiveWorkout(old)).toMatchObject({
      id: 'report',
      date: '2026-10-08',
      data: { duration_seconds: null, steps: null, distance_meters: null },
    })
    expect(archiveWorkout(current)?.excluded).toBe(true)
    expect(archiveWorkout(old)).not.toHaveProperty('resolution')
  })
  it('does not project a counter snapshot into a workout and rejects mixed archive decisions', () => {
    expect(archiveWorkout({ ...report(1), shape: 'day_counter_snapshot' })).toBeNull()
    const page = {
      from: '2026-10-08',
      to: '2026-10-09',
      items: [report(1)],
      next_cursor: null,
      fingerprint: 'old',
    }
    expect(coherentReportPages([page, { ...page, fingerprint: 'new' }])).toBe(false)
  })
})

it('retains generic requested/actual sport identity and pauses without projecting running', () => {
  const generic = ActivityReport.parse({
    ...report(1),
    kind: 'workouts',
    data: {
      sport_type: 'boxing',
      duration_seconds: 1200,
      active_duration_seconds: 900,
      sport_plan: {
        requested_id: 20,
        name: 'boxing',
        family: 'combat',
        candidate_wire_mode: 20,
        actual_wire_mode: null,
        qualification: 'protocol_unverified',
        evidence_ref: 'synthetic',
      },
      pauses: [{ start_at: '2026-10-08T23:55:00Z', end_at: '2026-10-09T00:00:00Z' }],
    },
  })
  expect(archiveWorkout(generic)?.data).toMatchObject({
    sport_type: 'boxing',
    duration_seconds: 1200,
    active_duration_seconds: 900,
    steps: null,
    distance_meters: null,
    sport_plan: { actual_wire_mode: null },
    pauses: generic.data.pauses,
  })
})
