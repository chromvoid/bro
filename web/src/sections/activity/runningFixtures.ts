import { ResolvedPage } from '@/sections/personal/resolution'

export const RUNNING_DAY = '2026-10-06'
export function runningDecision(overrides: Record<string, unknown> = {}) {
  return {
    status: 'partial',
    fingerprint: 'synthetic-running-inputs',
    resolver_version: 'resolution-running-v1',
    selected: [
      {
        id: 'snapshot',
        revision: 1,
        source_id: 'ring',
        source_name: 'Synthetic ring',
        contribution: 420,
      },
    ],
    alternatives: [],
    protocol: { verified: false, flags: ['protocol_unverified'] },
    data: {
      shape: 'day_counter_snapshot',
      observed_at: `${RUNNING_DAY}T08:00:00Z`,
      value: 420,
      local_date: RUNNING_DAY,
    },
    coverage: { basis: 'daily_counter_snapshot', complete: false },
    ...overrides,
  }
}
export function runningPage(daily: unknown[] = []) {
  return ResolvedPage.parse({
    from: '2026-10-01',
    to: RUNNING_DAY,
    timezone: 'Europe/Moscow',
    computed_at: `${RUNNING_DAY}T12:00:00Z`,
    fingerprint: 'synthetic-running-page',
    daily,
    series: daily.flatMap((row) => {
      const resolution = (row as { resolution?: { episodes?: unknown[] } }).resolution
      return resolution?.episodes ?? []
    }),
    next_cursor: null,
  })
}
export function stepDay(
  value: number | string | null = 420,
  overrides: Record<string, unknown> = {},
) {
  return {
    date: RUNNING_DAY,
    kind: 'running_steps',
    status: 'available',
    value,
    unit: 'steps',
    complete: false,
    resolution: runningDecision(),
    ...overrides,
  }
}
export function workoutEpisode(id = 'workout', overrides: Record<string, unknown> = {}) {
  return {
    id,
    revision: 1,
    source_id: 'ring',
    source_name: 'Synthetic ring',
    shape: 'workout_report',
    kind: 'running_workouts',
    local_date: RUNNING_DAY,
    timezone: 'Europe/Moscow',
    start_at: `${RUNNING_DAY}T07:00:00Z`,
    end_at: `${RUNNING_DAY}T07:30:00Z`,
    data: { sport_type: 'running', duration_seconds: 1800, steps: 3000, distance_meters: 5000 },
    ...overrides,
  }
}
export function workoutDay(
  episodes: unknown[] = [workoutEpisode()],
  overrides: Record<string, unknown> = {},
) {
  return {
    date: RUNNING_DAY,
    kind: 'running_workouts',
    status: 'available',
    value: episodes.length,
    unit: 'workouts',
    resolution: runningDecision({
      status: 'available',
      selected: episodes.map((episode) => {
        const report = episode as {
          id: string
          revision: number
          source_id: string
          source_name: string
        }
        return {
          id: report.id,
          revision: report.revision,
          source_id: report.source_id,
          source_name: report.source_name,
        }
      }),
      episodes,
      summary: {
        count: episodes.length,
        known_duration_seconds: 1800 * episodes.length,
        unknown_duration_count: 0,
      },
    }),
    ...overrides,
  }
}
