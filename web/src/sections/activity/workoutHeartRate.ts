import { z } from '@/core/zod'
import { SportPlan, WorkoutPauses } from './sports'
import type { WorkoutPulse } from './workoutChart'

const Timestamp = z.string().refine((value) => Number.isFinite(Date.parse(value)))
export const PhoneWorkoutOrigin = z.object({
  source: z.literal('phone_recorded'),
  record_id: z.uuid(),
  details: z.object({
    status: z.enum(['completed', 'interrupted']),
    trigger: z.enum(['manual', 'automatic', 'manual_takeover']),
    recovery_end_at: Timestamp.nullish(),
    stop_reason: z.string().nullish(),
  }),
})
export const WorkoutHeartRateChunk = z.object({
  external_id: z.string(),
  workout_id: z.uuid(),
  sequence: z.number().int().min(0).max(10079),
  start_at: Timestamp,
  end_at: Timestamp,
  time_basis: z.literal('phone_monotonic'),
  samples: z
    .array(
      z
        .object({
          offset_ms: z.number().int().nonnegative(),
          bpm: z.number().int().min(0).max(300),
          quality: z.enum(['valid', 'invalid', 'unknown']),
        })
        .refine((value) => value.quality !== 'valid' || (value.bpm >= 20 && value.bpm <= 250)),
    )
    .max(300),
  gaps: z.array(
    z.object({
      start_offset_ms: z.number().int().nonnegative(),
      end_offset_ms: z.number().int().nonnegative(),
      reason: z.string(),
    }),
  ),
  clock_anchor: z.object({
    utc_at: Timestamp,
    elapsed_realtime_ms: z.number().int().nonnegative(),
    boot_id: z.string(),
  }),
})
export type WorkoutHeartRateChunk = z.infer<typeof WorkoutHeartRateChunk>
export const WorkoutHeartRatePage = z.object({
  workout_id: z.uuid(),
  source_id: z.uuid(),
  report_ref: z.object({ id: z.uuid(), revision: z.number().int().positive() }),
  fingerprint: z.string().regex(/^[0-9a-f]{64}$/),
  workout: z.object({
    kind: z.enum(['running_workouts', 'workouts']).optional(),
    sport_type: z.string().optional(),
    sport_plan: SportPlan.nullish(),
    recording_source: z.string().nullish(),
    gps_requested: z.boolean().nullish(),
    duration_seconds: z.number().nullable().optional(),
    active_duration_seconds: z.number().nullable().optional(),
    pauses: WorkoutPauses.optional(),
    start_at: Timestamp,
    end_at: Timestamp,
    timezone: z.string(),
    status: z.enum(['completed', 'interrupted']),
    trigger: z.enum(['manual', 'automatic', 'manual_takeover']),
    recovery_end_at: Timestamp.nullish(),
    stop_reason: z.string().nullish(),
  }),
  chunks: z.array(WorkoutHeartRateChunk).max(100),
  next_cursor: z.string().min(1).max(500).nullable(),
})
export type WorkoutHeartRatePage = z.infer<typeof WorkoutHeartRatePage>

/** Pages may only share a chart when both their lineage and report context agree. */
export function coherentWorkoutHeartRatePages(pages: WorkoutHeartRatePage[]): boolean {
  const first = pages[0]
  if (!first) return true
  const context = JSON.stringify(first.workout)
  let previous = -1
  for (const page of pages) {
    if (
      page.workout_id !== first.workout_id ||
      page.source_id !== first.source_id ||
      page.fingerprint !== first.fingerprint ||
      page.report_ref.id !== first.report_ref.id ||
      page.report_ref.revision !== first.report_ref.revision ||
      JSON.stringify(page.workout) !== context
    )
      return false
    for (const chunk of page.chunks) {
      if (chunk.workout_id !== first.workout_id || chunk.sequence <= previous) return false
      previous = chunk.sequence
    }
  }
  return true
}

/** The terminal report defines the workout boundary. Its recovery never becomes running data. */
export function workoutHeartRateSamples(pages: WorkoutHeartRatePage[]): WorkoutPulse[] {
  const samples: WorkoutPulse[] = []
  for (const page of pages) {
    const boundary = Date.parse(page.workout.end_at)
    for (const chunk of page.chunks) {
      if (chunk.workout_id !== page.workout_id) continue
      for (const sample of chunk.samples) {
        if (sample.quality === 'invalid') continue
        const timestamp = Date.parse(chunk.start_at) + sample.offset_ms
        if (
          page.workout.pauses?.some(
            (pause) =>
              timestamp >= Date.parse(pause.start_at) && timestamp < Date.parse(pause.end_at),
          )
        )
          continue
        const observed_at = new Date(timestamp).toISOString()
        samples.push({
          observed_at,
          bpm: sample.bpm,
          quality: sample.quality,
          phase:
            timestamp < boundary
              ? page.workout.kind === 'workouts'
                ? 'workout'
                : 'running'
              : 'recovery',
        })
      }
    }
  }
  return samples
}
