export function decision() {
  return {
    status: 'available',
    selected: [{ id: 'sample', revision: 2, source_id: 'ring', source_name: 'Synthetic ring' }],
    alternatives: [{ id: 'other', source_id: 'phone', reason: 'partial_overlap' }],
    resolver_version: 'resolution-v1',
    fingerprint: 'synthetic-fingerprint',
    policy_version: 1,
    protocol: { verified: false, flags: ['protocol_unverified'] },
    coverage: { reported_seconds: 3600 },
    statistics: {
      points: { count: 0, mean: null, min: null, max: null },
      interval_means: { count: 0, mean: null, min: null, max: null },
      ranges: { count: 1, mean: null, min: 94, max: 99, covered_seconds: 3600 },
    },
  }
}
export function page() {
  return {
    from: '2026-10-01',
    to: '2026-10-05',
    timezone: 'Europe/Moscow',
    computed_at: '2026-10-05T12:00:00Z',
    daily: [
      {
        date: '2026-10-04',
        kind: 'spo2',
        status: 'available',
        value: null,
        unit: '%',
        resolution: decision(),
      },
    ],
    series: [],
    next_cursor: null,
    fingerprint: 'synthetic-page',
    policies: [],
  }
}

/** Synthetic incomplete night followed by a known nap. */
export function sleepDecision() {
  const sessions = [
    {
      id: 'night',
      source_id: 'ring',
      source_name: 'Synthetic ring',
      start_at: '2026-10-04T21:35:00Z',
      end_at: '2026-10-05T05:33:00Z',
      asleep_minutes: null,
      stages: [
        { stage: 'light', start_at: '2026-10-04T21:35:00Z', end_at: '2026-10-04T22:00:00Z' },
        { stage: 'unknown:1', start_at: '2026-10-04T22:00:00Z', end_at: '2026-10-05T05:33:00Z' },
      ],
    },
    {
      id: 'nap',
      source_id: 'ring',
      source_name: 'Synthetic ring',
      start_at: '2026-10-05T10:00:00Z',
      end_at: '2026-10-05T10:40:00Z',
      asleep_minutes: 30,
      stages: [
        { stage: 'deep', start_at: '2026-10-05T10:00:00Z', end_at: '2026-10-05T10:30:00Z' },
        { stage: 'awake', start_at: '2026-10-05T10:30:00Z', end_at: '2026-10-05T10:40:00Z' },
      ],
    },
  ]
  return {
    status: 'partial',
    selected: sessions.map((session) => ({
      id: session.id,
      source_id: session.source_id,
      source_name: session.source_name,
      start_at: session.start_at,
      end_at: session.end_at,
      contribution: session.asleep_minutes,
    })),
    alternatives: [
      { id: 'old-night', source_id: 'ring', reason: 'superseded_report', superseded_by: 'night' },
    ],
    sessions,
    resolver_version: 'resolution-v2',
    fingerprint: 'synthetic-sleep',
    quality: { flags: ['unknown_sleep_stage'] },
  }
}
