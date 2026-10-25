import type { Kind, Observation, Profile } from './api'
import { numericValue, type BodyAnalysis } from './body'

export const BODY_VISUAL_VERSION = 'body-visual-v2' as const
/** Drawing anchors only. They do not define reference or healthy ranges. */
export const BODY_VISUAL_CALIBRATION = {
  fatPercent: [10, 25, 40],
  muscleIndex: [6, 9, 12],
} as const
export const BODY_REGIONS = ['left_arm', 'right_arm', 'trunk', 'left_leg', 'right_leg'] as const
export type BodyRegion = (typeof BODY_REGIONS)[number]
/** The current DDX contract cannot establish regional tissue masses or sides. */
export type BodyRegionVisual = {
  fat: null
  lean: null
  unavailable: 'unverified_contract'
}

/** A visual parameter is not a measurement or a medical reference range. */
export type BodyVisualState = {
  version: typeof BODY_VISUAL_VERSION
  template: 'male' | 'female'
  fat: number | null
  muscle: number | null
  regions: Record<BodyRegion, BodyRegionVisual>
}
export type VisualUnavailable = 'missing' | 'excluded' | 'conflict' | 'invalid' | 'height_missing'
export type BodyVisualAxis = 'fat' | 'muscle'
export type CalibrationBoundary = 'below' | 'above'
export type BodyVisual = {
  visual: BodyVisualState
  unavailable: { fat: VisualUnavailable | null; muscle: VisualUnavailable | null }
  calibrationBoundaries: Record<BodyVisualAxis, CalibrationBoundary | null>
  genderMissing: boolean
  heightMissing: boolean
}

/** Fresh state: a renderer must never mutate another analysis's availability. */
export function neutralBodyVisual(template: BodyVisualState['template'] = 'male'): BodyVisualState {
  const unavailable = (): BodyRegionVisual => ({
    fat: null,
    lean: null,
    unavailable: 'unverified_contract',
  })
  return {
    version: BODY_VISUAL_VERSION,
    template,
    fat: null,
    muscle: null,
    regions: {
      left_arm: unavailable(),
      right_arm: unavailable(),
      trunk: unavailable(),
      left_leg: unavailable(),
      right_leg: unavailable(),
    },
  }
}

/** Anchors are a bounded drawing scale, not healthy/ideal values. */
export function visualScale(value: number, anchors: readonly [number, number, number]): number {
  const [low, middle, high] = anchors
  if (value <= low) return 0
  if (value >= high) return 1
  return value <= middle
    ? ((value - low) / (middle - low)) * 0.5
    : 0.5 + ((value - middle) / (high - middle)) * 0.5
}

function calibrationBoundary(
  value: number | null,
  anchors: readonly [number, number, number],
): CalibrationBoundary | null {
  if (value === null) return null
  return value < anchors[0] ? 'below' : value > anchors[2] ? 'above' : null
}

function measurement(analysis: BodyAnalysis, kind: Kind) {
  const rows = analysis.records.filter((record) => record.kind === kind)
  const active = rows.filter((record) => !record.excluded)
  let reason: VisualUnavailable | null = null
  let record: Observation | null = null
  if (!active.length) reason = rows.length ? 'excluded' : 'missing'
  else if (active.length > 1) reason = 'conflict'
  else record = active[0]!
  const value = record ? numericValue(record) : null
  if (record && (value === null || value < 0 || (kind === 'body_fat_percent' && value > 100)))
    reason = 'invalid'
  return { value: reason ? null : value, reason }
}

export function bodyVisual(analysis: BodyAnalysis, profile?: Profile | null): BodyVisual {
  const fat = measurement(analysis, 'body_fat_percent')
  const muscle = measurement(analysis, 'skeletal_muscle_mass')
  const height = Number(profile?.height_cm)
  const heightMissing =
    !profile?.height_cm || !Number.isFinite(height) || height < 50 || height > 260
  const muscleReason = muscle.reason ?? (heightMissing ? 'height_missing' : null)
  const muscleIndex =
    muscleReason || muscle.value === null ? null : muscle.value / (height / 100) ** 2
  return {
    visual: {
      ...neutralBodyVisual(profile?.gender === 'female' ? 'female' : 'male'),
      fat: fat.value === null ? null : visualScale(fat.value, BODY_VISUAL_CALIBRATION.fatPercent),
      muscle:
        muscleIndex === null ? null : visualScale(muscleIndex, BODY_VISUAL_CALIBRATION.muscleIndex),
    },
    unavailable: { fat: fat.reason, muscle: muscleReason },
    calibrationBoundaries: {
      fat: calibrationBoundary(fat.value, BODY_VISUAL_CALIBRATION.fatPercent),
      muscle: calibrationBoundary(muscleIndex, BODY_VISUAL_CALIBRATION.muscleIndex),
    },
    genderMissing: !profile?.gender,
    heightMissing,
  }
}

/** Only real, strictly earlier scans from the selected source can be compared. */
export function comparisonScans(
  analyses: BodyAnalysis[],
  selected: BodyAnalysis,
  profile?: Profile | null,
): BodyAnalysis[] {
  if (!selected.sourceId || !Number.isFinite(Date.parse(selected.measuredAt))) return []
  const current = bodyVisual(selected, profile).visual
  return analyses
    .filter((analysis) => {
      if (
        analysis.id === selected.id ||
        analysis.sourceId !== selected.sourceId ||
        !(Date.parse(analysis.measuredAt) < Date.parse(selected.measuredAt))
      )
        return false
      const previous = bodyVisual(analysis, profile).visual
      return (
        (current.fat !== null && previous.fat !== null) ||
        (current.muscle !== null && previous.muscle !== null)
      )
    })
    .sort((a, b) => Date.parse(b.measuredAt) - Date.parse(a.measuredAt) || a.id.localeCompare(b.id))
}

/** Scrubbing changes only the figure. A missing endpoint keeps the selected axis still. */
export function interpolateVisual(
  previous: BodyVisualState,
  selected: BodyVisualState,
  fraction: number,
): BodyVisualState {
  const amount = Number.isFinite(fraction) ? Math.max(0, Math.min(1, fraction)) : 1
  const blend = (axis: 'fat' | 'muscle') => {
    const from = previous[axis],
      to = selected[axis]
    return from === null || to === null ? to : from + (to - from) * amount
  }
  return { ...selected, fat: blend('fat'), muscle: blend('muscle') }
}
