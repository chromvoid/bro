import { z } from '@/core/zod'
import { RunningEpisode } from './running'

export const ACTIVITY_REPORT_KINDS = ['running_steps', 'running_workouts', 'workouts'] as const
export type ActivityReportKind = (typeof ACTIVITY_REPORT_KINDS)[number]
export type ActivityReportFilter = ActivityReportKind | 'active_energy' | 'distance'
export function isActivityReportKind(value: unknown): value is ActivityReportKind {
  return typeof value === 'string' && (ACTIVITY_REPORT_KINDS as readonly string[]).includes(value)
}
export const ActivityReport = z.looseObject({
  id: z.string(),
  source_id: z.string(),
  source_name: z.string().nullish(),
  revision: z.number().int(),
  source_version: z.number().int(),
  kind: z.string(),
  shape: z.string(),
  local_date: z.string(),
  timezone: z.string(),
  observed_at: z.string(),
  start_at: z.string().nullable(),
  end_at: z.string().nullable(),
  data: z.record(z.string(), z.unknown()),
  origin: z.record(z.string(), z.unknown()),
  quality: z.array(z.string()),
  is_current: z.boolean().optional(),
  current_revision: z.number().int().optional(),
  annotation_revision: z.number().int().optional(),
  excluded: z.boolean().optional(),
  resolution_disposition: z.string().nullish(),
})
export type ActivityReport = z.infer<typeof ActivityReport>
export const ActivityReportPage = z.object({
  from: z.string(),
  to: z.string(),
  items: z.array(ActivityReport),
  next_cursor: z.string().nullable(),
  fingerprint: z.string(),
})
export type ActivityReportPage = z.infer<typeof ActivityReportPage>

/** Immutable versions have the same record ID, so both revision and source version identify a row. */
export const activityReportKey = (report: ActivityReport) =>
  `${report.id}:${report.revision}:${report.source_version}`
const currentArchiveFields = new Set([
  'source_name',
  'source_status',
  'is_current',
  'current_revision',
  'annotation_id',
  'annotation_revision',
  'excluded',
  'resolution_disposition',
])
/** Preserve saved fields before display projections; live annotations are not historical facts. */
export function activityReportSnapshot(report: ActivityReport): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(report).filter(([field]) => !currentArchiveFields.has(field)),
  )
}
export function archiveWorkout(report: ActivityReport): (RunningEpisode & { date: string }) | null {
  if (report.shape !== 'workout_report') return null
  const parsed = RunningEpisode.safeParse(report)
  return parsed.success ? { ...parsed.data, date: report.local_date } : null
}
export function coherentReportPages(pages: ActivityReportPage[]): boolean {
  return pages.every((page) => page.fingerprint === pages[0]?.fingerprint)
}
