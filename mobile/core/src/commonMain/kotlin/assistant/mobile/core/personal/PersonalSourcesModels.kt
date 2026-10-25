package assistant.mobile.core.personal

import kotlinx.serialization.Serializable

data class PersonalSourceDevice(val kind: String = "custom", val profile: String? = null,
    val collectorId: String? = null, val collectorEnabled: Boolean? = null)
data class PersonalSourceEntry(
    val id: String, val name: String, val kinds: List<String>, val status: String, val revision: Long,
    val metricKeys: List<String> = emptyList(), val device: PersonalSourceDevice = PersonalSourceDevice(),
    val lastReceivedAt: String? = null, val lastMeasuredAt: String? = null, val createdAt: String? = null,
    val error: String? = null, val connection: PersonalDdxConnection? = null,
) { val active: Boolean get() = status != "disabled" && device.collectorEnabled != false }

@Serializable
data class PersonalSourcePolicy(val id: String?, val metric: String, val sourceIds: List<String?>,
    val effectiveFrom: String, val revision: Long)
data class PersonalSourceIdentity(val physicalId: String?, val confirmed: Boolean, val revision: Long)
data class PersonalSourceReading(val id: String, val metric: String, val startAt: String, val endAt: String?,
    val value: String?, val unit: String, val sourceId: String?, val sourceName: String?, val revision: Long?,
    val measurementKind: String?, val sensorLocation: String?, val definitionId: String?,
    val temperatureBasis: String?, val context: String?, val reportId: String?)
data class PersonalSourceComparison(val sourceIds: List<String>, val count: Int, val totals: List<Double>,
    val meanDifference: Double, val meanAbsoluteDifference: Double, val outsideReference: Int = 0)
data class PersonalSourceDay(val resolved: PersonalResolvedPage, val series: List<PersonalSourceReading>,
    val policies: List<PersonalSourcePolicy>, val identities: Map<String, PersonalSourceIdentity>,
    val comparisons: List<PersonalSourceComparison> = emptyList(), val catalog: List<PersonalCatalogMetric> = emptyList()) {
    val daily: List<PersonalResolvedDay> get() = resolved.daily
}
data class PersonalSourceRow(val key: String, val metrics: List<String>, val group: String)

val PERSONAL_TIMED_SOURCE_ROWS = setOf("steps", "active_minutes", "heart_rate", "spo2", "stress", "sleep", "hrv", "temperature")

/** A pair's daily differences are weighted by matched observations, just as the server reports. */
fun combinePersonalSourceComparisons(items: List<PersonalSourceComparison>): List<PersonalSourceComparison> {
    val combined = linkedMapOf<List<String>, PersonalSourceComparison>()
    for (item in items) {
        val old = combined[item.sourceIds]
        if (old == null) combined[item.sourceIds] = item
        else {
            val count = old.count + item.count
            combined[item.sourceIds] = old.copy(count = count,
                totals = listOf(old.totals[0] + item.totals[0], old.totals[1] + item.totals[1]),
                meanDifference = if (count == 0) 0.0 else (old.meanDifference * old.count + item.meanDifference * item.count) / count,
                meanAbsoluteDifference = if (count == 0) 0.0 else (old.meanAbsoluteDifference * old.count + item.meanAbsoluteDifference * item.count) / count,
                outsideReference = old.outsideReference + item.outsideReference)
        }
    }
    return combined.values.filter { it.count > 0 }
}

val PERSONAL_SOURCE_ROWS = listOf(
    PersonalSourceRow("steps", listOf("steps"), "activity"),
    PersonalSourceRow("active_minutes", listOf("active_minutes"), "activity"),
    PersonalSourceRow("active_energy", listOf("active_energy"), "activity"),
    PersonalSourceRow("distance", listOf("distance"), "activity"),
    PersonalSourceRow("workouts", listOf("workouts", "running_workouts", "running_steps"), "activity"),
    PersonalSourceRow("sleep", listOf("sleep"), "sleep"),
    PersonalSourceRow("heart_rate", listOf("heart_rate"), "vitals"),
    PersonalSourceRow("hrv", listOf("hrv"), "vitals"), PersonalSourceRow("spo2", listOf("spo2"), "vitals"),
    PersonalSourceRow("temperature", listOf("temperature"), "vitals"),
    PersonalSourceRow("stress", listOf("stress"), "vitals"),
    PersonalSourceRow("weight", listOf("weight"), "body"),
    PersonalSourceRow("body", listOf("body_fat_percent", "body_fat_mass", "skeletal_muscle_mass",
        "fat_free_mass", "body_water", "body_protein", "body_minerals", "bmi", "basal_metabolic_rate", "visceral_fat_level"), "body"),
)

@Serializable
data class PersonalSourcePolicyDraft(val rowKey: String, val sourceId: String?, val effectiveFrom: String)

fun personalPolicyOn(policies: List<PersonalSourcePolicy>, metric: String, day: String): PersonalSourcePolicy? =
    policies.filter { it.metric == metric && it.effectiveFrom <= day }
        .maxWithOrNull(compareBy<PersonalSourcePolicy> { it.effectiveFrom }.thenBy { it.revision })

fun personalSourcePin(row: PersonalSourceRow, policies: List<PersonalSourcePolicy>, day: String): String? =
    row.metrics.firstNotNullOfOrNull { personalPolicyOn(policies, it, day)?.sourceIds?.firstOrNull() }

fun personalSourceItems(row: PersonalSourceRow, data: PersonalSourceDay?, day: String): List<PersonalResolvedDay> =
    row.metrics.mapNotNull { metric -> data?.daily?.find { it.date == day && it.metric.kind == metric } }
        .filter { it.metric.value != null || (it.metric.kind == "sleep" && !it.metric.resolution?.sessions.isNullOrEmpty()) }

fun personalSourceRole(sourceId: String, row: PersonalSourceRow, data: PersonalSourceDay?, day: String): String? {
    val contributions = row.metrics.firstNotNullOfOrNull { metric ->
        data?.daily?.find { it.date == day && it.metric.kind == metric }?.metric?.resolution?.contributions?.takeIf { it.isNotEmpty() }
    }.orEmpty().sortedWith(compareByDescending<PersonalContribution> { it.value?.toDoubleOrNull() ?: 0.0 }.thenByDescending { it.count })
    val index = contributions.indexOfFirst { it.sourceId == sourceId }
    return when { index < 0 -> null; index == 0 -> "primary"; else -> "backup" }
}
