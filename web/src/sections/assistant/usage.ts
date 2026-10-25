/** Public usage facts only. Missing provider measurements never become zero or inferred totals. */
import { z } from '@/core/zod'

const Count = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER)
const Milliseconds = z.number().finite().nonnegative().max(Number.MAX_SAFE_INTEGER)
const measured = (schema: typeof Count | typeof Milliseconds) =>
  schema.nullish().transform((value) => value ?? null)

export const UsageSummary = z.object({
  calls: measured(Count),
  input_total_tokens: measured(Count),
  input_uncached_tokens: measured(Count),
  cache_read_tokens: measured(Count),
  cache_write_tokens: measured(Count),
  output_tokens: measured(Count),
  total_tokens: measured(Count),
  // This is the sum of model call durations, not the request's wall-clock latency.
  model_duration_ms: measured(Milliseconds),
  // From the start of the turn to the first text the person saw, as in the turn's trace;
  // not one model call's own time to first text.
  first_public_text_ms: measured(Milliseconds),
})
export type UsageSummary = z.infer<typeof UsageSummary>

/** An optional malformed summary must not hide the turn's durable results. */
export function parseUsage(value: unknown): UsageSummary | null {
  const parsed = UsageSummary.safeParse(value)
  return parsed.success ? parsed.data : null
}

/** Compatible with older turn envelopes that have no usage property. */
export const UsageField = z.unknown().optional().transform(parseUsage)

const FIELDS = [
  { field: 'calls', label: 'calls', unit: 'count' },
  { field: 'input_total_tokens', label: 'inputTotal', unit: 'count' },
  { field: 'input_uncached_tokens', label: 'inputUncached', unit: 'count' },
  { field: 'cache_read_tokens', label: 'cacheRead', unit: 'count' },
  { field: 'cache_write_tokens', label: 'cacheWrite', unit: 'count' },
  { field: 'output_tokens', label: 'output', unit: 'count' },
  { field: 'total_tokens', label: 'total', unit: 'count' },
  { field: 'model_duration_ms', label: 'modelDuration', unit: 'milliseconds' },
  { field: 'first_public_text_ms', label: 'firstPublicText', unit: 'milliseconds' },
] as const satisfies ReadonlyArray<{
  field: keyof UsageSummary
  label: string
  unit: 'count' | 'milliseconds'
}>

export function usageRows(usage: UsageSummary | null | undefined) {
  return usage ? FIELDS.map((row) => ({ ...row, value: usage[row.field] })) : []
}

/** Null remains distinct from a measured zero, including for durations. */
export function formatUsageNumber(value: number | null, locale: string): string | null {
  return value === null
    ? null
    : new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(value)
}
