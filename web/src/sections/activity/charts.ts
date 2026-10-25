import type { Point } from '@/components/chart/scale'
import type { History } from '@/sections/personal/api'
import type { Progress, Summary } from './api'

export const atDay = (date: string) => Date.parse(`${date}T00:00:00Z`)
export const shortDate = (timestamp: number) =>
  new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', timeZone: 'UTC' }).format(
    timestamp,
  )

/** The same period in digits, for a narrow row: «12.05–10.06» (its words stay for screen readers). */
export function rangeDigits(from: string, to: string): string {
  const short = (day: string) => `${day.slice(8, 10)}.${day.slice(5, 7)}`
  return `${short(from)}–${short(to)}`
}

/** Whether a period holds any manual session. */
export function hasSessions(summary: Summary | undefined): boolean {
  return (summary?.totals.sessions ?? 0) > 0
}

/** Only available observations become points; absent/conflicting days keep gaps, zero is real. */
export function dailyMetric(
  history: History | undefined,
  kind: 'steps' | 'active_minutes',
): Point[] {
  return (history?.daily ?? [])
    .filter(
      (day) =>
        day.kind === kind &&
        day.status === 'available' &&
        day.resolution?.status !== 'conflict' &&
        day.value !== null &&
        String(day.value).trim() !== '' &&
        Number.isFinite(Number(day.value)),
    )
    .map((day) => ({ x: atDay(day.date), y: Number(day.value) }))
    .sort((a, b) => a.x - b.x)
}
export function summaryPoints(
  summary: Summary | undefined,
  field: 'sessions' | 'duration_minutes' | 'working_sets',
  kind?: string,
): Point[] {
  return (summary?.daily ?? []).flatMap((day) => {
    if (
      field === 'duration_minutes' &&
      !kind &&
      day.sessions > 0 &&
      day.missing_duration_sessions >= day.sessions
    )
      return []
    const value = kind ? day.distance_km_by_kind[kind] : day[field]
    return value === null || value === undefined ? [] : [{ x: atDay(day.date), y: Number(value) }]
  })
}
/** A day has one chart point; its accompanying rows retain all sets and repetitions. */
export function progressPoints(
  series: Progress['series'][number],
  field: 'load_kg' | 'repetitions',
): Point[] {
  const days = new Map<string, number>()
  for (const point of series.points) {
    const value = point[field]
    if (value === null || !Number.isFinite(Number(value))) continue
    const previous = days.get(point.date)
    const number = Number(value)
    const lower = field === 'load_kg' && series.snapshot.load_basis === 'assistance'
    days.set(
      point.date,
      previous === undefined
        ? number
        : lower
          ? Math.min(previous, number)
          : Math.max(previous, number),
    )
  }
  return [...days]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, y]) => ({ x: atDay(date), y }))
}
