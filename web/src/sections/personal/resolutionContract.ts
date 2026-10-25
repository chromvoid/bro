/** Pure resolver response contracts, shared with read-only browser fixtures. */
import { z } from '@/core/zod'

const Amount = z
  .union([z.string(), z.number()])
  .nullish()
  .transform((value) => (value == null ? null : String(value)))
const Fact = z.looseObject({
  id: z.string(),
  revision: z.number().optional(),
  source_id: z.string().nullish(),
  source_name: z.string().nullish(),
  contribution: Amount,
  reason: z.string().optional(),
  start_at: z.string().nullish(),
  end_at: z.string().nullish(),
  superseded_by: z.string().optional(),
})
export const SleepStage = z.looseObject({
  start_at: z.string(),
  end_at: z.string(),
  stage: z.string(),
})
export const SleepSession = Fact.extend({
  start_at: z.string(),
  end_at: z.string(),
  asleep_minutes: Amount,
  stages: z.array(SleepStage).default([]),
  position_changes: z.array(z.string()).default([]),
})
export type SleepSession = z.infer<typeof SleepSession>
const Seconds = z.union([z.number(), z.string()]).transform(Number).refine(Number.isFinite)
const OptionalMinutes = Amount.transform((value) => (value === null ? null : Number(value)))
const SleepForecast = z.looseObject({
  status: z.string(),
  algorithm_version: z.string(),
  estimated_asleep_minutes: OptionalMinutes,
  estimated_missing_minutes: OptionalMinutes,
  reason: z.string().nullish(),
  training_nights: z.number(),
  validation: z
    .looseObject({
      nights: z.number(),
      mae_minutes: Seconds,
      p90_error_minutes: Seconds,
      baseline_mae_minutes: Seconds,
      reference: z.string(),
    })
    .nullish(),
})
const SleepAnalysisEpisode = z.looseObject({
  start_at: z.string(),
  end_at: z.string(),
  status: z.string(),
  window_minutes: Seconds,
  asleep_minutes: Seconds,
  awake_minutes: Seconds,
  unknown_minutes: Seconds,
  conflict_minutes: Seconds,
  classification_coverage: Seconds,
  complete_asleep_minutes: OptionalMinutes,
  bounds: z.looseObject({ min_minutes: Seconds, max_minutes: Seconds }).nullable(),
  stage_minutes: z.record(z.string(), Seconds),
  flags: z.array(z.string()),
  forecast: SleepForecast,
  segments: z.array(
    z.looseObject({
      start_at: z.string(),
      end_at: z.string(),
      state: z.string(),
      stage: z.string().nullable(),
      reason: z.string(),
      evidence: z.array(Fact),
      selected_refs: z.array(Fact),
      unknown_codes: z.array(z.string()),
    }),
  ),
  reports: z.array(Fact.extend({ reported_asleep_minutes: OptionalMinutes })),
  /** Instants a device saw the sleeper turn. */
  position_changes: z.array(z.string()).default([]),
  /** Night figures; a figure the evidence cannot carry is null. */
  night: z
    .looseObject({
      onset_at: z.string(),
      final_wake_at: z.string(),
      midpoint_at: z.string(),
      latency_minutes: OptionalMinutes,
      efficiency: OptionalMinutes,
      waso_minutes: OptionalMinutes,
      awakenings: z.number().nullable(),
      turns_per_hour: OptionalMinutes,
      heart_rate: z
        .looseObject({ lowest: Seconds, mean: Seconds, readings: z.number() })
        .nullable(),
      hrv: z.looseObject({ mean: Seconds, readings: z.number() }).nullable(),
    })
    .nullish(),
})
export const SleepAnalysis = z.looseObject({
  version: z.string(),
  fingerprint: z.string(),
  status: z.string(),
  computed_at: z.string(),
  episodes: z.array(SleepAnalysisEpisode),
  summary: z.looseObject({
    window_minutes: Seconds,
    asleep_minutes: Seconds,
    awake_minutes: Seconds,
    unknown_minutes: Seconds,
    conflict_minutes: Seconds,
    classification_coverage: Seconds,
    complete_asleep_minutes: OptionalMinutes,
    forecast_asleep_minutes: OptionalMinutes,
  }),
  rejected_reports: z.array(Fact.extend({ analysis: z.unknown().nullish() })),
  input_refs: z.array(Fact),
  input_count: z.number(),
})
export type SleepAnalysis = z.infer<typeof SleepAnalysis>

const StatisticsBlock = z.looseObject({
  count: z.number().optional(),
  mean: Amount,
  min: Amount,
  max: Amount,
  first_at: z.string().nullish(),
  latest_at: z.string().nullish(),
  covered_seconds: Seconds.optional(),
  reported_seconds: Seconds.optional(),
})
const SampleWindows = StatisticsBlock.extend({
  count: z.number().int().nonnegative(),
  usable_sample_count: z.number().int().nonnegative(),
  sample_count: z.number().int().nonnegative(),
  mean_basis: z.literal('usable_sample_count_weighted'),
  sampled_seconds: z.number().int().nonnegative(),
  target_one_second_bins: z.number().int().nonnegative(),
  coverage_basis: z.literal('occupied_one_second_bins'),
  sample_coverage_ratio: z
    .union([z.number(), z.string()])
    .nullable()
    .transform((value) => (value === null ? null : Number(value)))
    .refine((value) => value === null || (Number.isFinite(value) && value >= 0 && value <= 1)),
  gap_count: z.number().int().nonnegative(),
  max_inter_sample_gap_ms: z.number().nonnegative().nullable(),
})
const StatisticsDimension = z.looseObject({
  measurement_kind: z.string().optional(),
  sensor_location: z.string().optional(),
  definition_id: z.string().optional(),
  temperature_basis: z.string().optional(),
  context: z.string().optional(),
  source_ids: z.array(z.string().nullable()).optional(),
  source_names: z.array(z.string()).optional(),
  points: StatisticsBlock.nullish(),
  interval_means: StatisticsBlock.nullish(),
  ranges: StatisticsBlock.nullish(),
  sample_windows: SampleWindows.nullish(),
})
export const Resolution = z.looseObject({
  status: z.string(),
  selected: z.array(Fact).default([]),
  alternatives: z.array(Fact).default([]),
  selected_count: z.number().int().optional(),
  alternatives_count: z.number().int().optional(),
  explanation_truncated: z.boolean().optional(),
  fingerprint: z.string(),
  resolver_version: z.string(),
  policy_version: z.union([z.string(), z.number()]).nullish(),
  coverage: z
    .looseObject({
      status: z.string().optional(),
      reported_seconds: Seconds.optional(),
      covered_seconds: Seconds.optional(),
      point_count: z.number().optional(),
      intervals: z.array(z.looseObject({ start_at: z.string(), end_at: z.string() })).optional(),
      complete: z.boolean().nullish(),
    })
    .nullish(),
  protocol: z
    .looseObject({ verified: z.boolean(), flags: z.array(z.string()).default([]) })
    .nullish(),
  quality: z.looseObject({ flags: z.array(z.string()).default([]) }).nullish(),
  sessions: z.array(SleepSession).optional(),
  sleep_analysis: SleepAnalysis.optional(),
  /** Which sources supplied the result; sources.ts reads the entries. */
  contributions: z.array(z.unknown()).nullish(),
  freshness: z
    .looseObject({ measured_at: z.string().nullish(), age_seconds: z.number().nullish() })
    .nullish(),
  statistics: StatisticsDimension.extend({
    groups: z.array(StatisticsDimension).optional(),
  }).nullish(),
})
export type Resolution = z.infer<typeof Resolution>
export const ResolutionPolicy = z.looseObject({
  id: z.string().nullish(),
  metric: z.string(),
  source_ids: z.array(z.string().nullable()),
  effective_from: z.string(),
  revision: z.number(),
})
export type ResolutionPolicy = z.infer<typeof ResolutionPolicy>
export const CatalogMetric = z.looseObject({
  key: z.string(),
  unit: z.string(),
  label: z.string().optional(),
  shape: z.string(),
  source_ids: z.array(z.string().nullable()).optional(),
})
export const ResolvedPage = z.looseObject({
  from: z.string(),
  to: z.string(),
  timezone: z.string(),
  computed_at: z.string(),
  daily: z.array(
    z.looseObject({
      date: z.string(),
      kind: z.string(),
      status: z.string(),
      value: Amount,
      unit: z.string(),
      resolution: Resolution,
    }),
  ),
  series: z.array(z.record(z.string(), z.unknown())),
  next_cursor: z.string().nullable(),
  fingerprint: z.string(),
  policies: z.array(ResolutionPolicy).default([]),
  catalog: z.array(CatalogMetric).optional(),
  sources: z
    .array(
      z.looseObject({
        id: z.string(),
        name: z.string().optional(),
        metric_keys: z.array(z.string()).optional(),
      }),
    )
    .optional(),
  source_identities: z
    .record(
      z.string(),
      z.looseObject({
        physical_id: z.string().nullish(),
        confirmed: z.boolean(),
        revision: z.number(),
      }),
    )
    .optional(),
})
export type ResolvedPage = z.infer<typeof ResolvedPage>
export type ResolutionRange = { from: string; to: string; metric?: string }
