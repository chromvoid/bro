import { describe, expect, it } from 'vitest'
import { Profile } from './api'
import { CaliperHistory, CaliperMeasurement, caliperCompletion, missingCaliperKey } from './caliper'

const measurement = {
  id: 'synthetic-caliper',
  source_id: 'synthetic-caliper-source',
  measured_at: '2026-10-05T06:00:00Z',
  timezone: 'Europe/Moscow',
  folds_mm: { abdomen: 20, suprailiac: '15.5' },
  gender: 'male',
  age_years: 35,
  body_fat_percent: null,
  calculation: {
    status: 'incomplete',
    method: 'jackson_pollock_3_siri',
    missing_fields: ['chest', 'thigh'],
    algorithm_version: 'jackson_pollock_3_siri_v1',
  },
  revision: 1,
  body_fat_record_id: null,
}

describe('caliper contract', () => {
  it('accepts incomplete raw readings without inventing an estimate', () => {
    const record = CaliperMeasurement.parse(measurement)
    expect(record.folds_mm).toEqual({ abdomen: '20', suprailiac: '15.5' })
    expect(record.body_fat_percent).toBeNull()
    expect(record.calculation.missing_fields).toEqual(['chest', 'thigh'])
  })

  it('retains the server result, metadata and formula version', () => {
    const record = CaliperMeasurement.parse({
      ...measurement,
      body_fat_percent: 18.515298680869137,
      calculation: { ...measurement.calculation, status: 'calculated', missing_fields: [] },
      body_fat_record_id: 'synthetic-fat-record',
    })
    expect(record.body_fat_percent).toBe('18.515298680869137')
    expect(record.age_years).toBe(35)
    expect(record.calculation.algorithm_version).toBe('jackson_pollock_3_siri_v1')
    expect(CaliperHistory.parse({ records: [record], truncated: true, limit: 100 }).truncated).toBe(
      true,
    )
  })

  it('rejects unknown fold sites and translates only supported missing fields', () => {
    expect(
      CaliperMeasurement.safeParse({ ...measurement, folds_mm: { waist: '20' } }).success,
    ).toBe(false)
    expect(missingCaliperKey('suprailiac')).toBe('personal.caliper.sites.suprailiac')
    expect(missingCaliperKey('age_years')).toBe('personal.caliper.age')
    expect(missingCaliperKey('gender')).toBe('personal.caliper.gender')
    expect(missingCaliperKey('archive.title')).toBeNull()
  })
})

describe('explicit caliper completion availability', () => {
  const profile = Profile.parse({
    user_id: 'owner',
    timezone: 'UTC',
    birth_date: '1990-01-01',
    gender: 'female',
    revision: 3,
  })
  const incomplete = CaliperMeasurement.parse({
    ...measurement,
    folds_mm: { chest: '15', abdomen: '25', thigh: '20' },
    age_years: null,
    calculation: { ...measurement.calculation, missing_fields: ['age_years'] },
  })

  it('uses saved gender instead of current profile gender and does not require height or weight', () => {
    expect(caliperCompletion(incomplete, profile)).toEqual({
      metadata: ['age_years'],
      available: true,
      missingFolds: [],
    })
    expect(incomplete.age_years).toBeNull()
    expect(incomplete.gender).toBe('male')
  })

  it('requires every missing demographic fact to be available, allowing still-missing folds', () => {
    const record = CaliperMeasurement.parse({
      ...incomplete,
      gender: null,
      folds_mm: { abdomen: '25' },
      calculation: { ...incomplete.calculation, missing_fields: ['gender', 'age_years'] },
    })
    expect(caliperCompletion(record, { ...profile, birth_date: null }).available).toBe(false)
    expect(caliperCompletion(record, { ...profile, gender: null }).available).toBe(false)
    expect(caliperCompletion(record, profile)).toEqual({
      metadata: ['gender', 'age_years'],
      available: true,
      missingFolds: ['triceps', 'suprailiac', 'thigh'],
    })
  })

  it('derives age on the saved local date and rejects future or out-of-storage-range DOB', () => {
    const record = {
      ...incomplete,
      measured_at: '2024-01-01T00:30:00Z',
      timezone: 'America/Los_Angeles',
    }
    expect(caliperCompletion(record, { ...profile, birth_date: '1903-01-01' }).available).toBe(true)
    expect(
      caliperCompletion({ ...record, timezone: 'UTC' }, { ...profile, birth_date: '1903-01-01' })
        .available,
    ).toBe(false)
    expect(caliperCompletion(record, { ...profile, birth_date: '2024-01-01' }).available).toBe(
      false,
    )
    expect(caliperCompletion(record, { ...profile, birth_date: 'invalid' }).available).toBe(false)
  })

  it('offers metadata completion outside study age while never changing finished snapshots', () => {
    expect(
      caliperCompletion(
        {
          ...incomplete,
          age_years: 80,
          gender: null,
          calculation: { ...incomplete.calculation, missing_fields: ['gender'] },
        },
        profile,
      ).available,
    ).toBe(true)
    expect(
      caliperCompletion(
        CaliperMeasurement.parse({
          ...incomplete,
          body_fat_percent: '18.5',
          calculation: { ...incomplete.calculation, status: 'calculated' },
        }),
        profile,
      ).available,
    ).toBe(false)
    expect(
      caliperCompletion(
        { ...incomplete, calculation: { ...incomplete.calculation, status: 'unsupported' } },
        profile,
      ).available,
    ).toBe(false)
    expect(caliperCompletion(CaliperMeasurement.parse(measurement), profile).available).toBe(false)
  })
})
