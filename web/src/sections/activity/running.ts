/** Read-only Personal decisions; device workouts never become manual Activity writes. */
import { useInfiniteQuery, useQuery } from '@tanstack/vue-query'
import { computed, type Ref } from 'vue'
import { api } from '@/core/client'
import { z } from '@/core/zod'
import { SportPlan, WorkoutPauses } from './sports'
import { ResolvedPage, consistentPages, type Resolution } from '@/sections/personal/resolution'

const Amount = z
  .union([z.number(), z.string()])
  .nullish()
  .transform((value) => {
    if (value == null || value === '') return null
    const amount = Number(value)
    return Number.isFinite(amount) && amount >= 0 ? amount : null
  })
export const RunningEpisode = z.looseObject({
  id: z.string(),
  revision: z.number().int(),
  source_id: z.string().nullish(),
  source_name: z.string().nullish(),
  start_at: z.string(),
  end_at: z.string(),
  local_date: z.string().nullish(),
  timezone: z.string().nullish(),
  origin: z.looseObject({ source: z.string().nullish() }).nullish(),
  data: z.looseObject({
    sport_type: z.string(),
    duration_seconds: Amount,
    active_duration_seconds: Amount.optional(),
    sport_plan: SportPlan.nullish(),
    pauses: WorkoutPauses.optional(),
    steps: Amount,
    distance_meters: Amount,
    // A workout the server found in a strap's history: its heart rate, zones and load.
    heart_rate: z.looseObject({ mean: z.number(), max: z.number() }).nullish(),
    zone_seconds: z.array(z.number()).nullish(),
    trimp: Amount.optional(),
    effort: z.looseObject({ rest: z.string(), max: z.string(), basis: z.string() }).nullish(),
  }),
})
export type RunningEpisode = z.infer<typeof RunningEpisode>
export const WorkoutSummary = z.looseObject({
  count: Amount,
  known_duration_seconds: Amount,
  unknown_duration_count: Amount,
})
export type WorkoutEntry = RunningEpisode & { date: string; resolution: Resolution }
export type RunningRange = { from: string; to: string }
export type RunningMetric = 'running_steps' | 'running_workouts'
export type WorkoutMetric = 'running_workouts' | 'workouts'

/** The selected series is paginated; bounded daily explanations are never the full journal.
 * Runs are `running_workouts`; every other sport, a found workout included, is `workouts`. */
export function workoutEntries(
  input: ResolvedPage | ResolvedPage[] | undefined,
  kind: WorkoutMetric = 'running_workouts',
): WorkoutEntry[] {
  const pages = !input ? [] : Array.isArray(input) ? input : [input]
  if (!consistentPages(pages)) return []
  const entries = new Map<string, WorkoutEntry>()
  for (const page of pages) {
    for (const value of page.series) {
      if (value.kind !== kind || value.shape !== 'workout_report') continue
      const report = RunningEpisode.safeParse(value)
      if (
        !report.success ||
        (report.data.data.sport_type === 'running') !== (kind === 'running_workouts') ||
        !Number.isFinite(Date.parse(report.data.start_at))
      )
        continue
      const days = page.daily.filter((row) => row.kind === kind)
      const day =
        days.find((row) =>
          row.resolution.selected.some(
            (fact) => fact.id === report.data.id && fact.revision === report.data.revision,
          ),
        ) ?? days.find((row) => row.date === report.data.local_date)
      // Series contains selected reports. Source dates survive profile timezone changes;
      // use them when the daily reference list has been compacted, never recalculate a date.
      if (day)
        entries.set(report.data.id, { ...report.data, date: day.date, resolution: day.resolution })
    }
  }
  return [...entries.values()].sort((a, b) => b.start_at.localeCompare(a.start_at))
}

export function useRunningData(
  range: Ref<RunningRange>,
  enabled: Ref<boolean>,
  stepsEnabled: Ref<boolean> = enabled,
) {
  const steps = useQuery({
    queryKey: computed(() => ['personal', 'resolved-running', 'running_steps', range.value]),
    enabled: stepsEnabled,
    queryFn: async ({ signal }) => {
      const query = new URLSearchParams({ ...range.value, metric: 'running_steps', limit: '1' })
      return ResolvedPage.parse(await api.get(`/v1/personal/resolved?${query}`, signal))
    },
  })
  const reportQuery = (metric: WorkoutMetric) =>
    useInfiniteQuery({
      queryKey: computed(() => ['personal', 'resolved-running', metric, range.value]),
      enabled,
      initialPageParam: null as string | null,
      queryFn: async ({ signal, pageParam }) => {
        const query = new URLSearchParams({
          ...range.value,
          metric,
          limit: '100',
          ...(pageParam ? { cursor: pageParam } : {}),
        })
        const page = ResolvedPage.parse(await api.get(`/v1/personal/resolved?${query}`, signal))
        for (const row of page.series)
          if (row.kind === metric && row.shape === 'workout_report') RunningEpisode.parse(row)
        return page
      },
      getNextPageParam: (page) => page.next_cursor ?? undefined,
    })
  const workouts = reportQuery('running_workouts')
  // Other sports, the workouts found in a strap's history among them; the journal shows them too.
  const others = reportQuery('workouts')
  const pages = computed(() => workouts.data.value?.pages ?? [])
  const otherPages = computed(() => others.data.value?.pages ?? [])
  const consistent = computed(() => consistentPages(pages.value))
  const workoutPage = computed(() => workouts.data.value?.pages[0])
  const reports = computed(() =>
    [...workoutEntries(pages.value), ...workoutEntries(otherPages.value, 'workouts')].sort((a, b) =>
      b.start_at.localeCompare(a.start_at),
    ),
  )
  return { steps, workouts, others, reports, workoutPage, consistent }
}

export type JournalEntry =
  | {
      type: 'manual'
      id: string
      date: string
      started_at: string | null
      session: import('./api').ActivitySession
    }
  | { type: 'device'; id: string; date: string; started_at: string; report: WorkoutEntry }

/** The time a logged session took, when it has a start and a length. */
export function sessionWindow(
  session: Pick<import('./api').ActivitySession, 'started_at' | 'duration_minutes'>,
): { start: number; end: number } | null {
  const start = session.started_at === null ? NaN : Date.parse(session.started_at)
  const minutes = Number(session.duration_minutes)
  if (!Number.isFinite(start) || !(minutes > 0)) return null
  return { start, end: start + minutes * 60_000 }
}

export function journalGroups(
  sessions: import('./api').ActivitySession[],
  reports: WorkoutEntry[],
  kind: string | null,
): Array<[string, JournalEntry[]]> {
  const entries: JournalEntry[] = sessions.map((session) => ({
    type: 'manual',
    id: `manual:${session.id}`,
    date: session.date,
    started_at: session.started_at,
    session,
  }))
  // A device report during a logged session is that session's heart, shown on its page.
  const logged = sessions
    .map(sessionWindow)
    .filter((window): window is { start: number; end: number } => window !== null)
  const during = (report: WorkoutEntry) =>
    logged.some(
      (window) =>
        Date.parse(report.start_at) < window.end && window.start < Date.parse(report.end_at),
    )
  if (kind === null || kind === 'running')
    entries.push(
      ...reports
        .filter((report) => kind === null || report.data.sport_type === 'running')
        .filter((report) => !during(report))
        .map((report): JournalEntry => ({
          type: 'device',
          id: `device:${report.id}`,
          date: report.date,
          started_at: report.start_at,
          report,
        })),
    )
  const groups = new Map<string, JournalEntry[]>()
  for (const entry of entries) groups.set(entry.date, [...(groups.get(entry.date) ?? []), entry])
  return [...groups]
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([day, items]) => [
      day,
      items.sort(
        (a, b) =>
          (b.started_at ?? '').localeCompare(a.started_at ?? '') || a.id.localeCompare(b.id),
      ),
    ])
}
