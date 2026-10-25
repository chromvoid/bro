import { describe, expect, it } from 'vitest'
import {
  Rings,
  SENSOR_METRICS,
  SensorPage,
  coherentSensorPages,
  sensorAmount,
  sensorRange,
} from './sensors'

describe('sensor history', () => {
  it('preserves null readings and reported ranges without inventing an average', () => {
    const parsed = SensorPage.parse({
      items: [
        {
          id: 'sample',
          source_id: 'source',
          source_name: 'Synthetic ring',
          external_id: 'range-1',
          metric: 'spo2',
          start_at: '2026-10-04T12:00:00Z',
          end_at: '2026-10-04T12:10:00Z',
          timezone: 'UTC',
          local_date: '2026-10-04',
          value: null,
          reported_min: 94,
          reported_max: '99',
          unit: '%',
          origin: { provider: 'herzring' },
          quality: ['protocol_unverified'],
          revision: 1,
        },
      ],
      next_cursor: 'page-2',
      fingerprint: 'synthetic-raw',
      count: 1,
      from: '2026-10-01T00:00:00Z',
      to: '2026-10-06T00:00:00Z',
    })
    expect(parsed.items[0]?.value).toBeNull()
    expect(parsed.items[0]?.reported_min).toBe('94')
    expect(parsed.items[0]?.quality).toContain('protocol_unverified')
    expect(parsed.next_cursor).toBe('page-2')
    expect(sensorAmount(null, '%')).toBeNull()
    expect(sensorAmount('', 'bpm')).toBeNull()
    expect(sensorAmount('bad', '°C')).toBeNull()
    expect(sensorAmount('36.25', '°C')).toBe('36,25 °C')
  })
  it('requires a coherent raw snapshot and refuses repeated sample identities', () => {
    const first = SensorPage.parse({
      items: [],
      next_cursor: 'next',
      fingerprint: 'synthetic-raw',
      count: 0,
      from: '2026-10-01T00:00:00Z',
      to: '2026-10-06T00:00:00Z',
    })
    expect(coherentSensorPages([first, { ...first, next_cursor: null }])).toBe(true)
    for (const change of [
      { fingerprint: 'other' },
      { count: 1 },
      { from: '2026-10-02T00:00:00Z' },
      { to: '2026-10-07T00:00:00Z' },
    ])
      expect(coherentSensorPages([first, { ...first, ...change }])).toBe(false)
  })
  it('requests an exclusive local day boundary across a daylight saving change', () => {
    const range = sensorRange('2026-10-25', '2026-10-25', 'Europe/Berlin')
    expect(range).toEqual({ from: '2026-10-25T00:00:00+02:00', to: '2026-10-26T00:00:00+01:00' })
    expect(Date.parse(range.to) - Date.parse(range.from)).toBe(25 * 60 * 60 * 1000)
  })
  it('retains spot/manual capture metadata for all five metrics without rejecting the ring listing', () => {
    const metrics = SENSOR_METRICS.map((metric) => ({
      metric,
      status: 'missing',
      last_measured_at: null,
      last_received_at: null,
      coverage: { status: 'unknown', shape: 'unknown', start_at: null, end_at: null, quality: [] },
      import_status: 'connected',
      last_capture_at: '2026-10-08T10:00:00Z',
      capture_status: 'complete',
      capture_scope: 'spot',
      capture_context: 'manual',
      reason: 'no_observation',
    }))
    const ring = {
      collector_id: 'collector',
      source_id: 'source',
      name: 'Synthetic ring',
      profile: 'qring-r20',
      generation: 1,
      enabled: true,
      last_received_at: null,
      cursor: null,
      error: null,
      status: 'connected',
      metrics,
    }
    const parsed = Rings.parse({ rings: [ring] })
    expect(parsed.rings[0]?.metrics).toEqual(metrics)
    const legacy = {
      ...ring,
      metrics: metrics.map((metric) => ({
        ...metric,
        capture_scope: undefined,
        capture_context: undefined,
      })),
    }
    expect(Rings.safeParse({ rings: [legacy] }).success).toBe(true)
    const unknown = { ...ring, metrics: [{ ...metrics[0], capture_scope: 'invented' }] }
    expect(Rings.safeParse({ rings: [unknown] }).success).toBe(false)
    for (const invalid of [
      { capture_context: 'invented' },
      { capture_date: '2026-02-30' },
      { last_capture_at: 'not-an-instant' },
    ]) {
      const candidate = { ...ring, metrics: [{ ...metrics[0], ...invalid }] }
      expect(Rings.safeParse({ rings: [candidate] }).success).toBe(false)
    }
    for (const context of ['background', 'running', 'workout', 'recovery', 'manual']) {
      const candidate = {
        ...ring,
        metrics: [
          {
            ...metrics[0],
            capture_context: context,
            capture_scope: context === 'manual' ? 'spot' : 'history',
          },
        ],
      }
      expect(Rings.safeParse({ rings: [candidate] }).success).toBe(true)
    }
  })
})
