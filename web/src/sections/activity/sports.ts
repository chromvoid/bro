import { z } from '@/core/zod'

export const SportCatalogEntry = z.object({
  requested_id: z.number().int().nonnegative(),
  name: z.string().max(64),
  family: z.string().max(32),
  app_outdoor_gps: z.boolean(),
  candidate_wire_mode: z.number().int().nonnegative().nullable(),
  qualification: z.enum(['protocol_unverified', 'verified']),
  evidence_ref: z.string(),
  mapping_basis: z.enum(['direct_whitelist', 'family_fallback', 'adapter_fallback']).optional(),
  resolved_activity_id: z.number().int().min(0).max(65535).nullable().optional(),
})
export const SportPlan = SportCatalogEntry.omit({ app_outdoor_gps: true }).extend({
  actual_wire_mode: z.number().int().nonnegative().nullable(),
  resolved_activity_id: z.number().int().min(0).max(65535).nullable().optional(),
})
export const WorkoutPauses = z
  .array(z.object({ start_at: z.string(), end_at: z.string() }))
  .max(300)

/** A device workout's name: a run, a workout found by heart rate, or the sport a device reported. */
export function workoutTitle(t: (key: string) => string, sport: string): string {
  if (sport === 'running') return t('activity.running.workout')
  if (sport === 'workout') return t('activity.running.otherWorkout')
  return `${t('activity.running.otherWorkout')} · ${sport}`
}

/** A running timer keeps its seconds; a finished length in whole minutes reads without «0 с». */
export function workoutDuration(seconds: number, zeroSeconds = true): string {
  const total = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor(total / 60) % 60
  const rest = total % 60
  return `${hours ? `${hours} ч ` : ''}${minutes} мин${rest || zeroSeconds ? ` ${rest} с` : ''}`
}
