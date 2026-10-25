package assistant.mobile.core.personal

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString

enum class PersonalDerivedMissingQualifier { Direct, Invalid, Future }

/** The UI resolves the shared input label before interpolating a shared condition template. */
data class PersonalDerivedMissingMessage(
    val label: LocalizedString,
    val qualifier: PersonalDerivedMissingQualifier = PersonalDerivedMissingQualifier.Direct,
) {
    fun withLabel(localizedLabel: String): LocalizedString = when (qualifier) {
        PersonalDerivedMissingQualifier.Direct -> label
        PersonalDerivedMissingQualifier.Invalid -> GeneratedStrings.DerivedMissingInputs.invalidInput(localizedLabel)
        PersonalDerivedMissingQualifier.Future -> GeneratedStrings.DerivedMissingInputs.futureInput(localizedLabel)
    }
}

/** Exact machine identifiers and shared labels from web personal/derivedMetadata.ts. */
fun personalDerivedMissingMessage(code: String): PersonalDerivedMissingMessage = personalDerivedMissingLabels[code]
    ?: PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.unknown)

private val personalDerivedInputLabels: Map<String, LocalizedString> = mapOf(
    "home_sleep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`home_sleep_minutes`,
    "home_deep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`home_deep_minutes`,
    "screen_sleep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`screen_sleep_minutes`,
    "screen_deep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`screen_deep_minutes`,
    "screen_awake_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`screen_awake_minutes`,
    "sleep_goal_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`sleep_goal_minutes`,
    "energy_sleep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_sleep_minutes`,
    "energy_deep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_deep_minutes`,
    "energy_light_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_light_minutes`,
    "energy_rem_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_rem_minutes`,
    "energy_awake_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_awake_minutes`,
    "ide_sleep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_sleep_minutes`,
    "ide_deep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_deep_minutes`,
    "ide_light_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_light_minutes`,
    "ide_rem_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_rem_minutes`,
    "ide_awake_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_awake_minutes`,
    "body_sleep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_sleep_minutes`,
    "body_deep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_deep_minutes`,
    "body_deep_percent" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_deep_percent`,
    "deep_percent" to GeneratedStrings.DerivedMissingInputs.Inputs.`deep_percent`,
    "hr_7d" to GeneratedStrings.DerivedMissingInputs.Inputs.`hr_7d`,
    "hr_24h" to GeneratedStrings.DerivedMissingInputs.Inputs.`hr_24h`,
    "hr_14d" to GeneratedStrings.DerivedMissingInputs.Inputs.`hr_14d`,
    "night_hr" to GeneratedStrings.DerivedMissingInputs.Inputs.`night_hr`,
    "night_hrv" to GeneratedStrings.DerivedMissingInputs.Inputs.`night_hrv`,
    "night_rhr_baseline" to GeneratedStrings.DerivedMissingInputs.Inputs.`night_rhr_baseline`,
    "night_hrv_baseline" to GeneratedStrings.DerivedMissingInputs.Inputs.`night_hrv_baseline`,
    "home_steps_current" to GeneratedStrings.DerivedMissingInputs.Inputs.`home_steps_current`,
    "home_steps_day_db" to GeneratedStrings.DerivedMissingInputs.Inputs.`home_steps_day_db`,
    "home_steps_session" to GeneratedStrings.DerivedMissingInputs.Inputs.`home_steps_session`,
    "home_steps_anchor" to GeneratedStrings.DerivedMissingInputs.Inputs.`home_steps_anchor`,
    "steps" to GeneratedStrings.Activity.steps,
    "steps_goal" to GeneratedStrings.DerivedMissingInputs.Inputs.`steps_goal`,
    "hrv_current" to GeneratedStrings.DerivedMissingInputs.Inputs.`hrv_current`,
    "hrv_7d" to GeneratedStrings.DerivedMissingInputs.Inputs.`hrv_7d`,
    "hrv_14d_series" to GeneratedStrings.DerivedMissingInputs.Inputs.`hrv_14d_series`,
    "hrv_baseline" to GeneratedStrings.DerivedMissingInputs.Inputs.`hrv_baseline`,
    "energy_spo2_current" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_spo2_current`,
    "energy_stress_morning_or_latest" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_stress_morning_or_latest`,
    "energy_stress_today_mean" to GeneratedStrings.DerivedMissingInputs.Inputs.`energy_stress_today_mean`,
    "hours_awake_estimate" to GeneratedStrings.DerivedMissingInputs.Inputs.`hours_awake_estimate`,
    "hours_awake" to GeneratedStrings.DerivedMissingInputs.Inputs.`hours_awake`,
    "body_hr_7d_mean" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_hr_7d_mean`,
    "body_hr_30d_mean" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_hr_30d_mean`,
    "body_hr_7d_max" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_hr_7d_max`,
    "body_hrv_7d_mean" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_hrv_7d_mean`,
    "body_hrv_30d_mean" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_hrv_30d_mean`,
    "body_stress_7d_mean" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_stress_7d_mean`,
    "stress_peaks_24h" to GeneratedStrings.DerivedMissingInputs.Inputs.`stress_peaks_24h`,
    "body_spo2_7d" to GeneratedStrings.DerivedMissingInputs.Inputs.`body_spo2_7d`,
    "weight_kg" to GeneratedStrings.Personal.Metric.weight,
    "height_cm" to GeneratedStrings.DerivedMissingInputs.Inputs.`height_cm`,
    "age" to GeneratedStrings.Personal.age,
    "female" to GeneratedStrings.DerivedMissingInputs.Inputs.`female`,
    "temp_delta_7d" to GeneratedStrings.DerivedMissingInputs.Inputs.`temp_delta_7d`,
    "mood_hrv_current" to GeneratedStrings.DerivedMissingInputs.Inputs.`mood_hrv_current`,
    "mood_hrv_14d" to GeneratedStrings.DerivedMissingInputs.Inputs.`mood_hrv_14d`,
    "mood_stress_current" to GeneratedStrings.DerivedMissingInputs.Inputs.`mood_stress_current`,
    "mood_sleep_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`mood_sleep_minutes`,
    "mood_steps_14d" to GeneratedStrings.DerivedMissingInputs.Inputs.`mood_steps_14d`,
    "sleep_need_minutes" to GeneratedStrings.DerivedMissingInputs.Inputs.`sleep_need_minutes`,
    "breath_current" to GeneratedStrings.DerivedMissingInputs.Inputs.`breath_current`,
    "breath_14d" to GeneratedStrings.DerivedMissingInputs.Inputs.`breath_14d`,
    "breath_today" to GeneratedStrings.DerivedMissingInputs.Inputs.`breath_today`,
    "spo2_today" to GeneratedStrings.DerivedMissingInputs.Inputs.`spo2_today`,
    "spo2_instant" to GeneratedStrings.DerivedMissingInputs.Inputs.`spo2_instant`,
    "spo2_7d_median" to GeneratedStrings.DerivedMissingInputs.Inputs.`spo2_7d_median`,
    "ide_rhr" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_rhr`,
    "ide_hrv_current" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_hrv_current`,
    "ide_hrv_14d" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_hrv_14d`,
    "ide_weight_kg" to GeneratedStrings.DerivedMissingInputs.Inputs.`ide_weight_kg`,
    "local_hour" to GeneratedStrings.DerivedMissingInputs.Inputs.`local_hour`,
    "sleep_segments" to GeneratedStrings.DerivedMissingInputs.Inputs.`sleep_segments`,
    "sleep_debt_durations" to GeneratedStrings.DerivedMissingInputs.Inputs.`sleep_debt_durations`,
    "ecg_waveform" to GeneratedStrings.DerivedMissingInputs.Inputs.`ecg_waveform`,
    "sdk_health_norm" to GeneratedStrings.DerivedMissingInputs.Inputs.`sdk_health_norm`,
    "soul-home-sleep" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-home-sleep`,
    "soul-sleep-screen-score" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-sleep-screen-score`,
    "soul-energy-sleep" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-energy-sleep`,
    "soul-ide-sleep" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-ide-sleep`,
    "soul-body-sleep" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-body-sleep`,
    "soul-rhr-energy" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-rhr-energy`,
    "soul-rhr-night" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-rhr-night`,
    "soul-night-recovery" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-night-recovery`,
    "soul-energy-readiness" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-energy-readiness`,
    "soul-energy-recovery" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-energy-recovery`,
    "soul-energy-pns" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-energy-pns`,
    "soul-body-cardio" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-body-cardio`,
    "soul-body-recovery" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-body-recovery`,
    "soul-body-activity" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-body-activity`,
    "soul-body-stress" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-body-stress`,
    "soul-body-respiratory" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-body-respiratory`,
    "soul-body-metabolism" to GeneratedStrings.DerivedMissingInputs.Inputs.`soul-body-metabolism`,
)

private val personalDerivedMissingLabels: Map<String, PersonalDerivedMissingMessage> = buildMap {
    personalDerivedInputLabels.forEach { (code, label) ->
        put(code, PersonalDerivedMissingMessage(label))
        put("$code:invalid", PersonalDerivedMissingMessage(label, PersonalDerivedMissingQualifier.Invalid))
        put("$code:future", PersonalDerivedMissingMessage(label, PersonalDerivedMissingQualifier.Future))
    }
    put("actual_measurements", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`actual_measurements`))
    put("formula_evidence", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`formula_evidence`))
    put("night_hr:four_samples", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`night_hr_four_samples`))
    put("night_hrv:three_samples", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`night_hrv_three_samples`))
    put("sleep_debt:three_weekly_rows", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`sleep_debt_three_weekly_rows`))
    put("sleep_debt:positive_integer_source_durations", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`sleep_debt_positive_integer_source_durations`))
    put("sleep_goal_minutes:integer_source_goal", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`sleep_goal_minutes_integer_source_goal`))
    put("instant_evaluation", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`instant_evaluation`))
    put("conditions_not_supplied_by_soul_ring_path", PersonalDerivedMissingMessage(GeneratedStrings.DerivedMissingInputs.Conditions.`conditions_not_supplied_by_soul_ring_path`))
}
