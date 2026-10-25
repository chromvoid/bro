import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import { isIsoDay } from '@/core/dates'
import { KINDS, type ActivityKind } from './api'

const TABS = new Set(['overview', 'journal', 'progress', 'exercises'])
const METRICS = new Set(['steps', 'active_minutes', 'sessions', 'strength'])

/** Only Activity's own reading context can be carried through a session detail URL. */
export function activityReturnQuery(
  query: LocationQuery | LocationQueryRaw,
  today: string,
  fallbackDay?: string,
): Record<string, string> {
  const result: Record<string, string> = {}
  const tab = query.tab
  result.tab = typeof tab === 'string' && TABS.has(tab) ? tab : 'journal'
  for (const key of ['day', 'end'] as const) {
    const value = query[key]
    if (isIsoDay(value) && value <= today) result[key] = value
  }
  if (!result.day && isIsoDay(fallbackDay) && fallbackDay <= today) result.day = fallbackDay
  if (typeof query.period === 'string' && ['7', '30', '90'].includes(query.period))
    result.period = query.period
  if (typeof query.kind === 'string' && KINDS.includes(query.kind as ActivityKind))
    result.kind = query.kind
  if (typeof query.exercise === 'string' && query.exercise.trim()) result.exercise = query.exercise
  if (typeof query.metric === 'string' && METRICS.has(query.metric)) result.metric = query.metric
  return result
}
