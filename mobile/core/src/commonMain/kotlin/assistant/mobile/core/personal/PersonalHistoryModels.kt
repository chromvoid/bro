package assistant.mobile.core.personal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val PERSONAL_HISTORY_PERIODS = listOf(7, 30, 90, 365)
val PERSONAL_HISTORY_METRICS = listOf("weight", "body", "sleep", "steps", "active_minutes", "active_energy", "distance") +
    PERSONAL_BODY_KINDS + PERSONAL_SENSOR_KINDS + listOf("derived_scores", "all")

data class PersonalHistoryPage(
    val from: String, val to: String, val records: List<PersonalObservation>,
    val daily: List<PersonalResolvedDay>, val evidenceById: Map<String, JsonObject>,
    val analyses: List<PersonalAnalysisSummary> = emptyList(), val sources: List<PersonalSource> = emptyList(),
    val truncated: Boolean = false,
    val restrictedSources: List<PersonalSource> = emptyList(),
)

data class PersonalSensorSample(
    val id: String, val sourceId: String, val sourceName: String, val metric: String,
    val startAt: String, val endAt: String, val timezone: String, val localDate: String?,
    val value: String?, val reportedMin: String?, val reportedMax: String?, val unit: String,
    val quality: List<String>, val revision: Long, val excluded: Boolean, val annotationRevision: Long,
    val raw: JsonObject,
)
data class PersonalSensorPage(
    val from: String, val to: String, val items: List<PersonalSensorSample>, val fingerprint: String,
    val count: Int, val nextCursor: String?,
)

/** Archived snapshots retain every key and original JSON scalar; no current-schema defaults. */
data class PersonalRecordVersion(val revision: Long, val snapshot: JsonObject, val operationId: String?, val createdAt: String?)
data class PersonalRecordVersions(
    val id: String, val metric: String, val sourceId: String?, val sourceName: String?, val sourceStatus: String?,
    val currentRevision: Long, val count: Int, val fingerprint: String, val versions: List<PersonalRecordVersion>,
    val annotation: JsonObject, val nextCursor: String?,
)

data class PersonalRawReport(
    val id: String, val sourceId: String, val sourceName: String, val metric: String,
    val revision: Long, val sourceVersion: Long, val localDate: String, val timezone: String,
    val observedAt: String, val receivedAt: String, val layout: String, val reason: String,
    val payloadHex: String, val quality: List<String>, val isCurrent: Boolean, val currentRevision: Long,
    val interpretation: JsonObject?, val raw: JsonObject,
)
data class PersonalRawReportPage(
    val from: String, val to: String, val items: List<PersonalRawReport>, val count: Int,
    val fingerprint: String, val nextCursor: String?,
)

data class PersonalAnalysisSummary(
    val id: String, val sourceId: String, val sourceName: String?, val providerRecordId: String,
    val measuredAt: String, val revision: Long, val receivedAt: String,
)
data class PersonalSourceAnalysis(val summary: PersonalAnalysisSummary, val payload: JsonObject)

data class PersonalDerivedComponent(
    val id: String, val value: JsonElement, val unit: String, val synthetic: Boolean,
    val quality: List<String>, val reason: String?,
)
data class PersonalDerivedInput(
    val type: String, val id: String, val revision: Long, val sourceId: String?, val metric: String?,
    val observedAt: String?, val raw: JsonObject,
)
data class PersonalDerivedDefinition(
    val id: String, val label: String, val unit: String, val formulaVersion: String,
    val qualification: String, val requiredInputs: List<String>, val evidence: List<String>, val raw: JsonObject,
)
data class PersonalArchivedScore(
    val definitionId: String, val formulaVersion: String, val qualification: String, val status: String,
    val value: String?, val unit: String, val missing: List<String>, val quality: List<String>,
    val confidence: PersonalDerivedConfidence, val coverage: PersonalDerivedCoverage,
    val context: PersonalDerivedContext, val components: List<PersonalDerivedComponent>,
    val inputs: List<PersonalDerivedInput>, val baseline: JsonObject?, val fingerprint: String,
    val series: List<PersonalArchivedScore>, val raw: JsonObject,
)
data class PersonalArchivedResult(
    val resultId: String, val version: Long, val definitionId: String, val formulaVersion: String,
    val sourceScope: String?, val date: String, val evaluation: String, val timezone: String, val asOf: String,
    val temporalBasis: String, val score: PersonalArchivedScore, val definition: PersonalDerivedDefinition,
    val normalizedInputs: JsonObject, val contextRevisions: List<PersonalDerivedInput>,
    val inputAdapterVersion: String, val implementationDigest: String, val implementationSource: JsonObject,
    val operationId: String, val createdAt: String, val isCurrent: Boolean, val raw: JsonObject,
)
data class PersonalDerivedRefresh(val pending: Boolean, val pendingRuns: Int, val failedRuns: Int)
data class PersonalDerivedArchivePage(
    val from: String, val to: String, val timezone: String, val scope: String,
    val items: List<PersonalArchivedResult>, val totalVersions: Int, val fingerprint: String,
    val refresh: PersonalDerivedRefresh, val nextCursor: String?,
)
data class PersonalBackfillRun(
    val runId: String, val state: String, val from: String, val to: String, val nextDate: String,
    val asOf: String, val timezone: String, val sourceScope: String?, val trigger: String,
    val processedDays: Int, val scheduledDays: Int, val resultsSaved: Int, val resultsUnchanged: Int,
    val error: String?, val createdAt: String, val finishedAt: String?,
)
data class PersonalBackfillReceipt(val runId: String, val from: String, val to: String, val asOf: String, val scheduledDays: Int)
