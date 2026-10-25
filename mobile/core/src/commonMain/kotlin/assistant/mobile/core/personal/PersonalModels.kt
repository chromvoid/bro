package assistant.mobile.core.personal

import kotlinx.serialization.json.JsonObject

/** Display conversions never alter the canonical decimals captured in an edit draft. */
enum class PersonalDisplayUnits(val wire: String) {
    Metric("metric"), Imperial("imperial");
    companion object {
        fun fromWire(value: String): PersonalDisplayUnits = requireNotNull(entries.find { it.wire == value })
    }
}

data class PersonalProfile(
    val userId: String,
    val timezone: String,
    val displayUnits: PersonalDisplayUnits,
    val birthDate: String?,
    val heightCm: String?,
    val gender: String?,
    val revision: Long,
    val genderOrigin: String? = null,
)

data class PersonalPreference(val kind: String, val target: String?, val revision: Long)
data class PersonalSource(val id: String, val name: String, val status: String, val revision: Long)

data class PersonalObservation(
    val id: String,
    val kind: String,
    val value: String?,
    val startAt: String,
    val endAt: String,
    val timezone: String,
    val method: String,
    val revision: Long,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val localDate: String? = null,
    val quality: List<String> = emptyList(),
    val excluded: Boolean = false,
    val corrected: Boolean = false,
    val stages: List<PersonalSleepStage> = emptyList(),
    val provider: String? = null,
    val providerRecordId: String? = null,
    val originalStartAt: String? = null,
    val originalProviderRecordId: String? = null,
    val overrideRevision: Long = 0,
    val algorithmVersion: String? = null,
)

data class PersonalFact(
    val id: String,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val contribution: String? = null,
    val reason: String? = null,
    val startAt: String? = null,
    val endAt: String? = null,
    val revision: Long? = null,
    val type: String? = null,
    val reportedAsleepMinutes: Double? = null,
)

data class PersonalContributionSpan(val startAt: String, val endAt: String)
data class PersonalContribution(val sourceId: String?, val sourceName: String?, val count: Int, val value: String?,
    val spans: List<PersonalContributionSpan> = emptyList())

data class PersonalCoverage(
    val status: String? = null,
    val reportedSeconds: Double? = null,
    val coveredSeconds: Double? = null,
    val complete: Boolean? = null,
    val pointCount: Int? = null,
    val intervalCount: Int = 0,
)

data class PersonalStatisticsBlock(
    val count: Int?, val mean: String?, val min: String?, val max: String?,
    val coveredSeconds: Double? = null,
    val firstAt: String? = null, val latestAt: String? = null,
    val sampleCount: Int? = null, val usableSampleCount: Int? = null, val sampledSeconds: Double? = null,
    val sampleCoverageRatio: Double? = null, val gapCount: Int? = null, val maxInterSampleGapMs: Double? = null,
)
data class PersonalStatistics(
    val points: PersonalStatisticsBlock? = null,
    val intervalMeans: PersonalStatisticsBlock? = null,
    val ranges: PersonalStatisticsBlock? = null,
    val sampleWindows: PersonalStatisticsBlock? = null,
    val groups: List<PersonalStatistics> = emptyList(),
    val measurementKind: String? = null,
    val sensorLocation: String? = null,
    val definitionId: String? = null,
    val temperatureBasis: String? = null,
    val context: String? = null,
    val sourceNames: List<String> = emptyList(),
)

data class PersonalSleepStage(val startAt: String, val endAt: String, val stage: String)
data class PersonalSleepSession(
    val id: String, val startAt: String, val endAt: String, val asleepMinutes: String?,
    val sourceId: String?, val sourceName: String?,
    val stages: List<PersonalSleepStage> = emptyList(),
    val positionChanges: List<String> = emptyList(),
)
data class PersonalSleepSegment(
    val startAt: String, val endAt: String, val state: String, val stage: String?,
    val reason: String, val unknownCodes: List<String> = emptyList(),
    val evidence: List<PersonalFact> = emptyList(), val selectedRefs: List<PersonalFact> = emptyList(),
)
data class PersonalSleepSummary(
    val windowMinutes: Double,
    val asleepMinutes: Double,
    val awakeMinutes: Double,
    val unknownMinutes: Double,
    val conflictMinutes: Double,
    val classificationCoverage: Double,
    val completeAsleepMinutes: Double?,
    val forecastAsleepMinutes: Double?,
)
data class PersonalSleepForecastValidation(
    val nights: Int, val maeMinutes: Double, val p90ErrorMinutes: Double,
    val baselineMaeMinutes: Double, val reference: String,
)
data class PersonalSleepNight(
    val onsetAt: String, val finalWakeAt: String, val midpointAt: String,
    val latencyMinutes: Double?, val efficiency: Double?, val wasoMinutes: Double?,
    val awakenings: Int?, val turnsPerHour: Double?, val heartRateLowest: Double?,
    val heartRateMean: Double?, val heartRateReadings: Int?, val hrvMean: Double?, val hrvReadings: Int?,
)
data class PersonalSleepBounds(val minMinutes: Double, val maxMinutes: Double)
data class PersonalSleepForecast(
    val status: String, val algorithmVersion: String, val estimatedAsleepMinutes: Double?,
    val estimatedMissingMinutes: Double?, val trainingNights: Int, val reason: String?,
    val basis: String? = null, val trainingFrom: String? = null, val trainingTo: String? = null,
    val validation: PersonalSleepForecastValidation? = null,
)
data class PersonalSleepEpisode(
    val startAt: String, val endAt: String, val status: String,
    val asleepMinutes: Double, val awakeMinutes: Double, val unknownMinutes: Double,
    val conflictMinutes: Double, val completeAsleepMinutes: Double?,
    val stageMinutes: Map<String, Double>, val flags: List<String>,
    val forecast: PersonalSleepForecast, val segments: List<PersonalSleepSegment>,
    val positionChanges: List<String>,
    val windowMinutes: Double? = null, val classificationCoverage: Double? = null,
    val bounds: PersonalSleepBounds? = null, val night: PersonalSleepNight? = null,
    val reports: List<PersonalFact> = emptyList(),
)
data class PersonalSleepAnalysis(
    val version: String, val fingerprint: String, val status: String, val computedAt: String,
    val summary: PersonalSleepSummary, val episodes: List<PersonalSleepEpisode>,
    val inputRefs: List<PersonalFact> = emptyList(), val rejectedReports: List<PersonalFact> = emptyList(),
    val inputCount: Int? = null,
)

data class PersonalResolution(
    val status: String,
    val fingerprint: String,
    val resolverVersion: String,
    val selected: List<PersonalFact> = emptyList(),
    val alternatives: List<PersonalFact> = emptyList(),
    val protocolVerified: Boolean? = null,
    val protocolFlags: List<String> = emptyList(),
    val qualityFlags: List<String> = emptyList(),
    val coverage: PersonalCoverage? = null,
    val measuredAt: String? = null,
    val sessions: List<PersonalSleepSession> = emptyList(),
    val sleepAnalysis: PersonalSleepAnalysis? = null,
    val statistics: PersonalStatistics? = null,
    val selectedCount: Int? = null,
    val alternativesCount: Int? = null,
    val explanationTruncated: Boolean = false,
    val contributions: List<PersonalContribution> = emptyList(),
)

data class PersonalMetric(
    val kind: String,
    val status: String,
    val value: String?,
    val unit: String,
    val target: String? = null,
    val complete: Boolean? = null,
    val records: List<PersonalObservation> = emptyList(),
    val sourceUnavailable: Boolean = false,
    val resolution: PersonalResolution? = null,
    val sourceId: String? = null,
    val sourceName: String? = null,
)
data class PersonalNutritionMetric(
    val status: String, val value: String?, val unit: String, val complete: Boolean?,
    val target: String?, val remaining: String?, val estimatedCount: Int?, val pendingCount: Int?,
)
data class PersonalOverview(
    val date: String, val timezone: String, val profile: PersonalProfile,
    val metrics: Map<String, PersonalMetric>, val nutrition: PersonalNutritionMetric?,
    val preferences: Map<String, PersonalPreference>, val sources: List<PersonalSource>,
    val metricsBySource: Map<String, List<PersonalMetric>> = emptyMap(),
)

data class PersonalCatalogMetric(val key: String, val unit: String, val shape: String, val label: String?)
data class PersonalResolvedDay(val date: String, val metric: PersonalMetric)
data class PersonalResolvedPage(
    val from: String, val to: String, val timezone: String, val computedAt: String,
    val daily: List<PersonalResolvedDay>, val fingerprint: String, val nextCursor: String?,
    val series: List<JsonObject> = emptyList(),
)

data class PersonalDerivedConfidence(val level: String, val availableInputs: Int, val requiredInputs: Int)
data class PersonalDerivedCoverage(val observed: Int, val expected: Int, val ratio: String?)
data class PersonalDerivedContext(
    val evaluation: String, val date: String, val timezone: String, val asOf: String, val sourceId: String?,
)
data class PersonalDerivedScore(
    val definitionId: String, val label: String, val formulaVersion: String,
    val qualification: String, val status: String, val value: String?, val unit: String,
    val missing: List<String>, val quality: List<String>,
    val confidence: PersonalDerivedConfidence, val coverage: PersonalDerivedCoverage,
    val context: PersonalDerivedContext, val resultId: String, val version: Long,
    val evaluatedAt: String, val seriesCount: Int,
)
data class PersonalDerivedDay(val date: String, val scores: List<PersonalDerivedScore>)
data class PersonalDerivedScoresPage(
    val from: String, val to: String, val timezone: String, val days: List<PersonalDerivedDay>,
    val fingerprint: String, val nextCursor: String?, val totalSummaries: Int,
    val pendingRuns: Int = 0, val failedRuns: Int = 0,
)

data class PersonalCaliperCalculation(
    val status: String, val method: String, val missingFields: List<String>,
    val algorithmVersion: String, val reason: String?,
)
data class PersonalCaliperMeasurement(
    val id: String, val sourceId: String, val measuredAt: String, val timezone: String,
    val foldsMm: Map<String, String>, val gender: String?, val ageYears: Int?, val note: String?,
    val bodyFatPercent: String?, val calculation: PersonalCaliperCalculation,
    val revision: Long, val bodyFatRecordId: String?,
)
data class PersonalCaliperHistory(val records: List<PersonalCaliperMeasurement>, val truncated: Boolean, val limit: Int)

val PERSONAL_BODY_KINDS = listOf("body_fat_percent", "body_fat_mass", "skeletal_muscle_mass", "fat_free_mass", "body_water", "body_protein", "body_minerals", "bmi", "basal_metabolic_rate", "visceral_fat_level")
val PERSONAL_TARGET_KINDS = listOf("weight", "sleep", "steps", "active_minutes", "active_energy", "distance")
val PERSONAL_SENSOR_KINDS = listOf("heart_rate", "spo2", "temperature", "hrv", "stress")
val PERSONAL_FOLD_SITES = listOf("abdomen", "suprailiac", "chest", "thigh", "triceps")
