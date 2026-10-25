import { dayIn, isIsoDay, shiftDay } from '@/core/dates'
import { ticks } from '@/components/chart/scale'
import { Observation, type History, type Observation as ObservationType } from './api'
import { bodyAnalyses, numericValue, type BodyAnalysis } from './body'
import type { SourceAnalysisSummary } from './bodyArchive'

export const DASHBOARD_WEIGHT_PERIODS = [7, 30, 90] as const
export type DashboardWeightPeriod = (typeof DASHBOARD_WEIGHT_PERIODS)[number]
export interface DashboardWeightRange {
  from: string
  to: string
}
export interface DashboardWeightPoint {
  key: string
  day: string
  value: number
  kind: 'weight' | 'body_fat_percent'
  records: ObservationType[]
  archive?: SourceAnalysisSummary
}
export interface DashboardFatSeries {
  sourceId: string
  sourceName: string | null
  points: DashboardWeightPoint[]
}
export interface DashboardFatMeasurement {
  record: ObservationType
  day: string
  archive?: SourceAnalysisSummary
}

export function dashboardWeightRange(day: string, period: DashboardWeightPeriod) {
  return { from: shiftDay(day, 1 - period), to: day }
}

function amount(value: unknown): number | null {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  if (typeof value === 'string' && !value.trim()) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}
const inRange = (day: string, range: DashboardWeightRange) =>
  isIsoDay(day) && day >= range.from && day <= range.to

/** The API's daily selection owns source orders and conflicts; never recompute an average. */
export function dashboardWeightPoints(history: History | undefined, range: DashboardWeightRange) {
  const result: DashboardWeightPoint[] = []
  for (const daily of history?.daily ?? []) {
    const value = amount(daily.value)
    if (
      daily.kind !== 'weight' ||
      daily.status !== 'available' ||
      value === null ||
      value <= 0 ||
      !inRange(daily.date, range)
    )
      continue
    // Newer replies carry the exact selected records. Older replies still carry a selected value.
    const candidates = (history?.records ?? []).filter(
      (record) => record.local_date === daily.date && numericValue(record) === value,
    )
    const selected = Array.isArray(daily.records)
      ? daily.records.flatMap((record) => {
          const parsed = Observation.safeParse(record)
          return parsed.success ? [parsed.data] : []
        })
      : candidates.length === 1
        ? candidates
        : []
    result.push({
      key: `weight:${daily.date}`,
      day: daily.date,
      value,
      kind: 'weight',
      records: selected.filter(
        (record) => record.kind === 'weight' && !record.excluded && numericValue(record) === value,
      ),
    })
  }
  // An inconsistent duplicate day is not resolved locally, even when its values happen to agree.
  const counts = new Map<string, number>()
  for (const daily of history?.daily ?? []) {
    if (daily.kind === 'weight' && inRange(daily.date, range))
      counts.set(daily.date, (counts.get(daily.date) ?? 0) + 1)
  }
  return result
    .filter((point) => counts.get(point.day) === 1)
    .sort((a, b) => a.day.localeCompare(b.day))
}

export function dashboardWeightConflicts(
  history: History | undefined,
  range: DashboardWeightRange,
) {
  return [
    ...new Set(
      (history?.daily ?? [])
        .filter(
          (daily) =>
            daily.kind === 'weight' && daily.status === 'conflict' && inRange(daily.date, range),
        )
        .map((daily) => daily.date),
    ),
  ].sort()
}

/** DDX's verified InBody connector, never a source's editable display name or generic device method. */
function inBodyAnalyses(history: History | undefined) {
  if (!history) return []
  const sources = new Set(
    (history.sources ?? [])
      .filter((source) => source.connection?.provider === 'ddx')
      .map((source) => source.id),
  )
  return bodyAnalyses(history.records, history.source_analyses).filter((analysis) => {
    return (
      sources.has(analysis.sourceId ?? '') ||
      analysis.records.some((record) => {
        const origin = record.original?.origin ?? record.origin
        return origin?.provider === 'DDX Fitness'
      })
    )
  })
}
function measurementDay(record: ObservationType, analysis: BodyAnalysis) {
  const zone =
    typeof record.original?.timezone === 'string' ? record.original.timezone : record.timezone
  const savedDay = record.original ? record.original.local_date : record.local_date
  return isIsoDay(savedDay) ? savedDay : dayIn(zone, new Date(analysis.measuredAt))
}

/** Details retain excluded and ambiguous observations even when they cannot become chart points. */
export function dashboardFatMeasurements(
  history: History | undefined,
  range: DashboardWeightRange,
) {
  const result: DashboardFatMeasurement[] = []
  for (const analysis of inBodyAnalyses(history)) {
    if (!Number.isFinite(Date.parse(analysis.measuredAt))) continue
    for (const record of analysis.records) {
      if (record.kind !== 'body_fat_percent') continue
      const day = measurementDay(record, analysis)
      if (inRange(day, range)) result.push({ record, day, archive: analysis.archive })
    }
  }
  return result
}

export function dashboardFatSeries(history: History | undefined, range: DashboardWeightRange) {
  const series = new Map<string, DashboardFatSeries>()
  const samples = inBodyAnalyses(history).filter((analysis) => analysis.values.body_fat_percent)
  const counts = new Map<string, number>()
  for (const analysis of samples) {
    const key = JSON.stringify([analysis.sourceId, Date.parse(analysis.measuredAt)])
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  for (const analysis of samples) {
    const record = analysis.values.body_fat_percent!
    const value = numericValue(record)
    if (value === null || value < 0 || value > 100) continue
    if (!Number.isFinite(Date.parse(analysis.measuredAt))) continue
    if (counts.get(JSON.stringify([analysis.sourceId, Date.parse(analysis.measuredAt)])) !== 1)
      continue
    // Body projection follows the source's calendar day, including after scalar corrections.
    const day = measurementDay(record, analysis)
    if (!inRange(day, range)) continue
    const sourceId = analysis.sourceId ?? `record:${record.id}`
    const group = series.get(sourceId) ?? { sourceId, sourceName: analysis.sourceName, points: [] }
    group.points.push({
      key: `fat:${analysis.id}`,
      day,
      value,
      kind: 'body_fat_percent',
      records: [record],
      archive: analysis.archive,
    })
    series.set(sourceId, group)
  }
  return [...series.values()].map((group) => ({
    ...group,
    points: group.points.sort(
      (a, b) =>
        a.day.localeCompare(b.day) ||
        Date.parse(a.records[0]!.original?.start_at ?? a.records[0]!.start_at) -
          Date.parse(b.records[0]!.original?.start_at ?? b.records[0]!.start_at) ||
        a.key.localeCompare(b.key),
    ),
  }))
}

export function dashboardWeightChange(points: DashboardWeightPoint[]): number | null {
  return points.length > 1 ? points.at(-1)!.value - points[0]!.value : null
}

/** Both axes matter: weight and fat can share a date while occupying different plot heights. */
export function dashboardNearestPoint<T extends { x: number; y: number }>(
  points: T[],
  pointer: { x: number; y: number },
) {
  let nearest: T | null = null
  const distance = (point: T) => (point.x - pointer.x) ** 2 + (point.y - pointer.y) ** 2
  for (const point of points) if (!nearest || distance(point) < distance(nearest)) nearest = point
  return nearest
}

/** Calendar coordinates, not invented measurement timestamps; every dot is a real reported day. */
export function dashboardDateX(day: string, range: DashboardWeightRange, width: number) {
  const start = Date.parse(`${range.from}T00:00:00Z`)
  const end = Date.parse(`${shiftDay(range.to, 1)}T00:00:00Z`)
  return 40 + ((Date.parse(`${day}T12:00:00Z`) - start) / (end - start)) * (width - 80)
}

export function dashboardAxis(points: DashboardWeightPoint[]) {
  if (!points.length) return null
  const values = points.map((point) => point.value)
  let min = Math.min(...values),
    max = Math.max(...values)
  if (min === max) {
    const pad = Math.max(0.25, Math.abs(min) * 0.005)
    min = Math.max(0, min - pad)
    max += pad
  }
  const valuesTicks = ticks(min, max, 2)
  return { min: valuesTicks[0]!, max: valuesTicks.at(-1)!, ticks: valuesTicks }
}
export function dashboardValueY(
  value: number,
  axis: NonNullable<ReturnType<typeof dashboardAxis>>,
) {
  return 24 + (1 - (value - axis.min) / (axis.max - axis.min)) * 116
}
export function dashboardLine(
  points: DashboardWeightPoint[],
  range: DashboardWeightRange,
  width: number,
  blockedDays: string[] = [],
  axis = dashboardAxis(points),
) {
  if (!axis) return ''
  return points
    .map((point, index) => {
      const previous = points[index - 1]
      const breakLine =
        !previous || blockedDays.some((day) => day > previous.day && day < point.day)
      return `${breakLine ? 'M' : 'L'}${dashboardDateX(point.day, range, width).toFixed(1)},${dashboardValueY(point.value, axis).toFixed(1)}`
    })
    .join(' ')
}
