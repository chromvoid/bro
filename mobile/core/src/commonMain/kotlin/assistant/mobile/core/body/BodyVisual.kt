package assistant.mobile.core.body

import assistant.mobile.core.personal.PERSONAL_BODY_KINDS
import assistant.mobile.core.personal.PersonalAnalysisSummary
import assistant.mobile.core.personal.PersonalHistoryPage
import assistant.mobile.core.personal.PersonalObservation
import assistant.mobile.core.personal.PersonalOverview
import assistant.mobile.core.personal.PersonalProfile
import assistant.mobile.core.personal.PersonalDisplayUnits
import assistant.mobile.core.personal.personalUnitOf
import assistant.mobile.core.food.plainFoodDecimal
import assistant.mobile.core.today.formatTodayAmount
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Instant

enum class BodyTemplate(val wire: String) { Male("male"), Female("female") }
enum class BodyCharacter(val wire: String) {
    Sculpture("sculpture"), Spartan("spartan");
    companion object { fun fromWire(value: String) = requireNotNull(entries.find { it.wire == value }) }
}
enum class BodyVariant(val wire: String) {
    Male("male"), Female("female"), MaleSpartan("male_spartan");
    companion object { fun fromWire(value: String) = requireNotNull(entries.find { it.wire == value }) }
}
fun bodyVariant(template: BodyTemplate, character: BodyCharacter): BodyVariant = when {
    template == BodyTemplate.Female -> BodyVariant.Female
    character == BodyCharacter.Spartan -> BodyVariant.MaleSpartan
    else -> BodyVariant.Male
}
enum class BodyLayer(val wire: String, val kinds: List<String>) {
    Composition("composition", listOf("weight", "body_fat_percent", "skeletal_muscle_mass", "body_water")),
    Muscle("muscle", listOf("skeletal_muscle_mass", "fat_free_mass", "body_protein", "body_minerals")),
    Fat("fat", listOf("body_fat_percent", "body_fat_mass", "visceral_fat_level", "bmi")),
    Water("water", listOf("body_water", "fat_free_mass", "body_protein", "body_minerals")),
}
enum class BodyView(val wire: String, val yaw: Double) {
    Front("front", 0.0), Side("side", kotlin.math.PI / 2), Back("back", kotlin.math.PI), ThreeQuarter("three-quarter", 0.48);
    companion object { fun fromWire(value: String) = requireNotNull(entries.find { it.wire == value }) }
}
enum class BodyAxis(val wire: String) { Fat("fat"), Muscle("muscle") }
enum class VisualUnavailable(val wire: String) {
    Missing("missing"), Excluded("excluded"), Conflict("conflict"), Invalid("invalid"), HeightMissing("height_missing"),
}
enum class CalibrationBoundary(val wire: String) { Below("below"), Above("above") }

/** Artistic coordinates only. Regional masses remain unavailable under the current DDX contract. */
data class BodyVisualState(
    val template: BodyTemplate = BodyTemplate.Male,
    val fat: Double? = null,
    val muscle: Double? = null,
    val version: String = "body-visual-v2",
    val regions: Map<String, BodyRegionVisual> = BODY_REGIONS.associateWith { BodyRegionVisual() },
)
data class BodyRegionVisual(val fat: Double? = null, val lean: Double? = null, val unavailable: String = "unverified_contract")
val BODY_REGIONS = listOf("left_arm", "right_arm", "trunk", "left_leg", "right_leg")
data class BodyVisual(
    val visual: BodyVisualState,
    val unavailable: Map<BodyAxis, VisualUnavailable?>,
    val calibrationBoundaries: Map<BodyAxis, CalibrationBoundary?>,
    val genderMissing: Boolean,
    val heightMissing: Boolean,
)
data class BodyPreviousMeasurement(val record: PersonalObservation, val measuredAt: String)
data class BodyAnalysis(
    val id: String, val sourceId: String?, val sourceName: String?, val measuredAt: String,
    val records: List<PersonalObservation>, val values: Map<String, PersonalObservation> = emptyMap(),
    val previous: Map<String, BodyPreviousMeasurement> = emptyMap(), val archive: PersonalAnalysisSummary? = null,
)

private val instantKinds = listOf("weight") + PERSONAL_BODY_KINDS
private fun instant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
private fun numericValue(value: PersonalObservation): Double? = value.value?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** A provider identity, never a shared timestamp, is evidence that records belong to a scan. */
fun bodyAnalyses(
    records: List<PersonalObservation>, archives: List<PersonalAnalysisSummary> = emptyList(),
    evidenceById: Map<String, JsonObject> = emptyMap(),
): List<BodyAnalysis> {
    data class Group(val sourceId: String?, val sourceName: String?, var measuredAt: String,
        val archive: PersonalAnalysisSummary?, val records: MutableList<PersonalObservation> = mutableListOf())
    fun id(source: String, provider: String) = JsonArray(listOf(JsonPrimitive(source), JsonPrimitive(provider))).toString()
    val groups = linkedMapOf<String, Group>()
    archives.forEach { groups[id(it.sourceId, it.providerRecordId)] = Group(it.sourceId, it.sourceName, it.measuredAt, it) }
    records.filter { it.kind in instantKinds }.forEach { supplied ->
        val raw = evidenceById[supplied.id]
        val original = raw?.get("original") as? JsonObject
        val origin = (original?.get("origin") as? JsonObject) ?: (raw?.get("origin") as? JsonObject)
        fun text(row: JsonObject?, key: String) = (row?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val record = if (raw == null) supplied else supplied.copy(provider = text(origin, "provider"),
            providerRecordId = text(origin, "record_id"), originalProviderRecordId = text(original?.get("origin") as? JsonObject, "record_id"),
            originalStartAt = text(original, "start_at"))
        val provider = record.originalProviderRecordId ?: record.providerRecordId
        val key = if (record.sourceId != null && provider != null) id(record.sourceId, provider) else id("record", record.id)
        val measuredAt = record.originalStartAt ?: record.startAt
        val group = groups.getOrPut(key) { Group(record.sourceId, record.sourceName, measuredAt, null) }
        group.records += record
        if (group.archive == null && instant(measuredAt)?.let { time -> instant(group.measuredAt)?.let { time < it } } == true) group.measuredAt = measuredAt
    }
    val ordered = groups.filterValues { group -> group.archive != null || group.records.any { it.kind in PERSONAL_BODY_KINDS } }
        .map { (id, group) ->
            val sorted = group.records.sortedWith(compareBy<PersonalObservation> { instantKinds.indexOf(it.kind) }.thenBy { it.id })
            val values = instantKinds.mapNotNull { kind -> sorted.filter { it.kind == kind && !it.excluded }.singleOrNull()
                ?.takeIf { numericValue(it) != null }?.let { kind to it } }.toMap()
            BodyAnalysis(id, group.sourceId, group.sourceName, group.measuredAt, sorted, values, archive = group.archive)
        }.sortedWith(compareBy<BodyAnalysis> { instant(it.measuredAt) }.thenBy { it.id })
    val preceding = mutableMapOf<String, MutableMap<String, Pair<PersonalObservation?, String>>>()
    return ordered.map { analysis ->
        val source = analysis.sourceId?.let { preceding.getOrPut(it) { mutableMapOf() } }
        val previous = analysis.values.mapNotNull { (kind, _) ->
            val prior = source?.get(kind)
            if (prior?.first != null && instant(prior.second)?.let { time -> instant(analysis.measuredAt)?.let { time < it } } == true)
                kind to BodyPreviousMeasurement(requireNotNull(prior.first), prior.second) else null
        }.toMap()
        analysis.values.forEach { (kind, record) ->
            val sameTime = source?.get(kind)?.second?.let { instant(it) == instant(analysis.measuredAt) } == true
            source?.set(kind, (if (sameTime) null else record) to analysis.measuredAt)
        }
        analysis.copy(previous = previous)
    }.reversed()
}

/** Neutral deltas use percentage points for fat, preserving the selected display mass unit. */
fun bodyDelta(current: PersonalObservation, previous: PersonalObservation,
    units: PersonalDisplayUnits = PersonalDisplayUnits.Metric, locale: String = "ru"): String? {
    if (current.kind != previous.kind || current.sourceId == null || current.sourceId != previous.sourceId || current.excluded || previous.excluded) return null
    val now = numericValue(current) ?: return null; val before = numericValue(previous) ?: return null
    val canonicalUnit = personalUnitOf(current.kind)
    val raw = (now - before) / if (canonicalUnit == "кг" && units == PersonalDisplayUnits.Imperial) 0.45359237 else 1.0
    val difference = floor(raw * 100 + 0.5) / 100
    val unit = if (current.kind == "body_fat_percent") "п. п." else if (canonicalUnit == "кг" && units == PersonalDisplayUnits.Imperial) "lb" else canonicalUnit
    val sign = if (difference > 0) "+" else if (difference < 0) "−" else ""
    return "$sign${formatTodayAmount(plainFoodDecimal(abs(difference).toString()), 2, locale)} $unit"
}
fun dashboardBodyDelta(current: PersonalObservation, previous: PersonalObservation,
    units: PersonalDisplayUnits = PersonalDisplayUnits.Metric, locale: String = "ru"): String? =
    if (current.method == previous.method && current.algorithmVersion == previous.algorithmVersion) bodyDelta(current, previous, units, locale) else null

fun bodyAnalyses(history: PersonalHistoryPage): List<BodyAnalysis> = bodyAnalyses(history.records, history.analyses, history.evidenceById)

fun visualScale(value: Double, anchors: List<Double>): Double {
    require(anchors.size == 3 && anchors[0] < anchors[1] && anchors[1] < anchors[2])
    val (low, middle, high) = anchors
    return when { value <= low -> 0.0; value >= high -> 1.0; value <= middle -> (value - low) / (middle - low) * 0.5
        else -> 0.5 + (value - middle) / (high - middle) * 0.5 }
}

fun bodyVisual(analysis: BodyAnalysis, profile: PersonalProfile? = null): BodyVisual {
    fun measurement(kind: String): Pair<Double?, VisualUnavailable?> {
        val rows = analysis.records.filter { it.kind == kind }
        val active = rows.filter { !it.excluded }
        val reason = when { active.isEmpty() -> if (rows.isEmpty()) VisualUnavailable.Missing else VisualUnavailable.Excluded
            active.size > 1 -> VisualUnavailable.Conflict; else -> null }
        if (reason != null) return null to reason
        val value = numericValue(active.single())
        return if (value == null || value < 0 || (kind == "body_fat_percent" && value > 100)) null to VisualUnavailable.Invalid else value to null
    }
    val (fat, fatReason) = measurement("body_fat_percent")
    val (muscle, missingMuscle) = measurement("skeletal_muscle_mass")
    val height = profile?.heightCm?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 50.0..260.0 }
    val muscleReason = missingMuscle ?: if (height == null) VisualUnavailable.HeightMissing else null
    val muscleIndex = if (muscleReason == null && muscle != null && height != null) muscle / ((height / 100) * (height / 100)) else null
    val fatAnchors = listOf(10.0, 25.0, 40.0); val muscleAnchors = listOf(6.0, 9.0, 12.0)
    fun boundary(value: Double?, anchors: List<Double>) = when { value == null -> null
        value < anchors[0] -> CalibrationBoundary.Below; value > anchors[2] -> CalibrationBoundary.Above; else -> null }
    return BodyVisual(BodyVisualState(if (profile?.gender == "female") BodyTemplate.Female else BodyTemplate.Male,
        fat?.let { visualScale(it, fatAnchors) }, muscleIndex?.let { visualScale(it, muscleAnchors) }),
        mapOf(BodyAxis.Fat to fatReason, BodyAxis.Muscle to muscleReason),
        mapOf(BodyAxis.Fat to boundary(fat, fatAnchors), BodyAxis.Muscle to boundary(muscleIndex, muscleAnchors)),
        profile?.gender.isNullOrEmpty(), height == null)
}

fun bodyComparisonScans(analyses: List<BodyAnalysis>, selected: BodyAnalysis, profile: PersonalProfile? = null): List<BodyAnalysis> {
    if (selected.sourceId == null || instant(selected.measuredAt) == null) return emptyList()
    val current = bodyVisual(selected, profile).visual
    return analyses.filter { analysis ->
        val prior = bodyVisual(analysis, profile).visual
        analysis.id != selected.id && analysis.sourceId == selected.sourceId &&
            instant(analysis.measuredAt)?.let { it < requireNotNull(instant(selected.measuredAt)) } == true &&
            ((current.fat != null && prior.fat != null) || (current.muscle != null && prior.muscle != null))
    }.sortedWith(compareByDescending<BodyAnalysis> { instant(it.measuredAt) }.thenBy { it.id })
}
fun interpolateBodyVisual(previous: BodyVisualState, selected: BodyVisualState, fraction: Double): BodyVisualState {
    val amount = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 1.0
    fun blend(from: Double?, to: Double?) = if (from == null || to == null) to else from + (to - from) * amount
    return selected.copy(fat = blend(previous.fat, selected.fat), muscle = blend(previous.muscle, selected.muscle))
}

data class BodyDashboard(val analyses: List<BodyAnalysis>, val current: BodyAnalysis?)
fun dashboardBody(overview: PersonalOverview, history: PersonalHistoryPage?, day: String, timezone: String): BodyDashboard {
    val byId = linkedMapOf<String, PersonalObservation>()
    fun merge(record: PersonalObservation) {
        val old = byId[record.id]
        if (old == null || record.revision > old.revision || (record.revision == old.revision && record.overrideRevision > old.overrideRevision)) byId[record.id] = record
    }
    val sources = overview.metricsBySource["body_fat_percent"].orEmpty()
    (PERSONAL_BODY_KINDS.mapNotNull { overview.metrics[it] } + sources).forEach { metric -> metric.records.forEach(::merge) }
    history?.records?.forEach(::merge)
    fun beforeDay(at: String) = instant(at)?.toLocalDateTime(TimeZone.of(timezone))?.date?.toString()?.let { it <= day } == true
    val analyses = bodyAnalyses(byId.values.filter { beforeDay(it.startAt) }, history?.analyses.orEmpty().filter { beforeDay(it.measuredAt) })
        .filter { beforeDay(it.measuredAt) }
    fun deviceRecord(metric: assistant.mobile.core.personal.PersonalMetric?) = metric?.takeUnless { it.status in setOf("conflict", "no_data") }
        ?.records?.filter { !it.excluded && it.method != "derived" && it.provider != "caliper" && numericValue(it) != null }?.singleOrNull()
    val anchor = listOf("skeletal_muscle_mass", "body_fat_percent").firstNotNullOfOrNull { deviceRecord(overview.metrics[it]) }
        ?: sources.mapNotNull(::deviceRecord).singleOrNull()
        ?: PERSONAL_BODY_KINDS.firstNotNullOfOrNull { deviceRecord(overview.metrics[it]) }
    return BodyDashboard(analyses, anchor?.let { record -> analyses.find { analysis -> analysis.records.any { it.id == record.id } } })
}
