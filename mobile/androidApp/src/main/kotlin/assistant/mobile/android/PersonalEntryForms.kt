package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import assistant.mobile.core.personal.PERSONAL_FOLD_SITES
import assistant.mobile.core.personal.PersonalCaliperMeasurement
import assistant.mobile.core.personal.PersonalController
import assistant.mobile.core.personal.PersonalDisplayUnits
import assistant.mobile.core.personal.PersonalState
import assistant.mobile.core.personal.personalAgeOn
import assistant.mobile.core.personal.personalAmountText
import assistant.mobile.core.personal.personalRequiredFoldSites
import assistant.mobile.design.ui.CardTitle
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch

@Composable
internal fun PersonalWeightSheet(state: PersonalState, controller: PersonalController) {
    val draft = state.weightDraft ?: return
    val scope = rememberCoroutineScope()
    val editable = state.canWrite && !state.busy && state.pending.isEmpty()
    PersonalSheet(stringResource(DesignR.string.personal_add_weight), "personal_weight_sheet",
        onDismiss = { scope.launch { controller.dismissDraft("weight") } }) {
        PersonalWriteStatus(state, controller)
        PersonalField(draft.weightKg, { controller.updateWeightDraft(draft.copy(weightKg = it)) },
            DesignR.string.personal_weight_kg, "personal_weight_value", editable, KeyboardType.Decimal)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PersonalField(draft.measuredAt, { controller.updateWeightDraft(draft.copy(measuredAt = it)) },
                DesignR.string.personal_measured_at, "personal_weight_measured", editable)
            Text(stringResource(DesignR.string.personal_zone_hint, draft.timezone.ifBlank { state.timezone }),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { scope.launch { controller.saveWeight() } }, enabled = editable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_weight_save")) {
            Text(stringResource(DesignR.string.personal_save_weight))
        }
    }
}

@Composable
internal fun PersonalCaliperSheet(state: PersonalState, controller: PersonalController, onOpenProfile: () -> Unit) {
    val draft = state.caliperDraft ?: return
    val profile = state.overview.data?.profile
    val scope = rememberCoroutineScope()
    val editable = state.canWrite && !state.busy && state.pending.isEmpty()
    val effectiveGender = if (draft.genderOverridden) draft.gender else profile?.gender.orEmpty()
    val requiredSites = personalRequiredFoldSites(effectiveGender)
    val primary = requiredSites.ifEmpty { PERSONAL_FOLD_SITES }
    val optional = PERSONAL_FOLD_SITES.filter { it !in primary }
    var optionalOpen by remember(state.namespace) { mutableStateOf(false) }
    var overridesOpen by remember(state.namespace) { mutableStateOf(false) }
    PersonalSheet(stringResource(DesignR.string.personal_caliper_add), "personal_caliper_sheet",
        onDismiss = { scope.launch { controller.dismissDraft("caliper") } }) {
        PersonalWriteStatus(state, controller)
        Text(stringResource(DesignR.string.personal_caliper_form_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (requiredSites.isEmpty()) stringResource(DesignR.string.personal_caliper_protocol_unset)
            else stringResource(DesignR.string.personal_caliper_protocol, requiredSites.map { personalFoldLabel(it) }.joinToString(", ")),
            modifier = Modifier.testTag("personal_caliper_protocol"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (requiredSites.isNotEmpty()) Text(stringResource(DesignR.string.personal_caliper_primary_folds),
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        primary.forEach { site -> PersonalFoldField(site, draft.folds[site].orEmpty(), editable) {
            controller.updateCaliperDraft(draft.copy(folds = draft.folds + (site to it)))
        } }
        if (optional.isNotEmpty()) {
            OutlinedButton(onClick = { optionalOpen = !optionalOpen }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("personal_caliper_optional")) { Text(stringResource(DesignR.string.personal_caliper_optional_folds)) }
            if (optionalOpen || optional.any { draft.folds[it].orEmpty().isNotBlank() }) {
                Text(stringResource(DesignR.string.personal_caliper_optional_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                optional.forEach { site -> PersonalFoldField(site, draft.folds[site].orEmpty(), editable) {
                    controller.updateCaliperDraft(draft.copy(folds = draft.folds + (site to it)))
                } }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PersonalField(draft.measuredAt, { controller.updateCaliperDraft(draft.copy(measuredAt = it)) },
                DesignR.string.personal_measured_at, "personal_caliper_measured", editable)
            Text(stringResource(DesignR.string.personal_zone_hint, draft.timezone.ifBlank { state.timezone }),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Text(stringResource(DesignR.string.personal_caliper_profile_metadata), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        val age = personalAgeOn(profile?.birthDate, draft.measuredAt.take(10))
        Text(if (profile?.gender == null) stringResource(DesignR.string.personal_caliper_profile_gender_unknown)
            else stringResource(DesignR.string.personal_caliper_profile_gender, personalGenderLabel(profile.gender)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (age == null) stringResource(DesignR.string.personal_caliper_profile_age_unknown)
            else stringResource(DesignR.string.personal_caliper_profile_age, age.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (profile?.gender == null || age == null) {
            Text(stringResource(DesignR.string.personal_caliper_profile_notification_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(DesignR.string.personal_caliper_profile_form_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpenProfile, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_caliper_open_profile")) {
                Text(stringResource(DesignR.string.personal_body_profile_title))
            }
        }
        OutlinedButton(onClick = { overridesOpen = !overridesOpen }, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("personal_caliper_overrides")) { Text(stringResource(DesignR.string.personal_caliper_measurement_overrides)) }
        if (overridesOpen || draft.genderOverridden || draft.ageYears.isNotBlank()) {
            Text(stringResource(DesignR.string.personal_caliper_overrides_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PersonalGenderChoice(effectiveGender, editable, "personal_caliper_gender") {
                controller.updateCaliperDraft(draft.copy(gender = it, genderOverridden = true))
            }
            PersonalField(draft.ageYears, { controller.updateCaliperDraft(draft.copy(ageYears = it)) },
                DesignR.string.personal_caliper_age, "personal_caliper_age", editable, KeyboardType.Number)
        }
        Text(stringResource(DesignR.string.personal_caliper_metadata_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(DesignR.string.personal_caliper_age_from_profile_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = draft.note, onValueChange = { controller.updateCaliperDraft(draft.copy(note = it)) },
            enabled = editable, label = { Text(stringResource(DesignR.string.personal_caliper_note)) },
            modifier = Modifier.fillMaxWidth().testTag("personal_caliper_note"))
        PersonalCaliperMethod()
        Button(onClick = { scope.launch { controller.saveCaliper() } }, enabled = editable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_caliper_save")) {
            Text(stringResource(DesignR.string.personal_caliper_save))
        }
    }
}

@Composable
private fun PersonalFoldField(site: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        label = { Text(personalFoldLabel(site) + ", " + stringResource(DesignR.string.personal_caliper_mm)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("personal_caliper_$site"))
}

@Composable
private fun PersonalCaliperMethod() {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_caliper_method")) {
        Text(stringResource(DesignR.string.personal_caliper_about))
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.testTag("personal_caliper_method_details")) {
        Text(stringResource(DesignR.string.personal_caliper_method), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(DesignR.string.personal_caliper_technique), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PERSONAL_FOLD_SITES.forEach { site ->
            Text(personalFoldLabel(site), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(when (site) {
                "abdomen" -> DesignR.string.personal_caliper_technique_site_abdomen
                "suprailiac" -> DesignR.string.personal_caliper_technique_site_suprailiac
                "chest" -> DesignR.string.personal_caliper_technique_site_chest
                "thigh" -> DesignR.string.personal_caliper_technique_site_thigh
                else -> DesignR.string.personal_caliper_technique_site_triceps
            }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(DesignR.string.personal_caliper_limits), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PersonalCaliperResult(record: PersonalCaliperMeasurement, state: PersonalState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth().testTag("personal_caliper_result")) {
        CardTitle(stringResource(DesignR.string.personal_caliper_title))
        Text(personalMoment(record.measuredAt, record.timezone, state.locale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        record.bodyFatPercent?.let { value ->
            Text(stringResource(DesignR.string.personal_caliper_result,
                personalAmountText("body_fat_percent", value, PersonalDisplayUnits.Metric, state.locale)?.removeSuffix(" %").orEmpty()),
                modifier = Modifier.testTag("personal_caliper_result_value"), style = MaterialTheme.typography.headlineSmall)
        }
        if (record.bodyFatPercent == null) Text(when {
            record.calculation.status == "incomplete" -> stringResource(DesignR.string.personal_caliper_incomplete,
                record.calculation.missingFields.map { field -> when (field) {
                    "gender" -> stringResource(DesignR.string.personal_caliper_gender)
                    "age_years" -> stringResource(DesignR.string.personal_caliper_age)
                    else -> personalFoldLabel(field)
                } }.joinToString(", "))
            record.calculation.reason == "age_outside_study" ->
                stringResource(DesignR.string.personal_caliper_age_unsupported)
            else -> stringResource(DesignR.string.personal_caliper_unsupported)
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        record.foldsMm.forEach { (site, value) -> Text(personalFoldLabel(site) + " · " + value + " " +
            stringResource(DesignR.string.personal_caliper_mm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (record.gender != null || record.ageYears != null) Text(stringResource(DesignR.string.personal_caliper_snapshot,
            personalGenderLabel(record.gender), record.ageYears?.toString() ?: "—"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        record.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun personalGenderLabel(value: String?): String = stringResource(when (value) {
    "male" -> DesignR.string.personal_gender_male
    "female" -> DesignR.string.personal_gender_female
    else -> DesignR.string.personal_gender_unset
})

@Composable
private fun personalFoldLabel(site: String): String = stringResource(when (site) {
    "abdomen" -> DesignR.string.personal_caliper_sites_abdomen
    "suprailiac" -> DesignR.string.personal_caliper_sites_suprailiac
    "chest" -> DesignR.string.personal_caliper_sites_chest
    "thigh" -> DesignR.string.personal_caliper_sites_thigh
    else -> DesignR.string.personal_caliper_sites_triceps
})
