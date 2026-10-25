import { dayIn, momentLabel, timeIn } from '@/core/dates'
import type { Metric } from './api'
import type { Resolution, SleepAnalysis, SleepSession } from './resolutionContract'
import { laneOf, stageLane, type Part } from './sleepChart'

/** These disagreements cannot be settled by choosing the same source again. */
export function sleepReportConflict(resolution: Resolution | null | undefined): string | null {
  const reasons = new Set(resolution?.alternatives.map((fact) => fact.reason))
  return (
    [
      'sleep_report_correction_conflict',
      'sleep_report_epoch_conflict',
      'sleep_report_conflict',
      'same_source_overlap',
    ].find((reason) => reasons.has(reason)) ?? null
  )
}

export const sleepConflictKey = (reason: string) =>
  reason === 'same_source_overlap' ? 'personal.sleepOverlap' : `resolution.reason.${reason}`

export function sleepInterval(start: string, end: string, timezone: string, today: string) {
  return dayIn(timezone, new Date(start)) === dayIn(timezone, new Date(end))
    ? `${timeIn(timezone, start)} — ${timeIn(timezone, end)}`
    : `${momentLabel(start, timezone, today)} — ${momentLabel(end, timezone, today)}`
}

/** Legacy responses can already contain an interval without a duration. */
export function metricSleepSessions(metric: Metric): SleepSession[] {
  if (metric.kind !== 'sleep') return []
  if (metric.resolution) return metric.resolution.sessions ?? []
  if (metric.status !== 'available') return []
  return (metric.records ?? [])
    .filter((record) => !record.excluded)
    .map((record) => ({
      id: record.id,
      start_at: record.start_at,
      end_at: record.end_at,
      contribution: record.data.value,
      asleep_minutes: record.data.value,
      source_id: record.source_id,
      source_name: record.source_name,
      stages: record.data.stages ?? [],
      position_changes: record.data.position_changes ?? [],
    }))
}

/** The analysed states of an episode as chart rows. */
export const episodeParts = (episode: SleepAnalysis['episodes'][number]): Part[] =>
  episode.segments.map((segment) => ({
    start_at: segment.start_at,
    end_at: segment.end_at,
    lane: laneOf(segment.state, segment.stage),
  }))

/** The reported stages of a session as chart rows. */
export const sessionParts = (session: SleepSession): Part[] =>
  session.stages.map((stage) => ({
    start_at: stage.start_at,
    end_at: stage.end_at,
    lane: stageLane(stage.stage),
  }))
