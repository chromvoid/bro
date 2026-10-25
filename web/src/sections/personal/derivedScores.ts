import { createI18n } from 'vue-i18n'
import { z } from '@/core/zod'
import historyMessages from '@/locales/personal-history-ru.json'

const historyI18n = createI18n({
  legacy: false,
  locale: 'ru',
  fallbackLocale: 'ru',
  messages: { ru: historyMessages },
})
const historyText = historyI18n.global.t

const Amount = z.number().finite().nullable()
const TemporalBasis = z.enum([
  'measurement_capture',
  'owner_context_anchor',
  'historical_day_end',
  'source_sample',
])
const DerivationRefresh = z.strictObject({
  pending: z.boolean(),
  pending_runs: z.number().int().nonnegative(),
  failed_runs: z.number().int().nonnegative(),
  owner_pending: z.boolean(),
  owner_pending_runs: z.number().int().nonnegative(),
  owner_failed_runs: z.number().int().nonnegative(),
})
const Revision = z.number().int().nonnegative()
const ReferenceIdentity = { id: z.string(), revision: Revision }
const MeasurementReference = {
  ...ReferenceIdentity,
  source_id: z.string().nullable(),
  metric: z.string(),
  observed_at: z.string().nullable(),
  source_version: z.number().int().positive(),
  override_revision: Revision,
  annotation_revision: Revision,
  annotation_id: z.string().nullable(),
}
/** Exact reference variants emitted by the source adapter and immutable evaluation store. */
export const DerivedInputReference = z.discriminatedUnion('type', [
  z.strictObject({ type: z.literal('record'), ...MeasurementReference }),
  z.strictObject({ type: z.literal('sample'), ...MeasurementReference }),
  z.strictObject({ type: z.literal('activity_report'), ...MeasurementReference }),
  z.strictObject({ type: z.literal('manual_day_total'), ...MeasurementReference }),
  z.strictObject({
    type: z.literal('profile'),
    ...ReferenceIdentity,
    source_id: z.null(),
    metric: z.string(),
    observed_at: z.null(),
  }),
  z.strictObject({
    type: z.literal('preference'),
    ...ReferenceIdentity,
    source_id: z.null(),
    metric: z.string(),
    observed_at: z.null(),
  }),
  z.strictObject({
    type: z.literal('resolution_state'),
    ...ReferenceIdentity,
    fingerprint: z.string(),
  }),
  z.strictObject({
    type: z.literal('evaluation_capture'),
    ...ReferenceIdentity,
    captured_at: z.string(),
    basis: TemporalBasis,
  }),
  z.strictObject({
    type: z.literal('derivation_context'),
    ...ReferenceIdentity,
    source_id: z.string().nullable(),
    fingerprint: z.string(),
  }),
  z
    .strictObject({
      type: z.literal('software_context'),
      ...ReferenceIdentity,
      source_id: z.string().nullable(),
      formula_version: z.string().optional(),
      producer_version: z.string().optional(),
      fingerprint: z.string().optional(),
    })
    .refine(
      (ref) =>
        [ref.formula_version, ref.producer_version, ref.fingerprint].filter(
          (identity) => identity !== undefined,
        ).length === 1,
      'Software context must preserve exactly one captured identity',
    ),
  z.strictObject({
    type: z.literal('source_identity'),
    ...ReferenceIdentity,
    source_id: z.string(),
    physical_id: z.string().nullable(),
    confirmed: z.boolean(),
  }),
])
export type DerivedInputReference = z.infer<typeof DerivedInputReference>
const InputDigest = z.string().regex(/^[0-9a-f]{64}$/i)
export const DerivedInputWindow = z.strictObject({
  type: z.literal('sample_window'),
  metric: z.string(),
  window_start: z.string(),
  window_end: z.string(),
  count: z.number().int().nonnegative(),
  first_observed_at: z.string().nullable(),
  last_observed_at: z.string().nullable(),
  source_ids: z.array(z.string().nullable()),
  digest: InputDigest,
})
export type DerivedInputWindow = z.infer<typeof DerivedInputWindow>
const SleepStageInput = z.strictObject({
  stage: z.enum(['deep', 'light', 'rem', 'awake']),
  minutes: z.number().finite().nonnegative(),
})
export const DerivedComponentValue = z.union([
  Amount,
  z.boolean(),
  z.array(z.number().finite().nonnegative()),
  z.array(SleepStageInput),
])
export type DerivedComponentValue = z.infer<typeof DerivedComponentValue>
const ScoreComponent = z.discriminatedUnion('synthetic', [
  z.strictObject({
    id: z.string(),
    value: DerivedComponentValue,
    unit: z.string(),
    quality: z.array(z.string()),
    synthetic: z.literal(false),
  }),
  z.strictObject({
    id: z.string(),
    value: z.number().finite(),
    unit: z.string(),
    synthetic: z.literal(true),
    reason: z.enum(['soul_reference_default', 'soul_age_reference']),
  }),
])
const JsonObject = z.record(z.string(), z.json())
const HomeProducerVersion = z.literal('soul-1.0.1230-home-activity-inputs-v1')
const HomeCurrentBaseline = z.strictObject({
  id: z.literal('home_steps_current'),
  producer_version: HomeProducerVersion,
  producer: z.literal('wrapper_K_z0'),
  as_of: z.string(),
  time_zone: z.string(),
  missing: z.array(z.string()),
  device_scope: z
    .strictObject({
      basis: z.literal('owner_confirmed_physical_members'),
      source_ids: z.array(z.string().nullable()),
      physical_key: z.string().nullable(),
      identity_revisions: z
        .array(DerivedInputReference)
        .refine((refs) => refs.every((ref) => ref.type === 'source_identity')),
    })
    .optional(),
  source_date: z.string().nullable().optional(),
  observed_at: z.string().optional(),
  reset: z.boolean().optional(),
  wrapper_time_zone: z.string().optional(),
  date_gate: z.enum(['today', 'source_reset_or_stale_date']).optional(),
  excluded_candidate_ids: z.array(z.string()).optional(),
  unqualified_current_candidate: z
    .strictObject({
      id: z.string(),
      revision: Revision,
      total_steps: z.number().int().nonnegative(),
      observed_at: z.string(),
      reason: z.literal('core48_home_wrapper_unqualified'),
    })
    .optional(),
})
const HomeDayDbBaseline = z
  .strictObject({
    id: z.literal('home_steps_day_db'),
    producer_version: HomeProducerVersion,
    producer: z.literal('oy5.t/mbb.c/z5e.n'),
    input_scope: z.literal('owner_all_sources_source_only'),
    window_start: z.string(),
    window_end: z.string(),
    window_inclusive: z.literal(true),
    query_qualified: z.boolean(),
    missing: z.array(z.string()),
    input_ids: z.array(z.string()).optional(),
    input_count: z.number().int().nonnegative().optional(),
    input_digest: InputDigest.optional(),
    unknown_devices_discarded: z.boolean().optional(),
    selected_hourly_ids: z.array(z.string()).optional(),
    device_totals: z.record(z.string(), z.number().int().nonnegative()).optional(),
    hr_spans: z.record(z.string(), z.tuple([z.string(), z.string()]).nullable()).optional(),
    components: z.array(z.array(z.string())).optional(),
  })
  .refine(
    (baseline) =>
      baseline.input_ids !== undefined
        ? baseline.input_count === undefined && baseline.input_digest === undefined
        : baseline.input_count !== undefined && baseline.input_digest !== undefined,
    'Home baseline must preserve input IDs or a count and digest',
  )
const HomeSleepAnchorBaseline = z
  .strictObject({
    id: z.literal('home_steps_anchor'),
    producer_version: HomeProducerVersion,
    producer: z.literal('pya.d/e/a'),
    sleep_window_start: z.string(),
    sleep_window_end: z.string(),
    window_inclusive: z.literal(true),
    event_window_basis: z.literal('source_fixed_milliseconds'),
    query_qualified: z.boolean(),
    captured_as_of: z.string(),
    input_ids: z.array(z.string()).optional(),
    input_count: z.number().int().nonnegative().optional(),
    input_digest: InputDigest.optional(),
    missing: z.array(z.string()),
    unavailable: z.array(z.strictObject({ id: z.string(), reason: z.string() })).optional(),
    selected_sleep_id: z.string().optional(),
    selected_device: z.string().optional(),
    sleep_start: z.string().optional(),
    hourly_window_start: z.string().optional(),
    hourly_window_end: z.string().optional(),
    hourly_input_ids: z.array(z.string()).optional(),
  })
  .refine(
    (baseline) =>
      baseline.input_ids !== undefined
        ? baseline.input_count === undefined && baseline.input_digest === undefined
        : baseline.input_count !== undefined && baseline.input_digest !== undefined,
    'Home baseline must preserve input IDs or a count and digest',
  )
// A baseline combines separately captured windows and sleep-producer selection contexts.
// Additional producer details are preserved as strict JSON; there are no invented point counts.
const BaselineComponent = z.union([
  HomeCurrentBaseline,
  HomeDayDbBaseline,
  HomeSleepAnchorBaseline,
  z
    .object({
      id: z.string(),
      as_of: z.string(),
      window_start: z.string(),
      window_end: z.string(),
      points: z.number().int().nonnegative(),
      source_id: z.string().nullable(),
      estimator: z.string(),
      formula_version: z.string(),
      input_revisions: z.array(DerivedInputReference).optional(),
      input_digest: InputDigest.optional(),
      device_scope: JsonObject.optional(),
      unqualified_input_digest: InputDigest.optional(),
      unqualified_count: z.number().int().nonnegative().optional(),
    })
    .catchall(z.json())
    .refine(
      (component) =>
        component.input_revisions !== undefined || component.input_digest !== undefined,
      'Baseline window must preserve input revisions or a digest',
    ),
  z
    .object({
      id: z.string(),
      window_start: z.string(),
      window_end: z.string(),
      unqualified_input_digest: InputDigest.optional(),
      unqualified_count: z.number().int().nonnegative().optional(),
      unqualified_input_revisions: z.array(DerivedInputReference).optional(),
      input_digest: InputDigest.optional(),
      device_scope: JsonObject.optional(),
    })
    .catchall(z.json())
    .refine(
      (component) =>
        component.unqualified_input_revisions !== undefined ||
        (component.unqualified_input_digest !== undefined &&
          component.unqualified_count !== undefined),
      'Rejected baseline window must preserve input revisions or a digest and count',
    ),
  z
    .object({
      id: z.string(),
      formula_version: z.string(),
      source_scalar_includes_awake: z.literal(true),
      window_start: z.string(),
      window_end: z.string(),
      input_revisions: z.array(DerivedInputReference),
      selected_input_revisions: z.array(DerivedInputReference),
    })
    .catchall(z.json()),
  z
    .object({
      id: z.string(),
      algorithm_version: z.string(),
      window_start: z.string(),
      window_end: z.string(),
      input_ids: z.array(z.string()),
      decisions: z.array(JsonObject),
    })
    .catchall(z.json()),
  z
    .object({
      id: z.string(),
      as_of: z.string(),
      formula_version: z.string(),
      selection_context: JsonObject,
    })
    .catchall(z.json()),
])
export const DerivedBaseline = z.strictObject({
  as_of: z.string(),
  source_id: z.string().nullable(),
  components: z.array(BaselineComponent),
})
const ScoreCore = z.looseObject({
  definition_id: z.string(),
  formula_version: z.string(),
  qualification: z.string(),
  status: z.enum(['available', 'partial', 'unavailable', 'unqualified']),
  value: Amount,
  unit: z.string(),
  components: z.array(ScoreComponent),
  missing: z.array(z.string()),
  confidence: z.looseObject({
    level: z.string(),
    available_inputs: z.number(),
    required_inputs: z.number(),
  }),
  coverage: z.looseObject({ observed: z.number(), expected: z.number(), ratio: Amount }),
  quality: z.array(z.string()),
  context: z.looseObject({
    evaluation: z.enum(['day', 'night', 'instant']),
    date: z.string(),
    time_zone: z.string(),
    as_of: z.string(),
    source_id: z.string().nullable(),
  }),
  input_revisions: z.array(DerivedInputReference),
  input_windows: z.array(DerivedInputWindow).default([]),
  baseline: DerivedBaseline.nullable(),
  fingerprint: z.string(),
  result_id: z.string().length(64).optional(),
  version: z.number().int().positive().optional(),
  evaluated_at: z.string().optional(),
  temporal_basis: TemporalBasis.optional(),
  input_adapter_version: z.string().optional(),
  implementation_digest: z.string().optional(),
})
export const DerivedScore = ScoreCore.extend({
  details: z
    .looseObject({
      series: z.array(ScoreCore).optional(),
      series_count: z.number().int().nonnegative().optional(),
    })
    .optional(),
})
export const DerivedDefinition = z.looseObject({
  id: z.string(),
  label: z.string(),
  unit: z.string(),
  formula_version: z.string(),
  qualification: z.string(),
  required_inputs: z.array(z.string()),
  evidence: z.array(z.string()),
})
export type DerivedDefinition = z.infer<typeof DerivedDefinition>
export const DerivedScoreSummary = z
  .strictObject({
    definition_id: z.string(),
    formula_version: z.string(),
    qualification: z.string(),
    status: z.enum(['available', 'partial', 'unavailable', 'unqualified']),
    value: Amount,
    unit: z.string(),
    missing: z.array(z.string()),
    quality: z.array(z.string()),
    confidence: ScoreCore.shape.confidence,
    coverage: ScoreCore.shape.coverage,
    context: ScoreCore.shape.context,
    fingerprint: z.string(),
    result_id: z.string().length(64),
    version: z.number().int().positive(),
    evaluated_at: z.string(),
    temporal_basis: TemporalBasis,
    input_adapter_version: z.string(),
    implementation_digest: z.string(),
    details_mode: z.literal('archived'),
    series_count: z.number().int().nonnegative(),
    definition_snapshot: z.strictObject({
      id: z.string(),
      label: z.string(),
      unit: z.string(),
      formula_version: z.string(),
      qualification: z.string(),
    }),
  })
  .refine(
    (row) =>
      row.definition_snapshot.id === row.definition_id &&
      row.definition_snapshot.formula_version === row.formula_version &&
      row.definition_snapshot.unit === row.unit &&
      row.definition_snapshot.qualification === row.qualification,
    'Summary must retain the captured definition identity',
  )
export type DerivedScoreSummary = z.infer<typeof DerivedScoreSummary>
export const DerivedScoresPage = z.looseObject({
  from: z.string(),
  to: z.string(),
  time_zone: z.string(),
  as_of: z.string().nullable(),
  definitions: z.array(DerivedDefinition),
  days: z.array(z.object({ date: z.string(), scores: z.array(DerivedScoreSummary) })),
  fingerprint: z.string(),
  persistence: z.literal('immutable_versions'),
  refresh: DerivationRefresh,
  preview: z.null(),
  details_mode: z.literal('archived'),
  total_summaries: z.number().int().nonnegative(),
  next_cursor: z.string().nullable(),
  limit: z.number().int().min(1).max(500),
})
export type DerivedScoresPage = z.infer<typeof DerivedScoresPage>
export const coherentDerivedSummaries = (pages: DerivedScoresPage[]) =>
  pages.every((page) => page.fingerprint === pages[0]?.fingerprint)
export type DerivedScore = z.infer<typeof DerivedScore>

export const DerivedResultVersion = z
  .looseObject({
    result_id: z.string().length(64),
    version: z.number().int().positive(),
    definition_id: z.string(),
    formula_version: z.string(),
    source_scope: z.string().nullable(),
    date: z.string(),
    evaluation: z.enum(['day', 'night', 'instant']),
    time_zone: z.string(),
    as_of: z.string(),
    temporal_basis: TemporalBasis,
    score: DerivedScore,
    definition_snapshot: DerivedDefinition,
    normalized_inputs: JsonObject,
    context_revisions: z.array(DerivedInputReference),
    input_adapter_version: z.string(),
    implementation_digest: z.string(),
    implementation_source: z.strictObject({
      format: z.literal('git_blob_sha1'),
      files: z.array(
        z.strictObject({ path: z.string(), git_blob_sha1: z.string(), sha256: z.string() }),
      ),
    }),
    operation_id: z.string(),
    created_at: z.string(),
    is_current: z.boolean(),
  })
  .refine(
    (row) =>
      row.score.definition_id === row.definition_id &&
      row.definition_snapshot.id === row.definition_id &&
      row.score.formula_version === row.formula_version &&
      row.definition_snapshot.formula_version === row.formula_version &&
      row.score.context.source_id === row.source_scope &&
      row.score.context.date === row.date &&
      row.score.context.evaluation === row.evaluation &&
      row.score.context.time_zone === row.time_zone &&
      row.score.context.as_of === row.as_of,
    'Immutable result identity must agree with its captured score and definition',
  )
export type DerivedResultVersion = z.infer<typeof DerivedResultVersion>
export const DerivedResultVersionsPage = z.object({
  from: z.string(),
  to: z.string(),
  time_zone: z.string(),
  items: z.array(DerivedResultVersion),
  next_cursor: z.string().nullable(),
  fingerprint: z.string(),
  persistence: z.literal('immutable_versions'),
  refresh: DerivationRefresh,
  total_versions: z.number().int().nonnegative(),
  scope: z.enum(['all', 'owner', 'source']),
})
export type DerivedResultVersionsPage = z.infer<typeof DerivedResultVersionsPage>
/** Resolution/selection references are evidence, not timestamped measurements. */
export function referenceLabel(ref: DerivedInputReference): string {
  const when =
    'observed_at' in ref
      ? (ref.observed_at ?? historyText('personalHistory.derived.reference.timeUnknown'))
      : ''
  const source =
    'source_id' in ref
      ? (ref.source_id ?? historyText('personalHistory.derived.reference.owner'))
      : ''
  switch (ref.type) {
    case 'record':
    case 'sample':
    case 'manual_day_total':
      return historyText('personalHistory.derived.reference.measurement', {
        type: ref.type,
        metric: ref.metric,
        when,
        source,
      })
    case 'activity_report':
      return historyText('personalHistory.derived.reference.activityReport', { when, source })
    case 'profile':
      return historyText('personalHistory.derived.reference.profile', { metric: ref.metric })
    case 'preference':
      return historyText('personalHistory.derived.reference.preference', { metric: ref.metric })
    case 'resolution_state':
      return historyText('personalHistory.derived.reference.resolutionState', {
        fingerprint: ref.fingerprint,
      })
    case 'evaluation_capture':
      return historyText('personalHistory.derived.reference.evaluationCapture', {
        capturedAt: ref.captured_at,
        basis: ref.basis,
      })
    case 'derivation_context':
      return historyText('personalHistory.derived.reference.context', {
        source,
        fingerprint: ref.fingerprint,
      })
    case 'software_context':
      return historyText('personalHistory.derived.reference.softwareContext', {
        version: ref.formula_version ?? ref.producer_version ?? ref.fingerprint,
      })
    case 'source_identity':
      return historyText('personalHistory.derived.reference.identity', {
        source: ref.source_id,
        confirmed: historyText(
          ref.confirmed
            ? 'personalHistory.derived.reference.confirmed'
            : 'personalHistory.derived.reference.unconfirmed',
        ),
        physical: ref.physical_id ? ` · ${ref.physical_id}` : '',
      })
  }
}
export function componentValueLabel(value: DerivedComponentValue): string | null {
  if (typeof value === 'boolean')
    return historyText(
      value ? 'personalHistory.derived.component.yes' : 'personalHistory.derived.component.no',
    )
  if (Array.isArray(value))
    return historyText(
      value.some((item) => typeof item === 'object')
        ? 'personalHistory.derived.component.sleepStages'
        : 'personalHistory.derived.component.numericSeries',
      { count: value.length },
    )
  return null
}
export const coherentDerivedVersions = (pages: DerivedResultVersionsPage[]) =>
  pages.every((page) => page.fingerprint === pages[0]?.fingerprint)
export const DerivedBackfillReceipt = z.looseObject({
  derivation_run_id: z.string().length(64),
  state: z.literal('queued'),
  from: z.string(),
  to: z.string(),
  as_of: z.string(),
  scheduled_days: z.number().int().positive(),
  results_saved: z.literal(0),
  pending: z.literal(true),
})
export const DerivedBackfillRun = z.object({
  run_id: z.string().length(64),
  state: z.enum(['queued', 'succeeded', 'failed', 'cancelled']),
  from: z.string(),
  to: z.string(),
  next_date: z.string(),
  as_of: z.string(),
  time_zone: z.string(),
  source_scope: z.string().nullable(),
  trigger: z.string(),
  processed_days: z.number().int().nonnegative(),
  scheduled_days: z.number().int().positive(),
  results_saved: z.number().int().nonnegative(),
  results_unchanged: z.number().int().nonnegative(),
  error: z.string().nullable(),
  created_at: z.string(),
  finished_at: z.string().nullable(),
})

/** Availability belongs to the server definition; no neutral default becomes a measured score. */
export function scoreValue(score: Pick<DerivedScore, 'status' | 'value'>): number | null {
  return ['available', 'partial'].includes(score.status) &&
    score.value !== null &&
    Number.isFinite(score.value)
    ? score.value
    : null
}
