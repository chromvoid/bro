import { formatAmount } from '@/core/numbers'
import { massValue, massUnit, type DisplayUnits } from './displayUnits'
import { BODY_KINDS, INSTANT_KINDS, isInstant, unitOf, type Kind, type Observation } from './api'
import type { SourceAnalysisSummary } from './bodyArchive'

export type BodyAnalysis = {
  id: string
  sourceId: string | null
  sourceName: string | null
  measuredAt: string
  records: Observation[]
  values: Partial<Record<Kind, Observation>>
  previous: Partial<Record<Kind, { record: Observation; measuredAt: string }>>
  archive?: SourceAnalysisSummary
}

const order = (kind: Kind) => (INSTANT_KINDS as readonly Kind[]).indexOf(kind)

/** A provider identity is required to group records. Matching dates alone prove nothing. */
export function bodyAnalyses(
  records: Observation[],
  archives: SourceAnalysisSummary[] = [],
): BodyAnalysis[] {
  const groups = new Map<string, BodyAnalysis>()
  // A raw-only scan is still an analysis. Join snapshots by provider identity, never by date.
  for (const archive of archives) {
    const id = JSON.stringify([archive.source_id, archive.provider_record_id])
    groups.set(id, {
      id,
      sourceId: archive.source_id,
      sourceName: archive.source_name ?? null,
      measuredAt: archive.measured_at,
      records: [],
      values: {},
      previous: {},
      archive,
    })
  }
  for (const record of records) {
    if (!isInstant(record.kind)) continue
    const origin = record.original?.origin ?? record.origin
    const sourceId = record.source_id ?? null
    const id =
      sourceId && origin?.record_id
        ? JSON.stringify([sourceId, origin.record_id])
        : JSON.stringify(['record', record.id])
    let group = groups.get(id)
    const measuredAt = record.original?.start_at ?? record.start_at
    if (!group) {
      group = {
        id,
        sourceId,
        sourceName: record.source_name ?? null,
        measuredAt,
        records: [],
        values: {},
        previous: {},
      }
      groups.set(id, group)
    }
    group.records.push(record)
    if (!group.archive && Date.parse(measuredAt) < Date.parse(group.measuredAt))
      group.measuredAt = measuredAt
  }
  const analyses = [...groups.values()]
    // Manual weighings belong to the weight history; a body scan must report composition.
    .filter(
      (group) =>
        group.archive ||
        group.records.some((record) => (BODY_KINDS as readonly Kind[]).includes(record.kind)),
    )
    .sort((a, b) => Date.parse(a.measuredAt) - Date.parse(b.measuredAt) || a.id.localeCompare(b.id))
  for (const analysis of analyses) {
    analysis.records.sort((a, b) => order(a.kind) - order(b.kind) || a.id.localeCompare(b.id))
    for (const kind of INSTANT_KINDS) {
      const candidates = analysis.records.filter(
        (record) => record.kind === kind && !record.excluded,
      )
      // Ambiguous duplicates remain visible in details, never silently averaged or selected.
      if (candidates.length === 1 && numericValue(candidates[0]!) !== null)
        analysis.values[kind] = candidates[0]
    }
  }
  const preceding = new Map<string, Map<Kind, { record: Observation | null; measuredAt: string }>>()
  for (const analysis of analyses) {
    if (!analysis.sourceId) continue
    const source =
      preceding.get(analysis.sourceId) ??
      new Map<Kind, { record: Observation | null; measuredAt: string }>()
    for (const kind of INSTANT_KINDS) {
      const previous = source.get(kind)
      if (
        analysis.values[kind] &&
        previous?.record &&
        Date.parse(previous.measuredAt) < Date.parse(analysis.measuredAt)
      ) {
        analysis.previous[kind] = { record: previous.record, measuredAt: previous.measuredAt }
      }
    }
    for (const kind of INSTANT_KINDS) {
      const current = analysis.values[kind]
      if (current) {
        const sameTime = source.get(kind)?.measuredAt
        source.set(kind, {
          record:
            sameTime && Date.parse(sameTime) === Date.parse(analysis.measuredAt) ? null : current,
          measuredAt: analysis.measuredAt,
        })
      }
    }
    preceding.set(analysis.sourceId, source)
  }
  return analyses.reverse()
}

export function numericValue(record: Observation): number | null {
  const value = record.data.value
  return value !== null && value.trim() && Number.isFinite(Number(value)) ? Number(value) : null
}

/** Neutral change, using percentage points for body fat percentage, without target judgments. */
export function bodyDelta(
  current: Observation,
  previous: Observation,
  displayUnits: DisplayUnits = 'metric',
): string | null {
  if (
    current.kind !== previous.kind ||
    !current.source_id ||
    current.source_id !== previous.source_id ||
    current.excluded ||
    previous.excluded
  )
    return null
  const currentValue = numericValue(current)
  const previousValue = numericValue(previous)
  if (currentValue === null || previousValue === null) return null
  const canonicalUnit = unitOf(current.kind)
  const rawDifference = currentValue - previousValue
  const difference =
    Math.round(
      (canonicalUnit === 'кг' ? massValue(rawDifference, displayUnits)! : rawDifference) * 100,
    ) / 100
  const unit =
    current.kind === 'body_fat_percent'
      ? 'п. п.'
      : canonicalUnit === 'кг'
        ? massUnit(displayUnits)
        : canonicalUnit
  return `${difference > 0 ? '+' : difference < 0 ? '−' : ''}${formatAmount(Math.abs(difference), 2)} ${unit}`
}

/** One point per unambiguous scan from one source; corrected timestamps stay in the details. */
export function bodyPoints(analyses: BodyAnalysis[], sourceId: string, kind: Kind) {
  const points = analyses
    .filter((analysis) => analysis.sourceId === sourceId && analysis.values[kind])
    .map((analysis) => ({
      x: Date.parse(analysis.measuredAt),
      y: numericValue(analysis.values[kind]!)!,
    }))
  const countAt = new Map<number, number>()
  for (const point of points) countAt.set(point.x, (countAt.get(point.x) ?? 0) + 1)
  return points.filter((point) => countAt.get(point.x) === 1).sort((a, b) => a.x - b.x)
}
