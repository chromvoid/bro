import { z } from '@/core/zod'

const TemperatureProjectionCommon = z.object({
  quality: z.array(z.string()),
  input_refs: z.array(
    z.object({
      id: z.string(),
      revision: z.number().int(),
      source_id: z.string(),
      hash: z.string(),
    }),
  ),
  points: z.array(
    z.object({
      token_offset: z.number().int(),
      slot: z.number().int(),
      offset_minutes: z.number(),
      value: z.number().finite(),
      unit: z.literal('°C'),
    }),
  ),
  ring_local_date: z.string(),
})
const TemperatureProjectionCounts = z.object({ vendor: z.number().int(), spot: z.number().int() })
const TemperatureProjectionValues = {
  layout: z.enum(['spot_byte_div10_plus20', 'vendor_byte_div10_plus32']),
  status: z.enum(['source_only', 'empty']),
  interval_minutes: z.number().int(),
  day_start: z.string(),
  as_of: z.string(),
  plausible_counts: TemperatureProjectionCounts,
  candidate_counts: TemperatureProjectionCounts,
  capture_local_date: z.string(),
}
const LegacySoulTemperatureInterpretation = TemperatureProjectionCommon.extend({
  ...TemperatureProjectionValues,
  definition_id: z.literal('soul-temperature-legacy25'),
  formula_version: z.literal('soul-1.0.1214-dex-z0-v1'),
  time_basis: z.literal('phone_capture_local_midnight'),
  trailing_bytes_ignored_by_soul: z.literal(2).optional(),
})
const Soul1230TemperatureCommon = TemperatureProjectionCommon.extend({
  definition_id: z.literal('soul1230-temperature-legacy25'),
  formula_version: z.literal('soul-1.0.1230-dex-B0-time-v1'),
  legacy_capture_projection: LegacySoulTemperatureInterpretation,
  trailing_bytes_ignored_by_soul: z.literal(2),
})
const Soul1230TemperatureInterpretation = z.discriminatedUnion('status', [
  Soul1230TemperatureCommon.extend({
    ...TemperatureProjectionValues,
    time_basis: z.literal('phone_sync_start_local_midnight'),
    parse_local_date: z.string(),
    missing: z.array(z.string()).length(0),
    time_context: z.object({
      version: z.literal('bro-soul1230-temperature-capture-v1'),
      sync_started_at: z.string(),
      parsed_at: z.string(),
      timezone: z.string(),
      request_id: z.string().min(1).max(200),
    }),
  }),
  Soul1230TemperatureCommon.extend({
    status: z.literal('unavailable'),
    points: z.array(z.never()).length(0),
    missing: z.union([
      z.tuple([z.literal('temperature_sync_start_context')]),
      z.tuple([z.literal('temperature_sync_start_context_invalid')]),
    ]),
    layout: z.null(),
    interval_minutes: z.null(),
    day_start: z.null(),
    as_of: z.null(),
    plausible_counts: z.null(),
    candidate_counts: z.null(),
    capture_local_date: z.null(),
    parse_local_date: z.null(),
    time_context: z.null(),
    time_basis: z.literal('unavailable'),
  }),
])
export const SoulTemperatureInterpretation = z.union([
  LegacySoulTemperatureInterpretation,
  Soul1230TemperatureInterpretation,
])
const RawReportCommon = z.looseObject({
  id: z.string(),
  user_id: z.string(),
  source_id: z.string(),
  external_id: z.string(),
  source_version: z.number().int(),
  revision: z.number().int(),
  source_hash: z.string(),
  received_at: z.string(),
  local_date: z.string(),
  timezone: z.string(),
  observed_at: z.string(),
  layout: z.string(),
  payload_hex: z.string().max(4096),
  details: z.unknown(),
  origin: z.record(z.string(), z.unknown()),
  quality: z.array(z.string()),
  source_name: z.string(),
  source_status: z.string(),
  is_current: z.boolean(),
  current_revision: z.number().int(),
  qualification: z.literal('source_only'),
})
export const RawRingReport = z.discriminatedUnion('metric', [
  RawReportCommon.extend({
    metric: z.literal('temperature'),
    namespace: z.literal('bulk'),
    opcode: z.literal(37),
    reason: z.literal('ambiguous_layout'),
    interpretation: SoulTemperatureInterpretation.nullish(),
  }),
  RawReportCommon.extend({
    metric: z.literal('workouts'),
    namespace: z.literal('core'),
    opcode: z.literal(19),
    reason: z.literal('workout_time_basis_unconfirmed'),
    time_basis: z.literal('first_phone_capture_day'),
    interpretation: z.null().nullish(),
    interpretation_reason: z.literal('source_time_and_units_unconfirmed').optional(),
  }),
])
export type RawRingReport = z.infer<typeof RawRingReport>
export const RawReportPage = z.object({
  from: z.string(),
  to: z.string(),
  items: z.array(RawRingReport),
  count: z.number().int().nonnegative(),
  next_cursor: z.string().nullable(),
  fingerprint: z.string(),
})
export type RawReportPage = z.infer<typeof RawReportPage>
export const rawReportKey = (report: RawRingReport) =>
  `${report.id}:${report.revision}:${report.source_version}`
export const coherentRawPages = (pages: RawReportPage[]) =>
  pages.every((page) => page.fingerprint === pages[0]?.fingerprint)
