import type { Point } from '@/components/chart/scale'

export interface WorkoutPulse {
  observed_at: string
  bpm: number
  quality: 'valid' | 'invalid' | 'unknown'
  phase: 'running' | 'workout' | 'recovery'
}

/** A cached reading remains a previous reading, never a new measurement at the render time. */
export function pulseIsStale(
  pulse: Pick<WorkoutPulse, 'observed_at'> | null,
  now: number,
): boolean {
  if (!pulse) return true
  const timestamp = Date.parse(pulse.observed_at)
  return !Number.isFinite(timestamp) || timestamp > now || now - timestamp > 15_000
}

/** Only valid actual results are plotted; unknown raw values remain available in details. */
export function workoutPoints(samples: WorkoutPulse[], phase: WorkoutPulse['phase']): Point[] {
  const points: Point[] = []
  for (const sample of samples) {
    const x = Date.parse(sample.observed_at)
    if (
      sample.quality === 'valid' &&
      sample.phase === phase &&
      Number.isFinite(x) &&
      Number.isFinite(sample.bpm)
    )
      points.push({ x, y: sample.bpm })
  }
  return points
}
