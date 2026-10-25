import { z } from '@/core/zod'
import { amountText } from './api'
import { sensorAmount, type SensorMetric } from './sensors'
import type { DisplayUnits } from './displayUnits'

export type ArchivedMetric = 'steps' | 'sleep' | SensorMetric
const Instant = z.iso.datetime({ offset: true })
export const RecordVersionPage = z.object({
  id: z.string(),
  kind: z.enum(['steps', 'sleep']).optional(),
  metric: z.enum(['heart_rate', 'spo2', 'temperature', 'hrv', 'stress']).optional(),
  source_id: z.string().nullable(),
  source_name: z.string().nullable(),
  source_status: z.string().nullable(),
  current_revision: z.number().int().positive(),
  count: z.number().int().nonnegative(),
  fingerprint: z.string().min(1),
  versions: z.array(
    z.object({
      revision: z.number().int().positive(),
      // The saved snapshot is an open JSON document. Do not coerce amounts,
      // add current fields/defaults, or discard legacy/unknown fields here.
      snapshot: z.record(z.string(), z.unknown()),
      operation_id: z.string().nullable(),
      created_at: Instant.nullable(),
    }),
  ),
  annotation: z.looseObject({
    excluded: z.boolean(),
    corrected: z.boolean(),
    revision: z.number().int().nonnegative(),
    observation: z.unknown(),
  }),
  next_cursor: z.string().nullable(),
})
export type RecordVersionPage = z.infer<typeof RecordVersionPage>
export type SavedRecordVersion = RecordVersionPage['versions'][number]

export function coherentRecordVersions(pages: RecordVersionPage[]): boolean {
  const first = pages[0]
  if (!first) return true
  if (
    pages.some(
      (page) =>
        page.id !== first.id ||
        page.kind !== first.kind ||
        page.metric !== first.metric ||
        page.source_id !== first.source_id ||
        page.current_revision !== first.current_revision ||
        page.count !== first.count ||
        page.fingerprint !== first.fingerprint,
    )
  )
    return false
  const versions = pages.flatMap((page) => page.versions)
  return (
    versions.length <= first.count &&
    versions.every(
      (version, index) =>
        version.revision <= first.current_revision &&
        (index === 0 || version.revision < versions[index - 1]!.revision),
    )
  )
}

function text(snapshot: Record<string, unknown>, field: string) {
  return typeof snapshot[field] === 'string' ? snapshot[field] : undefined
}
function amount(value: unknown): string | null {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : null
}
function instant(snapshot: Record<string, unknown>, field: string) {
  const result = Instant.safeParse(snapshot[field])
  return result.success ? result.data : undefined
}
function object(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined
}

/** Readable projection only; the complete untouched snapshot remains available separately. */
export function versionDetails(version: SavedRecordVersion, sourceName: string | null) {
  const snapshot = version.snapshot
  return {
    id: text(snapshot, 'id'),
    external_id: text(snapshot, 'external_id'),
    source_id: text(snapshot, 'source_id'),
    source_name: sourceName,
    start_at: instant(snapshot, 'start_at'),
    end_at: instant(snapshot, 'end_at'),
    timezone: text(snapshot, 'timezone'),
    local_date: text(snapshot, 'local_date'),
    received_at: instant(snapshot, 'received_at'),
    revision: version.revision,
    algorithm_version: text(snapshot, 'algorithm_version'),
    method: text(snapshot, 'method'),
    origin: snapshot.origin,
    data: snapshot.data,
    value: amount(snapshot.value),
    reported_min: amount(snapshot.reported_min),
    reported_max: amount(snapshot.reported_max),
    unit: text(snapshot, 'unit'),
    quality: Array.isArray(snapshot.quality)
      ? snapshot.quality.filter((flag): flag is string => typeof flag === 'string')
      : undefined,
  }
}

export function versionValue(
  version: SavedRecordVersion,
  metric: ArchivedMetric,
  displayUnits: DisplayUnits,
): string | null {
  if (metric === 'steps' || metric === 'sleep')
    return amountText(metric, amount(object(version.snapshot.data)?.value), displayUnits)
  const unit = text(version.snapshot, 'unit')
  return unit ? sensorAmount(amount(version.snapshot.value), unit, displayUnits) : null
}

export function versionRange(version: SavedRecordVersion, displayUnits: DisplayUnits) {
  const unit = text(version.snapshot, 'unit')
  if (!unit) return null
  const minimum = sensorAmount(amount(version.snapshot.reported_min), unit, displayUnits)
  const maximum = sensorAmount(amount(version.snapshot.reported_max), unit, displayUnits)
  return minimum !== null || maximum !== null ? { minimum, maximum } : null
}
