import { describe, expect, it } from 'vitest'
import { Observation, Profile, type Kind } from './api'
import { bodyAnalyses } from './body'
import {
  BODY_REGIONS,
  bodyVisual,
  comparisonScans,
  interpolateVisual,
  neutralBodyVisual,
  visualScale,
} from './bodyVisual'

const profile = Profile.parse({
  user_id: 'owner',
  timezone: 'UTC',
  height_cm: '200',
  gender: 'female',
  gender_origin: 'manual',
  revision: 1,
})
function record(
  kind: Kind,
  value: string | null,
  scan = 'new',
  source = 'source',
  at = '2026-10-02T12:00:00Z',
) {
  return Observation.parse({
    id: `${source}-${scan}-${kind}`,
    kind,
    source_id: source,
    start_at: at,
    end_at: at,
    timezone: 'UTC',
    method: 'device_report',
    origin: { record_id: scan },
    data: { value },
    revision: 1,
  })
}
const scan = (records: Observation[]) => bodyAnalyses(records)[0]!

describe('versioned body visual mapping', () => {
  it('maps drawing anchors and clamps outside values without medical targets', () => {
    expect([0, 10, 25, 40, 60].map((value) => visualScale(value, [10, 25, 40]))).toEqual([
      0, 0, 0.5, 1, 1,
    ])
    expect(
      bodyVisual(
        scan([record('body_fat_percent', '25'), record('skeletal_muscle_mass', '36')]),
        profile,
      ).visual,
    ).toEqual({
      ...neutralBodyVisual('female'),
      fat: 0.5,
      muscle: 0.5,
    })
    expect(bodyVisual(scan([record('skeletal_muscle_mass', '24')]), profile).visual.muscle).toBe(0)
    expect(bodyVisual(scan([record('skeletal_muscle_mass', '48')]), profile).visual.muscle).toBe(1)
  })
  it('reports limits separately from the calibrated figure, using height-adjusted SMM', () => {
    const mapped = bodyVisual(
      scan([record('body_fat_percent', '8'), record('skeletal_muscle_mass', '60')]),
      profile,
    )
    expect(mapped.calibrationBoundaries).toEqual({ fat: 'below', muscle: 'above' })
    expect(mapped.visual).toEqual({ ...neutralBodyVisual('female'), fat: 0, muscle: 1 })
    for (const [fat, muscle] of [
      ['10', '24'],
      ['40', '48'],
    ] as const) {
      expect(
        bodyVisual(
          scan([record('body_fat_percent', fat), record('skeletal_muscle_mass', muscle)]),
          profile,
        ).calibrationBoundaries,
      ).toEqual({ fat: null, muscle: null })
    }
    expect(
      bodyVisual(
        scan([record('body_fat_percent', '55'), record('skeletal_muscle_mass', '20')]),
        profile,
      ).calibrationBoundaries,
    ).toEqual({ fat: 'above', muscle: 'below' })
    const taller = bodyVisual(scan([record('skeletal_muscle_mass', '36')]), profile)
    const shorter = bodyVisual(scan([record('skeletal_muscle_mass', '36')]), {
      ...profile,
      height_cm: '150',
    })
    expect(taller.calibrationBoundaries.muscle).toBeNull()
    expect(shorter.calibrationBoundaries.muscle).toBe('above')
  })
  it('uses corrected scalars and current profile, without mutating profile or carrying prior values', () => {
    const source = record('body_fat_percent', '30')
    const corrected = {
      ...source,
      corrected: true,
      data: { value: '10' },
      original: { data: { value: '30' } },
    }
    const before = JSON.stringify(profile)
    const mapped = bodyVisual(scan([corrected, record('skeletal_muscle_mass', '36')]), profile)
    expect(mapped.visual.fat).toBe(0)
    expect(bodyVisual(scan([record('body_water', '40')]), profile).visual).toMatchObject({
      fat: null,
      muscle: null,
    })
    expect(JSON.stringify(profile)).toBe(before)
    expect(
      bodyVisual(scan([record('skeletal_muscle_mass', '36')]), { ...profile, height_cm: '150' })
        .visual.muscle,
    ).toBe(1)
  })
  it('keeps missing height and unset gender explicit; never infers a gender or muscle axis', () => {
    const unset = { ...profile, height_cm: null, gender: null, gender_origin: 'unset' as const }
    const result = bodyVisual(
      scan([record('body_fat_percent', '0'), record('skeletal_muscle_mass', '36')]),
      unset,
    )
    expect(result.visual).toMatchObject({ template: 'male', fat: 0, muscle: null })
    expect(result.unavailable.muscle).toBe('height_missing')
    expect(result.calibrationBoundaries).toEqual({ fat: 'below', muscle: null })
    expect(result.genderMissing).toBe(true)
    expect(unset.gender).toBeNull()
  })
  it('neutralizes conflicts, excluded, invalid and missing measurements independently', () => {
    const first = record('body_fat_percent', '20')
    const conflict = scan([first, { ...first, id: 'duplicate', data: { value: '25' } }])
    expect(bodyVisual(conflict, profile).unavailable.fat).toBe('conflict')
    expect(bodyVisual(conflict, profile).visual.fat).toBeNull()
    expect(bodyVisual(conflict, profile).calibrationBoundaries.fat).toBeNull()
    for (const value of [null, 'NaN', 'Infinity', '-1', '101']) {
      const mapped = bodyVisual(scan([record('body_fat_percent', value)]), profile)
      expect(mapped.visual.fat).toBeNull()
      expect(mapped.unavailable.fat).toBe('invalid')
      expect(mapped.calibrationBoundaries.fat).toBeNull()
    }
    expect(bodyVisual(scan([{ ...first, excluded: true }]), profile).unavailable.fat).toBe(
      'excluded',
    )
    expect(bodyVisual(scan([record('body_water', '42')]), profile).unavailable.fat).toBe('missing')
    expect(
      bodyVisual(scan([{ ...first, excluded: true }]), profile).calibrationBoundaries.fat,
    ).toBeNull()
    expect(bodyVisual(scan([record('body_water', '42')]), profile).calibrationBoundaries).toEqual({
      fat: null,
      muscle: null,
    })
  })
  it('allows only valid earlier scans of the same source, with at least one shared visual axis', () => {
    const rows = [
      record('body_fat_percent', '25'),
      record('body_fat_percent', '20', 'old', 'source', '2026-10-01T12:00:00Z'),
      record('body_fat_percent', '15', 'other', 'other', '2026-09-20T12:00:00Z'),
      record('body_fat_percent', '30', 'same-time'),
      record('body_fat_percent', '30', 'future', 'source', '2026-10-03T12:00:00Z'),
      record('body_water', '40', 'raw-only', 'source', '2026-09-01T12:00:00Z'),
    ]
    const analyses = bodyAnalyses(rows)
    const selected = analyses.find((item) =>
      item.records.some((r) => r.origin?.record_id === 'new'),
    )!
    expect(comparisonScans(analyses, selected, profile).map((item) => item.id)).toEqual([
      JSON.stringify(['source', 'old']),
    ])
    expect(comparisonScans(analyses, { ...selected, sourceId: null }, profile)).toEqual([])
  })
  it('scrubs only available pairs; missing endpoints hold selected state with no fabricated values', () => {
    const previous = {
      ...neutralBodyVisual('female'),
      fat: 0,
      muscle: null,
    }
    const selected = { ...previous, fat: 1, muscle: 0.75 }
    expect(interpolateVisual(previous, selected, 0)).toEqual({ ...selected, fat: 0 })
    expect(interpolateVisual(previous, selected, 0.5)).toEqual({ ...selected, fat: 0.5 })
    expect(interpolateVisual(previous, selected, 1)).toEqual(selected)
    expect(interpolateVisual(previous, { ...selected, fat: null }, 0.5).fat).toBeNull()
    expect(interpolateVisual(previous, selected, Number.NaN)).toEqual(selected)
  })
  it('is deterministic and monotonic while changing each measured axis independently', () => {
    let previousFat = -1
    let previousMuscle = -1
    for (let step = 0; step <= 100; step++) {
      const fatScan = scan([
        record('body_fat_percent', String(step)),
        record('skeletal_muscle_mass', '36'),
      ])
      const fat = bodyVisual(fatScan, profile)
      const muscle = bodyVisual(
        scan([record('body_fat_percent', '25'), record('skeletal_muscle_mass', String(step))]),
        profile,
      )
      expect(bodyVisual(fatScan, profile)).toEqual(fat)
      expect(fat.visual.fat).toBeGreaterThanOrEqual(previousFat)
      expect(muscle.visual.muscle).toBeGreaterThanOrEqual(previousMuscle)
      expect(fat.visual.muscle).toBe(0.5)
      expect(muscle.visual.fat).toBe(0.5)
      previousFat = fat.visual.fat!
      previousMuscle = muscle.visual.muscle!
    }
  })
  it('never derives local tissue distribution from totals or unrelated measurements', () => {
    const result = bodyVisual(
      scan([
        record('body_fat_percent', '25'),
        record('skeletal_muscle_mass', '36'),
        record('body_water', '48'),
        record('visceral_fat_level', '12'),
        record('fat_free_mass', '60'),
      ]),
      profile,
    )
    for (const region of BODY_REGIONS) {
      expect(result.visual.regions[region]).toEqual({
        fat: null,
        lean: null,
        unavailable: 'unverified_contract',
      })
    }
    const neutral = neutralBodyVisual()
    expect(neutral.regions).not.toBe(neutralBodyVisual().regions)
    expect(neutral.regions.left_arm).not.toBe(neutral.regions.right_arm)
  })
})
