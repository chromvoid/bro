const qualityKeys = new Map<string, string>([
  ...[
    'protocol_verified',
    'protocol_unverified',
    'invalid',
    'hrv_statistic_unknown',
    'stress_scale_unknown',
    'partial_day',
    'partial',
    'incomplete',
    'identity_collision',
    'sparse',
    'missing',
    'gap',
    'out_of_range',
    'ambiguous_layout',
    'temperature_layout_unconfirmed',
    'ambiguous_time',
    'sleep_taxonomy_unverified',
    'estimated_stages',
    'epoch_unknown',
    'unit_unknown',
    'sport_type_unknown',
    'hr_cadence_unknown',
    'home_current_wrapper_unavailable',
    'home_current_wrapper_future',
    'home_current_reference_zero',
    'home_day_db_source_query_unqualified',
    'home_day_db_hr_span_query_unqualified',
    'home_anchor_source_query_unqualified',
    'home_anchor_sleep_timestamp_order',
    'home_anchor_hourly_source_unqualified',
    'home_anchor_hourly_timestamp_order',
  ].map((flag): [string, string] => [flag, `recordQuality.flags.${flag}`]),
  ['unknown_sleep_stage', 'personal.sleepQuality.unknown_sleep_stage'],
  ['duration_mismatch', 'personal.sleepQuality.duration_mismatch'],
])

/** Quality is an open source vocabulary; an unknown code receives no inferred meaning. */
export function recordQualityMessages(flags: string[] | null | undefined) {
  return (flags ?? []).map((code) => ({
    code,
    messageKey: qualityKeys.get(code) ?? 'recordQuality.unknown',
  }))
}
