import { describe, expect, it } from 'vitest'
import {
  BODY_KINDS,
  signedKilograms,
  DAILY_KINDS,
  History,
  Observation,
  Overview,
  amountText,
  hasTarget,
  observationOf,
  originOf,
  weightPoints,
  type Kind,
} from './api'

const weighing = Observation.parse({
  id: 'r1',
  source_id: null,
  kind: 'weight',
  start_at: '2026-09-23T06:15:00+00:00',
  end_at: '2026-09-23T06:15:00+00:00',
  timezone: 'Europe/Belgrade',
  method: 'self_report',
  data: { value: '72.4' },
  revision: 1,
  override_revision: 0,
  corrected: false,
})

describe('personal values', () => {
  it('shows amounts with their units and never invents a zero', () => {
    expect(amountText('weight', '72.4')).toBe('72,4 кг')
    expect(amountText('sleep', '440')).toBe('7 ч 20 мин')
    expect(amountText('sleep', '35')).toBe('35 мин')
    expect(amountText('steps', '8000')).toBe('8\u00a0000 шагов')
    expect(amountText('active_energy', '113.719')).toBe('114 ккал')
    expect(amountText('distance', '1582.75')).toBe('1\u00a0582,75 м')
    expect(amountText('active_energy', null)).toBeNull()
    expect(amountText('weight', null)).toBeNull()
    expect(amountText('body_fat_percent', '')).toBeNull()
    expect(amountText('body_fat_percent', 'invalid')).toBeNull()
  })

  it.each<[Kind, string, string]>([
    ['body_fat_percent', '22.7', '22,7 %'],
    ['body_fat_mass', '16.25', '16,25 кг'],
    ['skeletal_muscle_mass', '29.6', '29,6 кг'],
    ['fat_free_mass', '51.3', '51,3 кг'],
    ['body_water', '37.8', '37,8 л'],
    ['body_protein', '10.1', '10,1 кг'],
    ['body_minerals', '3.43', '3,43 кг'],
    ['bmi', '23.9', '23,9 кг/м²'],
    ['basal_metabolic_rate', '1542', '1\u00a0542 ккал/день'],
    ['visceral_fat_level', '8', '8 ур.'],
  ])('formats %s in its reported unit', (kind, value, expected) => {
    expect(amountText(kind, value)).toBe(expected)
    expect(amountText(kind, null)).toBeNull()
    expect(hasTarget(kind)).toBe(false)
  })

  it('accepts older overviews without composition and validates new metric amounts', () => {
    const emptyMetric = { status: 'no_data', value: null, unit: 'min' }
    const overview = {
      date: '2026-09-23',
      timezone: 'UTC',
      profile: { user_id: 'synthetic-user', timezone: 'UTC', revision: 1 },
      metrics: {
        weight: { ...emptyMetric, kind: 'weight', unit: 'kg' },
        ...Object.fromEntries(DAILY_KINDS.map((kind) => [kind, { ...emptyMetric, kind }])),
      },
      preferences: {},
      sources: [],
    }
    expect(Overview.parse(overview).metrics.body_fat_percent).toBeUndefined()
    const withComposition = Overview.parse({
      ...overview,
      metrics: {
        ...overview.metrics,
        body_fat_percent: {
          kind: 'body_fat_percent',
          status: 'available',
          value: 22.7,
          unit: '%',
        },
      },
    })
    expect(withComposition.metrics.body_fat_percent?.value).toBe('22.7')
  })

  it('names the origin, a correction first', () => {
    expect(originOf(weighing)).toBe('manual')
    expect(originOf({ ...weighing, corrected: true })).toBe('corrected')
    expect(originOf({ ...weighing, method: 'derived' })).toBe('estimate')
    expect(originOf({ ...weighing, method: 'device_report' })).toBe('device')
  })

  it('accepts numbers where the server sends them', () => {
    const steps = Observation.parse({ ...weighing, kind: 'steps', data: { value: 8000 } })
    expect(steps.data.value).toBe('8000')
  })

  it('builds a correction that replaces the whole override', () => {
    expect(
      observationOf(weighing, { value: '72.1', start_at: '2026-09-23T08:00:00+02:00' }),
    ).toEqual({
      kind: 'weight',
      start_at: '2026-09-23T08:00:00+02:00',
      end_at: '2026-09-23T08:00:00+02:00',
      timezone: 'Europe/Belgrade',
      method: 'self_report',
      data: { value: '72.1' },
    })
    const steps = { ...weighing, kind: 'steps' as const, end_at: '2026-09-23T20:00:00Z' }
    expect(observationOf(steps, { value: '9000' }).data.value).toBe(9000)
    expect(observationOf(steps).end_at).toBe('2026-09-23T20:00:00Z')
  })

  it.each(BODY_KINDS)('keeps %s corrections as a single measurement instant', (kind) => {
    const record = Observation.parse({ ...weighing, kind, data: { value: '23.75' } })
    const corrected = observationOf(record, {
      value: '24.25',
      start_at: '2026-09-22T10:00:00+02:00',
      end_at: '2026-09-23T12:00:00+02:00',
    })
    expect(corrected).toEqual({
      kind,
      start_at: '2026-09-22T10:00:00+02:00',
      end_at: '2026-09-22T10:00:00+02:00',
      timezone: 'Europe/Belgrade',
      method: 'self_report',
      data: { value: '24.25' },
    })
  })

  it('charts weighed days only, oldest first', () => {
    const history = History.parse({
      from: '2026-09-20',
      to: '2026-09-23',
      records: [],
      daily: [
        { date: '2026-09-23', kind: 'weight', status: 'available', value: '72.1' },
        { date: '2026-09-21', kind: 'weight', status: 'available', value: '72.6' },
        { date: '2026-09-22', kind: 'weight', status: 'no_data', value: null },
        { date: '2026-09-22', kind: 'sleep', status: 'available', value: 420 },
      ],
    })
    expect(weightPoints(history)).toEqual([
      { x: Date.parse('2026-09-21T00:00:00Z'), y: 72.6 },
      { x: Date.parse('2026-09-23T00:00:00Z'), y: 72.1 },
    ])
  })
})

describe('weight change', () => {
  it('signs a change with the typographic minus and rounds it to 100 g', () => {
    expect(signedKilograms(-1.54, 'кг')).toBe('−1,5 кг')
    expect(signedKilograms(0.26, 'кг')).toBe('+0,3 кг')
    expect(signedKilograms(0.02, 'кг')).toBe('0 кг')
  })
})
