/**
 * Personal observations as the server sends them. A value keeps its origin: a measurement typed
 * by the user, a device report, an estimate and a user correction are shown apart, and a missing
 * value is never shown as zero.
 */
import { formatAmount } from '@/core/numbers'
import { distanceText, massText, massValue, massUnit, type DisplayUnits } from './displayUnits'
import { z } from '@/core/zod'
import { DdxConnection } from './ddx'
import { SourceAnalysisSummary } from './bodyArchive'
import { Resolution, SleepStage } from './resolutionContract'

export const BODY_KINDS = [
  'body_fat_percent',
  'body_fat_mass',
  'skeletal_muscle_mass',
  'fat_free_mass',
  'body_water',
  'body_protein',
  'body_minerals',
  'bmi',
  'basal_metabolic_rate',
  'visceral_fat_level',
] as const
export type BodyKind = (typeof BODY_KINDS)[number]
export const DAILY_KINDS = [
  'sleep',
  'steps',
  'active_minutes',
  'active_energy',
  'distance',
] as const
export const INSTANT_KINDS = ['weight', ...BODY_KINDS] as const
export const KINDS = ['weight', ...DAILY_KINDS, ...BODY_KINDS] as const
export type Kind = (typeof KINDS)[number]
export type HistoryKind = Kind | 'body'

export function isInstant(kind: Kind): boolean {
  return (INSTANT_KINDS as readonly Kind[]).includes(kind)
}

/** Composition measurements have no user-defined targets. */
export function hasTarget(kind: Kind): boolean {
  return !(BODY_KINDS as readonly Kind[]).includes(kind)
}

const Amount = z
  .union([z.string(), z.number()])
  .nullish()
  .transform((value) => (value === null || value === undefined ? null : String(value)))

const RecordOrigin = z.looseObject({
  provider: z.string().nullish(),
  device: z.string().nullish(),
  record_id: z.string().nullish(),
})

export const Observation = z.looseObject({
  id: z.string(),
  source_id: z.string().nullish(),
  kind: z.enum(KINDS),
  start_at: z.string(),
  end_at: z.string(),
  timezone: z.string(),
  local_date: z.string().nullish(),
  method: z.string(),
  algorithm_version: z.string().nullish(),
  data: z.looseObject({
    value: Amount,
    stages: z.array(SleepStage).optional(),
    position_changes: z.array(z.string()).optional(),
  }),
  quality: z.array(z.string()).nullish(),
  origin: RecordOrigin.nullish(),
  received_at: z.string().nullish(),
  revision: z.number(),
  source_name: z.string().nullish(),
  source_status: z.string().nullish(),
  excluded: z.boolean().nullish(),
  override_revision: z.number().nullish(),
  corrected: z.boolean().nullish(),
  original: z
    .looseObject({
      data: z.looseObject({ value: Amount }),
      start_at: z.string().nullish(),
      origin: RecordOrigin.nullish(),
    })
    .nullish(),
})
export type Observation = z.infer<typeof Observation>

export const Metric = z.looseObject({
  kind: z.enum(KINDS),
  status: z.enum(['available', 'no_data', 'conflict', 'unavailable']),
  value: Amount,
  unit: z.string(),
  complete: z.boolean().nullish(),
  target: Amount,
  records: z.array(Observation).nullish(),
  source_unavailable: z.boolean().nullish(),
  resolution: Resolution.nullish(),
})
export type Metric = z.infer<typeof Metric>

/** Each independent method retains its own latest value beside the combined metric. */
export const SourceMetric = Metric.extend({
  source_id: z.string().nullable(),
  source_name: z.string().nullish(),
})
export type SourceMetric = z.infer<typeof SourceMetric>

export const NutritionMetric = z.looseObject({
  status: z.string(),
  value: Amount,
  unit: z.string(),
  complete: z.boolean().nullish(),
  target: Amount,
  remaining: Amount,
  estimated_count: z.number().nullish(),
  pending_count: z.number().nullish(),
})

export const Source = z.looseObject({
  id: z.string(),
  name: z.string(),
  kinds: z.array(z.string()),
  status: z.enum(['connected', 'unavailable', 'disabled']),
  cursor: z.unknown().nullish(),
  last_received_at: z.string().nullish(),
  error: z.string().nullish(),
  revision: z.number(),
  connection: DdxConnection.nullish(),
  metric_keys: z.array(z.string()).optional(),
})
export type Source = z.infer<typeof Source>

export const Profile = z.looseObject({
  user_id: z.string(),
  timezone: z.string(),
  display_units: z.enum(['metric', 'imperial']).default('metric'),
  birth_date: z.string().nullish(),
  height_cm: Amount,
  gender: z.enum(['male', 'female']).nullable().optional(),
  gender_origin: z.enum(['unset', 'manual']).optional(),
  revision: z.number(),
})
export type Profile = z.infer<typeof Profile>

/** A metric's target; sources are chosen by dated resolution policies. */
export const Preference = z.looseObject({
  kind: z.string(),
  target: Amount,
  revision: z.number(),
})
export type Preference = z.infer<typeof Preference>

export const Overview = z.looseObject({
  date: z.string(),
  timezone: z.string(),
  profile: Profile,
  metrics: z.looseObject({
    weight: Metric,
    sleep: Metric,
    steps: Metric,
    active_minutes: Metric,
    active_energy: Metric.optional(),
    distance: Metric.optional(),
    body_fat_percent: Metric.optional(),
    body_fat_mass: Metric.optional(),
    skeletal_muscle_mass: Metric.optional(),
    fat_free_mass: Metric.optional(),
    body_water: Metric.optional(),
    body_protein: Metric.optional(),
    body_minerals: Metric.optional(),
    bmi: Metric.optional(),
    basal_metabolic_rate: Metric.optional(),
    visceral_fat_level: Metric.optional(),
    nutrition: NutritionMetric.nullish(),
  }),
  preferences: z.record(z.string(), Preference),
  sources: z.array(Source),
  metrics_by_source: z
    .looseObject({ body_fat_percent: z.array(SourceMetric).optional() })
    .optional(),
})
export type Overview = z.infer<typeof Overview>

export const History = z.looseObject({
  from: z.string(),
  to: z.string(),
  records: z.array(Observation),
  sources: z.array(Source).optional(),
  source_analyses: z.array(SourceAnalysisSummary).optional(),
  truncated: z.boolean().optional(),
  daily: z.array(
    z.looseObject({
      date: z.string(),
      kind: z.string(),
      status: z.string(),
      unit: z.string().optional(),
      value: Amount,
      /** The person's goal for this kind on that day, if any. */
      target: Amount,
      resolution: Resolution.optional(),
    }),
  ),
})
export type History = z.infer<typeof History>

export const Sources = z.object({ sources: z.array(Source) })

export const BodyPresentation = z.object({
  character_id: z.enum(['sculpture', 'spartan']),
  revision: z.number().int().nonnegative(),
})
export type BodyPresentation = z.infer<typeof BodyPresentation>
export type BodyCharacterId = BodyPresentation['character_id']

export const PERSONAL = {
  endpoint: '/v1/personal/operations',
  lookupPrefix: '/v1/personal/operations/by-key/',
  domain: 'personal',
}

/** The unit word for an amount of a kind: «кг», «мин», «шагов». */
export function unitOf(kind: Kind | 'nutrition'): string {
  return {
    weight: 'кг',
    sleep: 'мин',
    steps: 'шагов',
    active_minutes: 'мин',
    active_energy: 'ккал',
    distance: 'м',
    nutrition: 'ккал',
    body_fat_percent: '%',
    body_fat_mass: 'кг',
    skeletal_muscle_mass: 'кг',
    fat_free_mass: 'кг',
    body_water: 'л',
    body_protein: 'кг',
    body_minerals: 'кг',
    bmi: 'кг/м²',
    basal_metabolic_rate: 'ккал/день',
    visceral_fat_level: 'ур.',
  }[kind]
}

/** All-history days include sensor and report results as well as ordinary observations. */
export function historyUnit(kind: string, reported?: string): string {
  if (reported !== undefined) return reported
  if ((KINDS as readonly string[]).includes(kind)) return unitOf(kind as Kind)
  return (
    (
      {
        heart_rate: 'bpm',
        spo2: '%',
        temperature: 'celsius',
        hrv: 'ms',
        stress: 'index',
        running_steps: 'шагов',
        running_workouts: 'тренировок',
        workouts: 'тренировок',
      } as Record<string, string>
    )[kind] ?? ''
  )
}

/** «7 ч 20 мин» for a sleep duration in minutes. */
export function duration(minutes: number): string {
  const whole = Math.round(minutes)
  const hours = Math.floor(whole / 60)
  return hours ? `${hours} ч ${whole % 60} мин` : `${whole} мин`
}

/** A value with its unit, or null when there is none to show. */
export function amountText(
  kind: Kind | 'nutrition',
  value: string | null,
  displayUnits?: DisplayUnits,
): string | null {
  if (value === null || !value.trim() || !Number.isFinite(Number(value))) return null
  if (kind === 'sleep') return duration(Number(value))
  if (kind === 'distance' && displayUnits) return distanceText(value, displayUnits)
  if (unitOf(kind) === 'кг' && displayUnits) return massText(value, displayUnits)
  const fraction =
    kind === 'distance' ||
    (kind !== 'nutrition' && isInstant(kind) && kind !== 'basal_metabolic_rate')
      ? 2
      : 0
  return `${formatAmount(value, fraction)} ${unitOf(kind)}`
}

export type Origin = 'corrected' | 'estimate' | 'manual' | 'device'

/** Where a value came from, most specific first: a user's correction outranks the source. */
export function originOf(record: Observation): Origin {
  if (record.corrected) return 'corrected'
  if (record.method === 'derived') return 'estimate'
  if (record.method === 'self_report') return 'manual'
  return 'device'
}

/** The observation to send with a correction; the server replaces the whole override. */
export function observationOf(
  record: Observation,
  changes: { value?: string | null; start_at?: string; end_at?: string } = {},
) {
  const start = changes.start_at ?? record.start_at
  const value = changes.value === undefined ? record.data.value : changes.value
  return {
    kind: record.kind,
    start_at: start,
    // Body measurements are moments: they end when they start.
    end_at: isInstant(record.kind) ? start : (changes.end_at ?? record.end_at),
    timezone: record.timezone,
    method: 'self_report',
    data: {
      value: value === null ? null : record.kind === 'steps' ? Number(value) : value,
      ...(['active_energy', 'distance'].includes(record.kind)
        ? { unit: record.kind === 'active_energy' ? 'kcal' : 'm' }
        : {}),
    },
  }
}

/** Weighings as chart points, one per day with a value, oldest first. */
/** «−1,5 кг»: a signed change with the minus sign of Russian typography. */
export function signedKilograms(
  change: number,
  kg: string,
  displayUnits: DisplayUnits = 'metric',
): string {
  const rounded = Math.round(massValue(change, displayUnits)! * 10) / 10
  const sign = rounded < 0 ? '−' : rounded > 0 ? '+' : ''
  return `${sign}${formatAmount(Math.abs(rounded), 1)} ${displayUnits === 'imperial' ? massUnit(displayUnits) : kg}`
}

export function weightPoints(history: History): Array<{ x: number; y: number }> {
  return history.daily
    .filter((day) => day.kind === 'weight' && day.value !== null && day.status !== 'conflict')
    .map((day) => ({ x: Date.parse(`${day.date}T00:00:00Z`), y: Number(day.value) }))
    .filter((point) => Number.isFinite(point.y))
    .sort((a, b) => a.x - b.x)
}

/** «Мои часы · Данные устройства»; a value typed by the user has no source name to repeat. */
export function provenance(record: Observation, label: (origin: Origin) => string): string {
  return [record.source_name, label(originOf(record))].filter(Boolean).join(' · ')
}
