package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.GeneratedStrings.PersonalHistory as HistoryStrings
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.personal.*
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

@Composable
internal fun PersonalHistoryOperationStatus(state: PersonalHistoryState, controller: PersonalHistoryController) {
    val scope = rememberCoroutineScope()
    state.error?.let { ErrorMessage(it, "personal_history_error") }
    if (state.ownPending.isNotEmpty()) PersonalSection(DesignR.string.outcome_unknown_title, "personal_history_pending") {
        Text(stringResource(DesignR.string.outcome_unknown_text))
        state.ownPending.forEach { pending ->
            TextButton(onClick = { scope.launch { controller.check(pending.key) } }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_history_pending_check_${pending.key}")) { Text(stringResource(DesignR.string.outcome_check)) }
        }
    }
}

@Composable
internal fun PersonalHistoryDerived(state: PersonalHistoryState, controller: PersonalHistoryController, onResult: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    Text(stringResource(DesignR.string.derived_scores_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(DesignR.string.derived_scores_intro), style = MaterialTheme.typography.bodySmall)
    PersonalHistoryOperationStatus(state, controller)
    if (state.canWrite) {
        OutlinedButton(onClick = { scope.launch { controller.backfill() } }, enabled = !state.backfillBlocked,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_derived_backfill")) { Text(localized(HistoryStrings.Backfill.start)) }
        Text(localized(HistoryStrings.Backfill.explanation(state.from, state.to)), style = MaterialTheme.typography.bodySmall)
    }
    if (state.submittedDays != null && state.backfillRun.data == null) Text(localized(HistoryStrings.Backfill.submitted(state.submittedDays.toString())), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    PersonalQueryStatus(state.backfillRun) { scope.launch { controller.refreshBackfill() } }
    state.backfillRun.data?.let { run ->
        OutlinedCard(Modifier.fillMaxWidth().testTag("personal_backfill_state")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized(when (run.state) {
                    "queued" -> HistoryStrings.Backfill.queued
                    "succeeded" -> HistoryStrings.Backfill.succeeded
                    "cancelled" -> HistoryStrings.Backfill.cancelled
                    else -> HistoryStrings.Backfill.failed
                }), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(localized(HistoryStrings.Backfill.progress(run.processedDays.toString(), run.scheduledDays.toString(), run.resultsSaved.toString(), run.resultsUnchanged.toString())))
                Text(localized(HistoryStrings.Backfill.context(run.from, run.to, run.timezone, run.asOf)), style = MaterialTheme.typography.bodySmall)
                run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { scope.launch { controller.refreshBackfill() } }, enabled = !state.backfillRun.loading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("personal_backfill_refresh")) { Text(localized(HistoryStrings.Backfill.refresh)) }
            }
        }
    }
    val refresh = { scope.launch { controller.refresh() }; Unit }
    PersonalQueryStatus(state.derived, refresh)
    state.derived.data?.let { page ->
        if (page.pendingRuns > 0) Text(stringResource(DesignR.string.derived_scores_refresh_pending), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(stringResource(DesignR.string.derived_scores_refresh_counts, page.pendingRuns.toString(), page.failedRuns.toString()), style = MaterialTheme.typography.bodySmall)
        val count = page.days.sumOf { it.scores.size }
        Text(stringResource(DesignR.string.derived_scores_loaded_count, count.toString(), page.totalSummaries.toString()), modifier = Modifier.testTag("personal_derived_summary_count"))
        if (count == 0) Text(stringResource(DesignR.string.derived_scores_empty))
        page.days.sortedByDescending { it.date }.forEach { day ->
            if (day.scores.isNotEmpty()) Text(day.date, style = MaterialTheme.typography.titleMedium)
            day.scores.forEach { score ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(score.label, style = MaterialTheme.typography.titleMedium)
                        personalScoreValue(score)?.let { Text("${formatFoodAmount(it.toString(), 2)} ${score.unit}", style = MaterialTheme.typography.titleLarge) }
                        DerivedStatus(score.status)
                        Text(stringResource(DesignR.string.derived_scores_saved_calculation, score.version.toString(), personalMoment(score.evaluatedAt, state.timezone, state.locale)), style = MaterialTheme.typography.bodySmall)
                        Text((score.context.sourceId?.let { stringResource(DesignR.string.sources_line, it) } ?: stringResource(DesignR.string.derived_scores_owner_context)) + " · ${score.formulaVersion}", style = MaterialTheme.typography.bodySmall)
                        PersonalQuality(score.quality)
                        PersonalHistoryMissingInputs(score.missing, "personal_derived_missing_${score.resultId}")
                        if (score.seriesCount > 0) Text(stringResource(DesignR.string.derived_scores_instant_count, score.seriesCount.toString()), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onResult(score.resultId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_history_score_${score.definitionId}")) { Text(localized(HistoryStrings.Derived.openSaved)) }
                        val versions = state.derivedVersions.data?.items.orEmpty().filter { it.definitionId == score.definitionId && it.date == score.context.date && it.sourceScope == score.context.sourceId }
                        PersonalHistoryDisclosure(stringResource(DesignR.string.derived_scores_versions), "personal_derived_versions_${score.resultId}") {
                            PersonalQueryStatus(state.derivedVersions, refresh)
                            if (versions.isEmpty()) Text(localized(HistoryStrings.Derived.noContextVersions))
                            versions.forEach { DerivedVersionSummary(it, state, onResult) }
                        }
                    }
                }
            }
        }
    }
    PersonalHistoryDisclosure(stringResource(DesignR.string.derived_scores_all_versions), "personal_derived_archive") {
        Text(localized(HistoryStrings.Derived.versionsExplanation), style = MaterialTheme.typography.bodySmall)
        PersonalQueryStatus(state.derivedVersions, refresh)
        state.derivedVersions.data?.let { archive ->
            Text(localized(HistoryStrings.Derived.archiveCounts(archive.totalVersions.toString(), archive.refresh.pendingRuns.toString(), archive.refresh.failedRuns.toString())))
            if (archive.refresh.pending) Text(localized(HistoryStrings.Derived.pending))
            var source by remember(state.from, state.to) { mutableStateOf("") }
            val sources = archive.items.mapNotNull { it.sourceScope }.distinct()
            if (sources.isNotEmpty()) PersonalHistoryChoice(source, listOf("" to localized(HistoryStrings.Derived.allSources)) + sources.map { it to it }, localized(HistoryStrings.Derived.versionSource), "personal_derived_archive_source") { source = it }
            val rows = archive.items.filter { source.isEmpty() || it.sourceScope == source || it.sourceScope == null }
            if (rows.isEmpty()) Text(localized(HistoryStrings.Derived.noContextVersions))
            rows.forEach { DerivedVersionSummary(it, state, onResult) }
        }
    }
    TextButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp).testTag("personal_derived_refresh")) { Text(stringResource(DesignR.string.derived_scores_refresh)) }
}

@Composable
private fun DerivedVersionSummary(result: PersonalArchivedResult, state: PersonalHistoryState, onResult: (String) -> Unit) {
    Text("${result.definition.label} · v${result.version}", style = MaterialTheme.typography.titleSmall)
    Text(localized(if (result.isCurrent) HistoryStrings.Derived.currentContextVersion else HistoryStrings.Derived.previousContextVersion), style = MaterialTheme.typography.bodySmall)
    val value = result.score.value?.toDoubleOrNull()?.takeIf { it.isFinite() && result.score.status in listOf("available", "partial") }
    if (value != null) Text("${formatFoodAmount(value.toString(), 2)} ${result.score.unit}") else Text(localized(HistoryStrings.Derived.noValue))
    PersonalHistoryMissingInputs(result.score.missing, "personal_derived_archive_missing_${result.resultId}")
    Text(localized(HistoryStrings.Derived.savedAt(personalMoment(result.asOf, state.timezone, state.locale), personalMoment(result.createdAt, state.timezone, state.locale))), style = MaterialTheme.typography.bodySmall)
    Text(localized(result.sourceScope?.let { HistoryStrings.Derived.source(it) } ?: HistoryStrings.Derived.ownerAllSources), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { onResult(result.resultId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_derived_version_${result.resultId}")) { Text(localized(HistoryStrings.Derived.openSaved)) }
}

@Composable
internal fun PersonalHistoryMissingInputs(codes: List<String>, tag: String) {
    if (codes.isEmpty()) return
    Text(stringResource(DesignR.string.derived_missing_inputs_title), style = MaterialTheme.typography.labelLarge)
    codes.forEach { code ->
        val message = personalDerivedMissingMessage(code)
        Text(localized(message.withLabel(localized(message.label))), style = MaterialTheme.typography.bodySmall)
    }
    PersonalHistoryDisclosure(stringResource(DesignR.string.derived_missing_inputs_codes_title), "${tag}_codes") {
        codes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DerivedStatus(status: String) { Text(stringResource(when (status) {
    "available" -> DesignR.string.derived_scores_status_available
    "partial" -> DesignR.string.derived_scores_status_partial
    "unqualified" -> DesignR.string.derived_scores_status_unqualified
    else -> DesignR.string.derived_scores_status_unavailable
}), style = MaterialTheme.typography.bodySmall) }

@Composable
internal fun PersonalHistoryDerivedResult(result: PersonalArchivedResult, timezone: String, locale: String) {
    val score = result.score
    Text(result.definition.label, style = MaterialTheme.typography.titleLarge)
    Text(localized(HistoryStrings.Derived.immutableResult(result.version.toString(), result.resultId)), style = MaterialTheme.typography.bodySmall)
    DerivedStatus(score.status)
    score.value?.toDoubleOrNull()?.takeIf { it.isFinite() && score.status in listOf("available", "partial") }?.let { Text("${formatFoodAmount(it.toString(), 2)} ${score.unit}", style = MaterialTheme.typography.titleLarge) }
    PersonalHistoryMissingInputs(score.missing, "personal_derived_detail_missing_${result.resultId}")
    Text(localized(HistoryStrings.Derived.definition), style = MaterialTheme.typography.titleSmall)
    Text("${score.definitionId} · ${score.formulaVersion} · ${score.qualification}")
    Text(localized(HistoryStrings.Derived.requiredInputs), style = MaterialTheme.typography.titleSmall)
    result.definition.requiredInputs.forEach { Text(derivedInputName(it)) }
    Text(localized(HistoryStrings.Derived.asOf), style = MaterialTheme.typography.titleSmall)
    Text("${personalMoment(score.context.asOf, timezone, locale)} · ${score.context.timezone} · ${score.context.evaluation}")
    Text(localized(HistoryStrings.Record.source), style = MaterialTheme.typography.titleSmall)
    Text(score.context.sourceId ?: localized(HistoryStrings.Derived.sourceUnspecified))
    Text(localized(HistoryStrings.Derived.coverage), style = MaterialTheme.typography.titleSmall)
    Text("${score.coverage.observed} / ${score.coverage.expected}" + (score.coverage.ratio?.toDoubleOrNull()?.let { " · ${formatFoodAmount((it * 100).toString(), 1)} %" } ?: ""))
    Text(localized(HistoryStrings.Derived.availability), style = MaterialTheme.typography.titleSmall)
    Text("${score.confidence.level} · ${score.confidence.availableInputs} / ${score.confidence.requiredInputs}")
    Text(localized(HistoryStrings.Derived.coverageExplanation), style = MaterialTheme.typography.bodySmall)
    score.baseline?.let { baseline ->
        Text(localized(HistoryStrings.Derived.baseline), style = MaterialTheme.typography.titleSmall)
        Text(localized(HistoryStrings.Derived.baselineSource(baseline.archiveText("as_of").orEmpty(), baseline.archiveText("source_id") ?: localized(HistoryStrings.Derived.ownerContext))))
        (baseline["components"] as? JsonArray).orEmpty().forEachIndexed { index, item -> (item as? JsonObject)?.let { component ->
            Text(component.archiveText("id").orEmpty(), style = MaterialTheme.typography.labelLarge)
            val start = component.archiveText("window_start"); val end = component.archiveText("window_end")
            if (start != null && end != null) Text("$start — $end")
            component.archiveText("points")?.let { Text(localized(HistoryStrings.Derived.points(it))) }
            component.archiveText("estimator")?.let { Text(localized(HistoryStrings.Derived.estimator(it))) }
            PersonalHistoryJson(localized(HistoryStrings.Derived.baselineEvidence), component, "personal_derived_baseline_$index")
        } }
    }
    score.components.forEachIndexed { index, component ->
        Text("${derivedInputName(component.id)}: ${derivedComponentValue(component.value)} ${component.unit}", style = MaterialTheme.typography.bodyMedium)
        if (component.synthetic) Text(localized(HistoryStrings.Derived.synthetic(localized(when (component.reason) {
            "soul_reference_default" -> GeneratedStrings.DerivedMissingInputs.SyntheticReasons.referenceDefault
            "soul_age_reference" -> GeneratedStrings.DerivedMissingInputs.SyntheticReasons.ageReference
            else -> GeneratedStrings.DerivedMissingInputs.SyntheticReasons.unknown
        })))) else PersonalQuality(component.quality)
        PersonalHistoryDisclosure(localized(HistoryStrings.Derived.componentCode), "personal_derived_component_$index") {
            Text(component.id)
            component.reason?.let { Text("${localized(HistoryStrings.Derived.reason)} $it") }
        }
        if (component.value is JsonArray) PersonalHistoryJson(localized(HistoryStrings.Derived.componentSeries), component.value, "personal_derived_component_series_$index")
    }
    PersonalQuality(score.quality)
    PersonalHistoryDisclosure(localized(HistoryStrings.Derived.inputVersions), "personal_derived_input_versions") {
        score.inputs.forEach { input -> Text("${derivedReference(input)} · ${input.id} · v${input.revision}", style = MaterialTheme.typography.bodySmall) }
    }
    PersonalHistoryJson(localized(HistoryStrings.Derived.definitionEvidence), result.definition.raw, "personal_derived_definition")
    PersonalHistoryJson(localized(HistoryStrings.Derived.savedEvidence), result.raw, "personal_derived_saved_evidence")
}

@Composable
private fun derivedInputName(code: String): String {
    val message = personalDerivedMissingMessage(code)
    return if (message.label.key.webKey == GeneratedStrings.DerivedMissingInputs.unknown.key.webKey) localized(GeneratedStrings.DerivedMissingInputs.inputUnknown)
        else localized(message.withLabel(localized(message.label)))
}

@Composable
private fun derivedComponentValue(value: JsonElement): String = when {
    value == JsonNull -> localized(HistoryStrings.Derived.noData)
    value is JsonPrimitive && value.booleanOrNull != null -> localized(if (value.booleanOrNull == true) HistoryStrings.Derived.Component.yes else HistoryStrings.Derived.Component.no)
    value is JsonPrimitive && value.doubleOrNull != null -> formatFoodAmount(requireNotNull(value.doubleOrNull).toString(), 2)
    value is JsonArray -> localized(if (value.any { it is JsonObject }) HistoryStrings.Derived.Component.sleepStages(value.size.toString()) else HistoryStrings.Derived.Component.numericSeries(value.size.toString()))
    else -> "—"
}

@Composable
private fun derivedReference(input: PersonalDerivedInput): String {
    val raw = input.raw
    val source = input.sourceId ?: localized(HistoryStrings.Derived.Reference.owner)
    return localized(when (input.type) {
        "record", "sample", "manual_day_total" -> HistoryStrings.Derived.Reference.measurement(input.type, input.metric.orEmpty(), input.observedAt ?: localized(HistoryStrings.Derived.Reference.timeUnknown), source)
        "activity_report" -> HistoryStrings.Derived.Reference.activityReport(input.observedAt ?: localized(HistoryStrings.Derived.Reference.timeUnknown), source)
        "profile" -> HistoryStrings.Derived.Reference.profile(input.metric.orEmpty())
        "preference" -> HistoryStrings.Derived.Reference.preference(input.metric.orEmpty())
        "resolution_state" -> HistoryStrings.Derived.Reference.resolutionState(raw.archiveText("fingerprint").orEmpty())
        "evaluation_capture" -> HistoryStrings.Derived.Reference.evaluationCapture(raw.archiveText("captured_at").orEmpty(), raw.archiveText("basis").orEmpty())
        "derivation_context" -> HistoryStrings.Derived.Reference.context(source, raw.archiveText("fingerprint").orEmpty())
        "software_context" -> HistoryStrings.Derived.Reference.softwareContext(raw.archiveText("formula_version") ?: raw.archiveText("producer_version") ?: raw.archiveText("fingerprint").orEmpty())
        "source_identity" -> HistoryStrings.Derived.Reference.identity(source, localized(if ((raw["confirmed"] as? JsonPrimitive)?.booleanOrNull == true) HistoryStrings.Derived.Reference.confirmed else HistoryStrings.Derived.Reference.unconfirmed), raw.archiveText("physical_id")?.let { " · $it" }.orEmpty())
        else -> return input.type
    })
}
