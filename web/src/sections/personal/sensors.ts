import { shiftDay, zonedInstant } from '@/core/dates'
import { formatAmount } from '@/core/numbers'
import { z } from '@/core/zod'
import { temperatureText, type DisplayUnits } from './displayUnits'

export const SENSOR_METRICS = ['heart_rate', 'spo2', 'temperature', 'hrv', 'stress'] as const
export type SensorMetric = (typeof SENSOR_METRICS)[number]
export function isSensorMetric(value: unknown): value is SensorMetric {
  return typeof value === 'string' && (SENSOR_METRICS as readonly string[]).includes(value)
}
const Amount = z
  .union([z.string(), z.number()])
  .nullable()
  .transform((value) => (value === null ? null : String(value)))
export const SensorSample = z.looseObject({
  id: z.string(),
  source_id: z.string(),
  source_name: z.string(),
  external_id: z.string(),
  metric: z.enum(SENSOR_METRICS),
  start_at: z.string(),
  end_at: z.string(),
  timezone: z.string(),
  local_date: z.string().nullable(),
  value: Amount,
  reported_min: Amount,
  reported_max: Amount,
  unit: z.string(),
  origin: z.record(z.string(), z.unknown()),
  quality: z.array(z.string()),
  revision: z.number().int(),
  excluded: z.boolean().default(false),
  annotation_revision: z.number().int().default(0),
})
export type SensorSample = z.infer<typeof SensorSample>
export const SensorPage = z.object({
  items: z.array(SensorSample),
  next_cursor: z.string().nullable(),
  fingerprint: z.string().min(1),
  count: z.number().int().nonnegative(),
  from: z.string(),
  to: z.string(),
})
export type SensorPage = z.infer<typeof SensorPage>

/** Raw history also represents one snapshot, including its exclusion annotations. */
export function coherentSensorPages(pages: SensorPage[]) {
  const first = pages[0]
  if (!first) return true
  const items = pages.flatMap((page) => page.items)
  return (
    pages.every(
      (page) =>
        page.fingerprint === first.fingerprint &&
        page.count === first.count &&
        page.from === first.from &&
        page.to === first.to,
    ) &&
    items.length <= first.count &&
    new Set(items.map((item) => item.id)).size === items.length
  )
}

const DeclaredCapabilities = z.object({
  captured_at: z.string(),
  opcode: z.number().int(),
  payload_hex: z.string(),
  flags: z.record(z.string(), z.boolean()),
  source_values: z.record(z.string(), z.number().int()),
  identity: z.object({ hardware: z.string(), firmware: z.string() }),
  decoder_version: z.string(),
  evidence_ref: z.string(),
  validation_status: z.literal('declared_only'),
})
export const StoredRingDeviceState = z.looseObject({
  observed_at: z.string(),
  revision: z.number().int(),
  received_at: z.string(),
  stale: z.boolean(),
  battery_percent: z.number().int().min(0).max(100).nullish(),
  charging: z.boolean().nullish(),
  battery_observed_at: z.string().nullish(),
  charging_observed_at: z.string().nullish(),
  battery_stale: z.boolean().nullish(),
  charging_stale: z.boolean().nullish(),
  firmware: z.string().optional(),
  hardware: z.string().optional(),
  capabilities: z
    .record(
      z.string(),
      z.object({
        status: z.enum(['supported', 'unsupported', 'unknown']),
        evidence_ref: z.string().optional(),
        reason: z.string().optional(),
        readable: z.boolean().optional(),
        writable: z.boolean().optional(),
      }),
    )
    .default({}),
  declared_capabilities: z
    .record(z.string(), DeclaredCapabilities)
    .refine((value) => Object.keys(value).every((key) => key === 'core_01' || key === 'core_3c'))
    .default({}),
  last_confirmed_settings: z
    .record(
      z.string(),
      z.object({
        value: z.unknown(),
        confirmed_at: z.string(),
        readback: z.literal(true),
        action: z.string().optional(),
        evidence_ref: z.string().optional(),
      }),
    )
    .default({}),
})
export type StoredRingDeviceState = z.infer<typeof StoredRingDeviceState>

export const Rings = z.object({
  rings: z.array(
    z.looseObject({
      collector_id: z.string(),
      source_id: z.string(),
      name: z.string(),
      profile: z.string(),
      generation: z.number().int(),
      enabled: z.boolean(),
      last_received_at: z.string().nullable(),
      cursor: z.unknown(),
      error: z.string().nullable(),
      status: z.enum(['connected', 'unavailable', 'disabled']),
      device_state: StoredRingDeviceState.nullish(),
      metrics: z
        .array(
          z.object({
            metric: z.string(),
            status: z.enum(['available', 'unverified', 'missing', 'unsupported']),
            last_measured_at: z.string().nullable(),
            last_received_at: z.string().nullable(),
            coverage: z.object({
              status: z.enum(['reported_window', 'partial', 'unknown']),
              shape: z.string(),
              start_at: z.string().nullable(),
              end_at: z.string().nullable(),
              quality: z.array(z.string()),
            }),
            import_status: z.string(),
            last_capture_at: z.iso.datetime({ offset: true }).nullable().optional(),
            capture_status: z.enum(['complete', 'empty', 'failed']).nullable().optional(),
            capture_scope: z
              .enum(['day', 'history', 'live', 'workout', 'spot'])
              .nullable()
              .optional(),
            capture_date: z.iso.date().nullable().optional(),
            capture_context: z
              .enum(['background', 'running', 'workout', 'recovery', 'manual'])
              .nullable()
              .optional(),
            capture_error_code: z.string().max(64).nullable().optional(),
            raw_report_count: z.number().int().nonnegative().optional(),
            original_kind: z.string().optional(),
            reason: z
              .enum([
                'no_observation',
                'unsupported_evidenced',
                'source_unavailable',
                'protocol_unverified',
                'semantic_unknown',
                'ambiguous_layout',
                'workout_time_basis_unconfirmed',
              ])
              .nullable(),
          }),
        )
        .optional(),
    }),
  ),
})

/** End is exclusive; calendar boundaries follow the user's zone, including clock changes. */
export function sensorRange(from: string, to: string, timezone: string) {
  return {
    from: zonedInstant(`${from}T00:00`, timezone)!,
    to: zonedInstant(`${shiftDay(to, 1)}T00:00`, timezone)!,
  }
}
export function sensorAmount(
  value: string | null,
  unit: string,
  displayUnits: DisplayUnits = 'metric',
): string | null {
  if (value === null || value.trim() === '' || !Number.isFinite(Number(value))) return null
  if (unit === 'celsius' || unit === '°C') return temperatureText(value, displayUnits)
  const label =
    unit === 'bpm'
      ? 'уд/мин'
      : unit === 'celsius' || unit === '°C'
        ? '°C'
        : unit === 'ms'
          ? 'мс'
          : unit === 'index'
            ? 'индекс'
            : unit
  return `${formatAmount(Number(value), 2)} ${label}`
}
