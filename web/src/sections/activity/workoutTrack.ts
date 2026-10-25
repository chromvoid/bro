import { i18n } from '@/i18n'
import { z } from '@/core/zod'

export const TrackPoint = z.looseObject({
  at: z.string(),
  latitude: z.number().finite().min(-90).max(90),
  longitude: z.number().finite().min(-180).max(180),
  accuracy_meters: z.number().finite().nonnegative().nullable(),
  quality: z.string(),
  provider: z.string().nullish(),
  paused: z.boolean().optional(),
})
export type TrackPoint = z.infer<typeof TrackPoint>
export const TrackSegment = z.object({
  context: z.enum(['running', 'workout', 'recovery']),
  quality: z.string(),
  points: z.array(TrackPoint),
})
export type TrackSegment = z.infer<typeof TrackSegment>
const ContextSummary = z.looseObject({
  distance_meters: z.number().finite().nonnegative().nullable(),
  usable_points: z.number().int().nonnegative(),
  reported_points: z.number().int().nonnegative(),
  gap_count: z.number().int().nonnegative(),
  coverage_seconds: z.number().finite().nonnegative(),
  quality: z.unknown(),
  paused_points: z.number().int().nonnegative().optional(),
  paused_seconds: z.number().nonnegative().optional(),
})
const SnapshotContextSummary = ContextSummary.extend({
  usable_segments: z.number().int().nonnegative().optional(),
  paused_seconds: z.number().finite().nonnegative().optional(),
})

/** Saved snapshots retain historical omissions and unknown fields without adding defaults. */
export const WorkoutTrackSummarySnapshot = z.looseObject({
  algorithm_version: z.string(),
  unit: z.literal('m'),
  value_meters: z.number().finite().nonnegative().nullable(),
  contexts: z.looseObject({
    running: SnapshotContextSummary.optional(),
    workout: SnapshotContextSummary.optional(),
    recovery: SnapshotContextSummary,
  }),
  speed_limit_meters_per_second: z.number().finite().nonnegative().optional(),
  max_segment_seconds: z.number().finite().nonnegative().optional(),
  max_accuracy_meters: z.number().finite().nonnegative().optional(),
  pause_policy: z.string().optional(),
  report_ref: z.looseObject({
    id: z.string(),
    revision: z.number().int().positive(),
  }),
  input_refs: z.array(
    z.looseObject({
      id: z.string(),
      sequence: z.number().int().nonnegative(),
      hash: z.string(),
    }),
  ),
})
export type WorkoutTrackSummarySnapshot = z.infer<typeof WorkoutTrackSummarySnapshot>
export const WorkoutTrackSummaryVersionsPage = z.looseObject({
  workout_id: z.string(),
  source_id: z.string(),
  items: z.array(
    z.looseObject({
      revision: z.number().int().positive(),
      snapshot: WorkoutTrackSummarySnapshot,
      is_latest: z.boolean(),
    }),
  ),
  latest_revision: z.number().int().positive().nullable(),
  fingerprint: z.string().min(1),
  next_cursor: z.string().min(1).nullable(),
})
export type WorkoutTrackSummaryVersionsPage = z.infer<typeof WorkoutTrackSummaryVersionsPage>

export function trackQualityText(value: unknown): string {
  const names: Record<string, string> = {
    gps_estimate: i18n.global.t('workoutTrack.qualityFlags.gps_estimate'),
    paused: i18n.global.t('workoutTrack.qualityFlags.paused'),
    partial: i18n.global.t('workoutTrack.qualityFlags.partial'),
    no_usable_segments: i18n.global.t('workoutTrack.qualityFlags.no_usable_segments'),
    missing_chunk: i18n.global.t('workoutTrack.qualityFlags.missing_chunk'),
    gap: i18n.global.t('workoutTrack.qualityFlags.gap'),
    approximate: i18n.global.t('workoutTrack.qualityFlags.approximate'),
    unusable_fix: i18n.global.t('workoutTrack.qualityFlags.unusable_fix'),
    sparse: i18n.global.t('workoutTrack.qualityFlags.sparse'),
    implausible_segment: i18n.global.t('workoutTrack.qualityFlags.implausible_segment'),
  }
  if (!Array.isArray(value))
    return JSON.stringify(value) ?? i18n.global.t('workoutTrack.unknownSegments')
  if (!value.length) return i18n.global.t('workoutTrack.noQualityLimits')
  return value
    .map((item) => (typeof item === 'string' ? (names[item] ?? item) : JSON.stringify(item)))
    .join(', ')
}
export const WorkoutTrackPage = z.looseObject({
  workout_id: z.string(),
  source_id: z.string(),
  workout: z.looseObject({
    start_at: z.string(),
    end_at: z.string(),
    timezone: z.string(),
    status: z.string(),
    trigger: z.string(),
    recovery_end_at: z.string().nullish(),
    stop_reason: z.string().nullish(),
  }),
  chunks: z.array(z.record(z.string(), z.unknown())),
  segments: z.array(TrackSegment),
  fingerprint: z.string(),
  summary: z
    .looseObject({
      algorithm_version: z.string(),
      value_meters: z.number().finite().nonnegative().nullable(),
      unit: z.literal('m'),
      contexts: z.object({
        running: ContextSummary.optional(),
        workout: ContextSummary.optional(),
        recovery: ContextSummary,
      }),
      input_refs: z.array(z.unknown()),
      fingerprint: z.string(),
    })
    .nullable(),
  next_cursor: z.string().nullable(),
})
export type WorkoutTrackPage = z.infer<typeof WorkoutTrackPage>

/** A preserved pause is evidence, not a drawable route joining either side of it. */
export function drawableSegments(segments: TrackSegment[]): TrackSegment[] {
  return segments.flatMap((segment) => {
    if (segment.quality === 'paused') return []
    if (segment.quality !== 'valid')
      return segment.points
        .filter((point) => !point.paused)
        .map((point) => ({ ...segment, points: [point] }))
    const parts: TrackSegment[] = []
    let points: TrackPoint[] = []
    const flush = () => {
      if (points.length) parts.push({ ...segment, points })
      points = []
    }
    for (const point of segment.points) {
      if (point.paused) flush()
      else points.push(point)
    }
    flush()
    return parts
  })
}

/** Web Mercator is presentation only; distance and quality are deterministic server results. */
export function mercator(point: Pick<TrackPoint, 'latitude' | 'longitude'>, zoom: number) {
  const latitude = (Math.max(-85.05112878, Math.min(85.05112878, point.latitude)) * Math.PI) / 180
  const size = 256 * 2 ** zoom
  return {
    x: ((point.longitude + 180) / 360) * size,
    y: Math.max(
      0,
      Math.min(
        size,
        ((1 - Math.log(Math.tan(latitude) + 1 / Math.cos(latitude)) / Math.PI) / 2) * size,
      ),
    ),
  }
}

/** Unwrap only a single server segment. A declared gap never becomes a connecting line. */
export function projectSegment(points: TrackPoint[], zoom: number, anchorX?: number) {
  const size = 256 * 2 ** zoom
  let previous = anchorX
  return points.map((point) => {
    const value = mercator(point, zoom)
    if (previous !== undefined) value.x += Math.round((previous - value.x) / size) * size
    previous = value.x
    return value
  })
}
export function trackView(points: TrackPoint[], width = 640, height = 360) {
  if (!points.length) return null
  for (let zoom = 17; zoom >= 1; zoom--) {
    const projected = projectSegment(points, zoom)
    const bounds = projected.reduce(
      (result, point) => ({
        left: Math.min(result.left, point.x),
        right: Math.max(result.right, point.x),
        top: Math.min(result.top, point.y),
        bottom: Math.max(result.bottom, point.y),
      }),
      { left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity },
    )
    const { left, right, top, bottom } = bounds
    if ((right - left <= width - 80 && bottom - top <= height - 80) || zoom === 1)
      return { zoom, x: (left + right) / 2, y: (top + bottom) / 2 }
  }
  return null
}
/** Only tiles that intersect this SVG viewport are fetched. Longitude wraps at the world edge. */
export function visibleTiles(
  view: { zoom: number; x: number; y: number },
  width = 640,
  height = 360,
) {
  const left = view.x - width / 2
  const top = view.y - height / 2
  const count = 2 ** view.zoom
  const tiles: Array<{ key: string; href: string; x: number; y: number }> = []
  for (let row = Math.floor(top / 256); row <= Math.floor((top + height - 1) / 256); row++) {
    if (row < 0 || row >= count) continue
    for (
      let column = Math.floor(left / 256);
      column <= Math.floor((left + width - 1) / 256);
      column++
    ) {
      const tileX = ((column % count) + count) % count
      tiles.push({
        key: `${view.zoom}/${column}/${row}`,
        href: `/v1/maps/osm/${view.zoom}/${tileX}/${row}.png`,
        x: column * 256 - left,
        y: row * 256 - top,
      })
    }
  }
  return tiles
}
