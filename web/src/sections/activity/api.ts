import { z } from '@/core/zod'

export const KINDS = [
  'walking',
  'running',
  'cycling',
  'strength',
  'swimming',
  'mobility',
  'other',
] as const
export type ActivityKind = (typeof KINDS)[number]
export const LOAD_BASES = [
  'total',
  'per_implement',
  'additional',
  'assistance',
  'bodyweight',
] as const
export type LoadBasis = (typeof LOAD_BASES)[number]
const Amount = z
  .union([z.string(), z.number()])
  .nullable()
  .transform((value) => (value === null ? null : String(value)))
export const ExerciseSnapshot = z.looseObject({
  name: z.string(),
  equipment: z.string().nullish(),
  variant: z.string().nullish(),
  load_basis: z.enum(LOAD_BASES),
})
export type ExerciseSnapshot = z.infer<typeof ExerciseSnapshot>
export const ExerciseDefinition = ExerciseSnapshot.extend({ id: z.string(), revision: z.number() })
export type ExerciseDefinition = z.infer<typeof ExerciseDefinition>
export const ActivitySet = z.looseObject({
  id: z.string(),
  revision: z.number(),
  position: z.number(),
  kind: z.enum(['warmup', 'working']),
  repetitions: z.number().nullable(),
  load_value: Amount,
  load_unit: z.enum(['kg', 'lb']),
  implement_count: z.number(),
  note: z.string().nullish(),
})
export type ActivitySet = z.infer<typeof ActivitySet>
export const SessionExercise = z.looseObject({
  id: z.string(),
  revision: z.number(),
  exercise_id: z.string(),
  position: z.number(),
  snapshot: ExerciseSnapshot,
  note: z.string().nullish(),
  sets: z.array(ActivitySet),
})
export type SessionExercise = z.infer<typeof SessionExercise>
export const ActivitySession = z.looseObject({
  id: z.string(),
  revision: z.number(),
  kind: z.enum(KINDS),
  title: z.string().nullish(),
  date: z.string(),
  timezone: z.string(),
  started_at: z.string().nullable(),
  duration_minutes: Amount,
  distance_km: Amount,
  note: z.string().nullish(),
  exercises: z.array(SessionExercise),
})
export type ActivitySession = z.infer<typeof ActivitySession>
export const Sessions = z.looseObject({
  sessions: z.array(ActivitySession),
  truncated: z.boolean().optional(),
})
export const Exercises = z.looseObject({ exercises: z.array(ExerciseDefinition) })
export const SessionReply = z.object({ session: ActivitySession })
const Distances = z.record(z.string(), Amount)
export const Summary = z.looseObject({
  from: z.string(),
  to: z.string(),
  totals: z.looseObject({
    sessions: z.number(),
    duration_minutes: Amount,
    missing_duration_sessions: z.number(),
    distance_km_by_kind: Distances,
    working_sets: z.number(),
  }),
  daily: z.array(
    z.looseObject({
      date: z.string(),
      sessions: z.number(),
      duration_minutes: Amount,
      missing_duration_sessions: z.number(),
      distance_km_by_kind: Distances,
      working_sets: z.number(),
    }),
  ),
})
export type Summary = z.infer<typeof Summary>
export const Progress = z.looseObject({
  exercise: ExerciseDefinition,
  from: z.string(),
  to: z.string(),
  series: z.array(
    z.looseObject({
      snapshot: ExerciseSnapshot,
      load_unit: z.literal('kg'),
      implement_count: z.number(),
      points: z.array(
        z.looseObject({
          date: z.string(),
          session_id: z.string(),
          session_exercise_id: z.string(),
          set_id: z.string(),
          repetitions: z.number().nullable(),
          load_value: Amount,
          load_kg: Amount,
        }),
      ),
    }),
  ),
  daily: z.array(z.object({ date: z.string(), working_sets: z.number() })),
})
export type Progress = z.infer<typeof Progress>
export const StepsOverride = z.object({
  id: z.string(),
  revision: z.number(),
  value: z.number().nullable(),
  active: z.boolean(),
})
export type StepsOverride = z.infer<typeof StepsOverride>
export const StepsReply = z.object({ date: z.string(), override: StepsOverride.nullable() })
export const ACTIVITY = {
  endpoint: '/v1/activity/operations',
  lookupPrefix: '/v1/activity/operations/by-key/',
  domain: 'activity',
}
