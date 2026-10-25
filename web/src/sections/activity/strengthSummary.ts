import type { Progress } from './api'

export type ProgressSeries = Progress['series'][number]
export type ProgressSet = ProgressSeries['points'][number]
export type StrengthDay = { date: string; points: ProgressSet[] }

/**
 * Summaries keep each actual load/repetition pair. The API has no time within a day, so all
 * working sets of that day stay together rather than claiming one session was the latest.
 * Call this per server-separated series: equipment, variant, basis and implement count differ.
 */
export function strengthDays(series: ProgressSeries): {
  latest: StrengthDay | null
  previous: StrengthDay | null
} {
  const dates = [...new Set(series.points.map((point) => point.date))].sort().reverse()
  const at = (index: number): StrengthDay | null => {
    const date = dates[index]
    return date ? { date, points: series.points.filter((point) => point.date === date) } : null
  }
  return { latest: at(0), previous: at(1) }
}
