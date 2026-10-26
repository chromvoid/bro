import homeReadinessArchive from './derivedScores.home-readiness.fixture.json' with { type: 'json' }

/** Synthetic values, actual producer shapes from soul_scores_read.py/_refs and soul_result_store.py. */
export const measurementReference = (
  id = 'hr-sample',
  source: string | null = 'ring-a',
  metric = 'heart_rate',
  revision = 2,
  type: 'sample' | 'record' | 'manual_day_total' = 'sample',
) => ({
  type,
  id,
  revision,
  override_revision: 0,
  annotation_revision: 1,
  annotation_id: 'annotation-1',
  source_id: source,
  metric,
  observed_at: '2026-10-09T07:00:00+00:00',
  source_version: 1,
})
export const resolutionReference = {
  type: 'resolution_state',
  id: 'owned_resolution_snapshot',
  revision: 0,
  fingerprint: 'c'.repeat(64),
}
export const captureReference = {
  type: 'evaluation_capture',
  id: 'evaluation-operation',
  revision: 1,
  captured_at: '2026-10-09T08:00:00+00:00',
  basis: 'measurement_capture',
}
export const producerReferences = () => [
  measurementReference(),
  measurementReference('sleep-record', 'ring-a', 'sleep', 3, 'record'),
  measurementReference('manual-steps', null, 'steps', 4, 'manual_day_total'),
  {
    type: 'profile',
    id: 'profile',
    revision: 2,
    source_id: null,
    metric: 'profile',
    observed_at: null,
  },
  {
    type: 'preference',
    id: 'sleep',
    revision: 3,
    source_id: null,
    metric: 'sleep',
    observed_at: null,
  },
  resolutionReference,
  captureReference,
  {
    type: 'derivation_context',
    id: 'soul-night-window-v1',
    revision: 1,
    source_id: 'ring-a',
    fingerprint: 'd'.repeat(64),
  },
  {
    type: 'software_context',
    id: 'soul-screen-source-default-tuning',
    revision: 1,
    source_id: null,
    formula_version: 'soul-1.0.1214-v1',
  },
  {
    type: 'source_identity',
    id: 'source-identity',
    revision: 5,
    source_id: 'ring-a',
    physical_id: 'physical-ring',
    confirmed: true,
  },
]
export function producerRhrScore(source = 'ring-a') {
  const refs = [
    resolutionReference,
    captureReference,
    measurementReference('hr-1', source),
    measurementReference('hr-2', source),
  ]
  return {
    definition_id: 'soul-rhr-energy',
    formula_version: 'soul-1.0.1214-v1',
    qualification: 'qualified',
    status: 'available',
    value: 55,
    unit: 'bpm',
    components: [{ id: 'hr_7d', value: [55, 60], unit: 'bpm', quality: [], synthetic: false }],
    missing: [],
    confidence: {
      kind: 'input_coverage',
      level: 'complete',
      available_inputs: 1,
      required_inputs: 1,
      formula_value: null,
    },
    coverage: { kind: 'input_presence', observed: 1, expected: 1, ratio: 1 },
    quality: [],
    context: {
      evaluation: 'day',
      date: '2026-10-09',
      time_zone: 'UTC',
      as_of: captureReference.captured_at,
      source_id: source,
      source_name: 'Synthetic ring',
      input_scope: 'source',
    },
    input_revisions: refs,
    baseline: {
      as_of: captureReference.captured_at,
      source_id: source,
      components: [
        {
          id: 'hr_7d',
          as_of: captureReference.captured_at,
          window_start: '2026-10-02T08:00:00+00:00',
          window_end: captureReference.captured_at,
          points: 2,
          source_id: source,
          estimator: 'lower_fifth_percentile',
          formula_version: 'soul-1.0.1214-v1',
          input_revisions: refs.filter((ref) => ref.type === 'sample'),
        },
      ],
    },
    details: {},
    fingerprint: 'rhr-score',
  }
}
export function producerWindowScore() {
  const rhr = producerRhrScore()
  const asOf = '2026-10-09T09:00:00Z'
  const deviceScope = {
    basis: 'owner_confirmed_physical_members',
    source_ids: ['ring-a'],
    physical_key: null,
    identity_revisions: [],
  }
  const windows = [
    {
      type: 'sample_window',
      metric: 'heart_rate',
      window_start: '2026-10-02T09:00:00Z',
      window_end: asOf,
      count: 3,
      first_observed_at: '2026-10-08T07:00:00Z',
      last_observed_at: '2026-10-09T07:00:00Z',
      source_ids: ['ring-a'],
      digest: 'e'.repeat(64),
    },
    {
      type: 'sample_window',
      metric: 'hrv',
      window_start: '2026-10-02T09:00:00Z',
      window_end: asOf,
      count: 1,
      first_observed_at: '2026-10-09T07:00:00Z',
      last_observed_at: '2026-10-09T07:00:00Z',
      source_ids: ['ring-a'],
      digest: 'f'.repeat(64),
    },
  ]
  return {
    ...rhr,
    definition_id: 'soul-energy-readiness',
    status: 'partial',
    value: 83,
    unit: '/100',
    components: [
      {
        id: 'hrv_current',
        value: null,
        unit: 'ms',
        quality: ['hrv_semantics_unqualified'],
        synthetic: false,
      },
      { id: 'soul-rhr-energy', value: 55, unit: 'bpm', quality: [], synthetic: false },
    ],
    missing: ['hrv_current', 'soul-energy-sleep'],
    quality: ['hrv_semantics_unqualified'],
    confidence: { ...rhr.confidence, level: 'partial', available_inputs: 1, required_inputs: 3 },
    coverage: { ...rhr.coverage, observed: 1, expected: 3, ratio: 1 / 3 },
    context: { ...rhr.context, as_of: asOf },
    input_revisions: [resolutionReference, { ...captureReference, captured_at: asOf }],
    input_windows: windows,
    baseline: {
      as_of: asOf,
      source_id: 'ring-a',
      components: [
        {
          id: 'hr_7d',
          as_of: asOf,
          window_start: windows[0]!.window_start,
          window_end: asOf,
          points: 3,
          source_id: 'ring-a',
          estimator: 'lower_fifth_percentile',
          formula_version: rhr.formula_version,
          input_digest: windows[0]!.digest,
          device_scope: deviceScope,
        },
        {
          id: 'hrv_current',
          window_start: windows[1]!.window_start,
          window_end: asOf,
          unqualified_input_digest: windows[1]!.digest,
          unqualified_count: 1,
          device_scope: deviceScope,
        },
      ],
    },
  }
}
export function producerRejectedStrapScore() {
  const rhr = producerRhrScore('strap-a')
  return {
    ...rhr,
    status: 'unavailable',
    value: null,
    components: [
      {
        id: 'hr_7d',
        value: null,
        unit: 'bpm',
        quality: ['heart_rate_semantics_unqualified'],
        synthetic: false,
      },
    ],
    missing: ['actual_measurements', 'hr_7d'],
    quality: ['heart_rate_semantics_unqualified'],
    confidence: { ...rhr.confidence, level: 'none', available_inputs: 0, required_inputs: 2 },
    coverage: { ...rhr.coverage, observed: 0, expected: 2, ratio: 0 },
    baseline: {
      ...rhr.baseline,
      components: [
        {
          id: 'hr_7d',
          window_start: rhr.baseline.components[0]!.window_start,
          window_end: rhr.context.as_of,
          unqualified_input_revisions: rhr.input_revisions.filter((ref) => ref.type === 'sample'),
        },
      ],
    },
  }
}
export function producerOwnerSleepScore() {
  const rhr = producerRhrScore()
  return {
    ...rhr,
    definition_id: 'soul-sleep-screen-score',
    status: 'partial',
    value: 100,
    unit: '/100',
    quality: ['owner_global_source_context'],
    confidence: { ...rhr.confidence, level: 'partial', available_inputs: 3, required_inputs: 3 },
    coverage: { ...rhr.coverage, observed: 3, expected: 3 },
    components: [
      {
        id: 'screen_sleep_minutes',
        value: 480,
        unit: 'min',
        quality: ['owner_global_source_context'],
        synthetic: false,
      },
      {
        id: 'screen_deep_minutes',
        value: 120,
        unit: 'min',
        quality: ['owner_global_source_context'],
        synthetic: false,
      },
      { id: 'sleep_goal_minutes', value: 480, unit: 'min', quality: [], synthetic: false },
    ],
    context: {
      ...rhr.context,
      source_id: null,
      source_name: null,
      input_scope: 'owner_all_sources_source_only_selected_day',
    },
    input_revisions: [
      resolutionReference,
      captureReference,
      measurementReference('sleep-record', 'ring-a', 'sleep', 3, 'record'),
      {
        type: 'preference',
        id: 'sleep',
        revision: 2,
        source_id: null,
        metric: 'sleep',
        observed_at: null,
      },
    ],
    baseline: {
      as_of: captureReference.captured_at,
      source_id: null,
      components: ['screen_sleep_minutes', 'screen_deep_minutes'].map((id) => ({
        id,
        algorithm_version: 'soul-sleep-source-contexts-v1',
        producer: 'SleepViewModel.n',
        as_of: captureReference.captured_at,
        window_start: '2026-10-09T00:00:00+00:00',
        window_end: '2026-10-10T00:00:00+00:00',
        input_scope: 'owner_all_sources_source_only_selected_day',
        input_ids: ['sleep-record'],
        decisions: [],
        missing: [],
        pipeline: { data_gaps: [], selected_device_scope: { identity_revisions: [] } },
      })),
    },
  }
}
export const producerArchiveItem = () => {
  const score = producerRhrScore()
  return {
    result_id: 'a'.repeat(64),
    version: 1,
    definition_id: score.definition_id,
    formula_version: score.formula_version,
    source_scope: score.context.source_id,
    date: score.context.date,
    evaluation: score.context.evaluation,
    time_zone: score.context.time_zone,
    as_of: score.context.as_of,
    temporal_basis: 'measurement_capture',
    score,
    definition_snapshot: {
      id: score.definition_id,
      label: 'Пульс покоя · Energy Soul',
      unit: 'bpm',
      formula_version: score.formula_version,
      qualification: 'qualified',
      required_inputs: ['hr_7d'],
      evidence: ['ce4.d:61-78'],
    },
    normalized_inputs: {
      snapshot: {
        context_revisions: [resolutionReference, captureReference],
        source_id: score.context.source_id,
      },
      facts: {
        hr_7d: {
          value: [55, 60],
          unit: 'bpm',
          refs: score.input_revisions,
          baseline: score.baseline?.components[0],
        },
      },
      requested_inputs: ['hr_7d'],
    },
    context_revisions: [resolutionReference, captureReference],
    input_adapter_version: 'soul-inputs-1.0.1214-v4',
    implementation_digest: 'b'.repeat(64),
    implementation_source: {
      format: 'git_blob_sha1',
      files: [
        {
          path: 'server/plugins/personal/soul_scores.py',
          git_blob_sha1: 'c'.repeat(40),
          sha256: 'd'.repeat(64),
        },
      ],
    },
    operation_id: 'evaluation-operation',
    created_at: '2026-10-09T08:01:00+00:00',
    is_current: true,
  }
}
/** Missing K/z0, admitted hourly history and independent raw-sleep anchor. */
export function producerHomeArchiveItem() {
  const original = producerArchiveItem()
  const asOf = captureReference.captured_at
  const producerVersion = 'soul-1.0.1230-home-activity-inputs-v1'
  const plain = (id: string, metric: string, type: 'record' | 'activity_report') => ({
    ...measurementReference(id, 'ring-a', metric),
    type,
    annotation_revision: 0,
    annotation_id: null,
  })
  const report = {
    ...plain('current-candidate', 'running_steps', 'activity_report'),
    observed_at: asOf,
  }
  const hour = { ...plain('source-hour', 'steps', 'record'), revision: 1 }
  const sleep = { ...plain('raw-night', 'sleep', 'record'), revision: 1 }
  const baselines = [
    {
      id: 'home_steps_current',
      producer_version: producerVersion,
      producer: 'wrapper_K_z0',
      as_of: asOf,
      time_zone: 'UTC',
      missing: ['home_current_wrapper_unavailable'],
      excluded_candidate_ids: [],
      unqualified_current_candidate: {
        id: report.id,
        revision: report.revision,
        total_steps: 1234,
        observed_at: asOf,
        reason: 'core48_home_wrapper_unqualified',
      },
      device_scope: {
        basis: 'owner_confirmed_physical_members',
        source_ids: ['ring-a'],
        physical_key: null,
        identity_revisions: [],
      },
    },
    {
      id: 'home_steps_day_db',
      producer_version: producerVersion,
      producer: 'oy5.t/mbb.c/z5e.n',
      input_scope: 'owner_all_sources_source_only',
      window_start: '2026-10-09T00:00:00+00:00',
      window_end: asOf,
      window_inclusive: true,
      query_qualified: true,
      missing: [],
      input_ids: [hour.id],
      unknown_devices_discarded: true,
      selected_hourly_ids: [hour.id],
      device_totals: { 'ring-a': 80 },
      hr_spans: { 'ring-a': null },
      components: [['ring-a']],
    },
    {
      id: 'home_steps_anchor',
      producer_version: producerVersion,
      producer: 'pya.d/e/a',
      sleep_window_start: '2026-10-09T00:00:00+00:00',
      sleep_window_end: '2026-10-10T00:00:00+00:00',
      window_inclusive: true,
      event_window_basis: 'source_fixed_milliseconds',
      query_qualified: true,
      captured_as_of: asOf,
      input_ids: [sleep.id],
      missing: [],
      selected_sleep_id: sleep.id,
      selected_device: 'ring-a',
      sleep_start: '2026-10-09T07:00:00+00:00',
      hourly_window_start: '2026-10-09T06:00:00+00:00',
      hourly_window_end: asOf,
      hourly_input_ids: [hour.id],
    },
  ]
  const query = (name: string) => ({
    type: 'software_context',
    id: name + ':source_query',
    revision: 1,
    source_id: null,
    fingerprint: 'e'.repeat(64),
  })
  const facts = {
    home_steps_current: {
      value: null,
      unit: 'steps',
      refs: [report, query('home_steps_current')],
      quality: ['home_current_wrapper_unavailable', 'protocol_unverified'],
      observed_at: asOf,
      measurement: false,
      baseline: baselines[0],
    },
    home_steps_day_db: {
      value: 80,
      unit: 'steps',
      refs: [hour, query('home_steps_day_db')],
      quality: [],
      observed_at: asOf,
      measurement: true,
      baseline: baselines[1],
    },
    home_steps_anchor: {
      value: 80,
      unit: 'steps',
      refs: [sleep, hour, query('home_steps_anchor')],
      quality: [],
      observed_at: asOf,
      measurement: true,
      baseline: baselines[2],
    },
    steps_goal: {
      value: null,
      unit: '',
      refs: [],
      quality: [],
      observed_at: null,
      measurement: false,
      baseline: null,
    },
  }
  const score = {
    ...original.score,
    definition_id: 'soul-home-readiness',
    status: 'unavailable',
    value: null,
    unit: '/100',
    components: Object.entries(facts)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([id, fact]) => ({
        id,
        value: fact.value,
        unit: fact.unit,
        quality: fact.quality,
        synthetic: false,
      })),
    missing: ['home_steps_current', 'soul-home-sleep', 'soul-night-recovery', 'steps_goal'],
    quality: ['home_current_wrapper_unavailable', 'protocol_unverified'],
    confidence: {
      kind: 'input_coverage',
      level: 'none',
      available_inputs: 2,
      required_inputs: 6,
      formula_value: null,
    },
    coverage: { kind: 'input_presence', observed: 2, expected: 6, ratio: 1 / 3 },
    input_revisions: [
      resolutionReference,
      captureReference,
      report,
      hour,
      sleep,
      query('home_steps_current'),
      query('home_steps_day_db'),
      query('home_steps_anchor'),
    ],
    input_windows: [],
    baseline: { as_of: asOf, source_id: 'ring-a', components: baselines },
  }
  return {
    ...original,
    definition_id: score.definition_id,
    score,
    definition_snapshot: {
      ...original.definition_snapshot,
      id: score.definition_id,
      label: 'Готовность · главный экран Soul',
      unit: score.unit,
      required_inputs: [
        'night_hr',
        'night_hrv',
        'home_sleep_minutes',
        'home_deep_minutes',
        'home_steps_current',
        'home_steps_day_db',
        'home_steps_anchor',
        'steps_goal',
      ],
    },
    normalized_inputs: {
      ...original.normalized_inputs,
      facts,
      requested_inputs: Object.keys(facts),
    },
    input_adapter_version: 'soul-inputs-1.0.1230-v1',
  }
}

/** Unmodified public_result archive from synthetic_history, captured on CI try 4338. */
export function producerCompactHomeArchiveItem() {
  return structuredClone(homeReadinessArchive)
}

interface SummaryInput {
  definition_id: string
  formula_version: string
  qualification: string
  status: string
  value: number | null
  unit: string
  missing: string[]
  quality: string[]
  confidence: { level: string; available_inputs: number; required_inputs: number }
  coverage: { observed: number; expected: number; ratio: number | null }
  context: {
    evaluation: string
    date: string
    time_zone: string
    as_of: string
    source_id: string | null
  }
  fingerprint: string
}
export const producerRefresh = (pending = false, pendingRuns = 0, failedRuns = 0) => ({
  pending,
  pending_runs: pendingRuns,
  failed_runs: failedRuns,
  owner_pending: pending,
  owner_pending_runs: pendingRuns,
  owner_failed_runs: failedRuns,
})
/** Compact API omits the full evidence; the immutable result endpoint owns it. */
export function producerSummary(
  score: SummaryInput = producerRhrScore(),
  label = 'Пульс покоя · Energy Soul',
) {
  return {
    definition_id: score.definition_id,
    formula_version: score.formula_version,
    qualification: score.qualification,
    status: score.status,
    value: score.value,
    unit: score.unit,
    missing: score.missing,
    quality: score.quality,
    confidence: score.confidence,
    coverage: score.coverage,
    context: score.context,
    fingerprint: score.fingerprint,
    result_id: 'a'.repeat(64),
    version: 1,
    evaluated_at: score.context.as_of,
    temporal_basis: 'measurement_capture',
    input_adapter_version: 'soul-inputs-1.0.1214-v4',
    implementation_digest: 'b'.repeat(64),
    details_mode: 'archived',
    series_count: 0,
    definition_snapshot: {
      id: score.definition_id,
      label,
      unit: score.unit,
      formula_version: score.formula_version,
      qualification: score.qualification,
    },
  }
}
export const producerSummaryPage = (
  scores = [producerSummary()],
  nextCursor: string | null = null,
  fingerprint = 'summary-collection',
) => ({
  from: '2026-10-09',
  to: '2026-10-09',
  time_zone: 'UTC',
  as_of: scores.length ? scores[0]!.context.as_of : null,
  definitions: [],
  days: [{ date: '2026-10-09', scores }],
  fingerprint,
  persistence: 'immutable_versions',
  refresh: producerRefresh(),
  preview: null,
  details_mode: 'archived',
  total_summaries: scores.length,
  next_cursor: nextCursor,
  limit: 250,
})
