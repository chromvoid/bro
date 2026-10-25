import { dayIn } from '@/core/dates'
import {
  BODY_KINDS,
  type BodyKind,
  type History,
  type Metric,
  type Observation,
  type SourceMetric,
} from './api'
import { bodyAnalyses, bodyDelta, numericValue, type BodyAnalysis } from './body'

const keyKinds = ['skeletal_muscle_mass', 'body_fat_percent'] as const

function deviceRecord(metric?: Metric): Observation | undefined {
  if (!metric || metric.status === 'conflict' || metric.status === 'no_data') return
  const records = metric.records?.filter(
    (record) =>
      !record.excluded &&
      record.method !== 'derived' &&
      (record.original?.origin ?? record.origin)?.provider !== 'caliper' &&
      numericValue(record) !== null,
  )
  return records?.length === 1 ? records[0] : undefined
}

/** Keep normalized scan identity; matching timestamps alone cannot join two measurements. */
export function dashboardBody(
  metrics: Partial<Record<BodyKind, Metric>>,
  fatSources: SourceMetric[],
  history: History | undefined,
  day: string,
  timezone: string,
): { analyses: BodyAnalysis[]; current: BodyAnalysis | undefined } {
  const records = new Map<string, Observation>()
  const mergeRecord = (record: Observation) => {
    const previous = records.get(record.id)
    if (
      !previous ||
      record.revision > previous.revision ||
      (record.revision === previous.revision &&
        (record.override_revision ?? 0) > (previous.override_revision ?? 0))
    )
      records.set(record.id, record)
  }
  for (const metric of [...BODY_KINDS.map((kind) => metrics[kind]), ...fatSources]) {
    for (const record of metric?.records ?? []) mergeRecord(record)
  }
  // Queries can complete independently: arrival order does not establish record freshness.
  // Prefer the overview on equal versions, and history only for a newer fact or override.
  for (const record of history?.records ?? []) mergeRecord(record)
  const beforeDay = (instant: string) =>
    Number.isFinite(Date.parse(instant)) && dayIn(timezone, new Date(instant)) <= day
  const analyses = bodyAnalyses(
    [...records.values()].filter((record) => beforeDay(record.start_at)),
    history?.source_analyses?.filter((archive) => beforeDay(archive.measured_at)),
  ).filter((analysis) => beforeDay(analysis.measuredAt))

  // Use a record selected by the overview's preference, not the first provider in history.
  let anchor = keyKinds.map((kind) => deviceRecord(metrics[kind])).find(Boolean)
  if (!anchor) {
    const independent = fatSources.map(deviceRecord).filter((record) => !!record)
    if (independent.length === 1) anchor = independent[0]
  }
  if (!anchor) anchor = BODY_KINDS.map((kind) => deviceRecord(metrics[kind])).find(Boolean)
  const current = anchor
    ? analyses.find((analysis) => analysis.records.some((record) => record.id === anchor!.id))
    : undefined
  return { analyses, current }
}

function sameMethod(current: Observation, previous: Observation): boolean {
  return (
    current.method === previous.method &&
    (current.algorithm_version ?? null) === (previous.algorithm_version ?? null)
  )
}

/** Different methods from the same source are still separate measurements. */
export function dashboardBodyDelta(current: Observation, previous: Observation): string | null {
  return sameMethod(current, previous) ? bodyDelta(current, previous) : null
}

export type DashboardBodyChange = {
  kind: 'body_fat_percent' | 'skeletal_muscle_mass'
  value: string
  at: string
  previousAnalysisId: string | null
}

/** Per-metric endpoints stay separate unless they belong to one identified scan. */
export function dashboardBodyChanges(
  current: BodyAnalysis | undefined,
  analyses: readonly BodyAnalysis[],
): { changes: DashboardBodyChange[]; sharedPrevious: BodyAnalysis | undefined } {
  const changes: DashboardBodyChange[] = []
  for (const kind of ['body_fat_percent', 'skeletal_muscle_mass'] as const) {
    const selected = current?.values[kind]
    const previous = current?.previous[kind]
    if (!selected || !previous) continue
    const value = dashboardBodyDelta(selected, previous.record)
    if (value === null) continue
    const owners = analyses.filter((analysis) => analysis.values[kind]?.id === previous.record.id)
    changes.push({
      kind,
      value,
      at: previous.measuredAt,
      previousAnalysisId: owners.length === 1 ? owners[0]!.id : null,
    })
  }
  const sharedId = changes.length === 2 ? changes[0]!.previousAnalysisId : null
  const sharedPrevious =
    sharedId && changes.every((change) => change.previousAnalysisId === sharedId)
      ? analyses.find((analysis) => analysis.id === sharedId)
      : undefined
  return { changes, sharedPrevious }
}

export function previousDashboardBody(
  analyses: BodyAnalysis[],
  current: BodyAnalysis | undefined,
): BodyAnalysis | undefined {
  if (!current?.sourceId) return
  const candidates = analyses.filter(
    (analysis) =>
      analysis.sourceId === current.sourceId &&
      Date.parse(analysis.measuredAt) < Date.parse(current.measuredAt) &&
      keyKinds.some((kind) => {
        const selected = current.values[kind]
        const previous = analysis.values[kind]
        return selected && previous && dashboardBodyDelta(selected, previous) !== null
      }),
  )
  // Multiple scans at the preceding timestamp do not establish a unique comparison.
  const previous = candidates[0]
  return previous &&
    candidates.filter(
      (analysis) => Date.parse(analysis.measuredAt) === Date.parse(previous.measuredAt),
    ).length === 1
    ? previous
    : undefined
}
