package assistant.mobile.core.personal

import assistant.mobile.core.food.plainFoodDecimal
import assistant.mobile.core.today.formatTodayAmount
import kotlinx.datetime.LocalDate
import kotlin.math.floor

private val personalMassKinds = setOf("weight", "body_fat_mass", "skeletal_muscle_mass", "fat_free_mass", "body_protein", "body_minerals")

fun personalUnitOf(kind: String): String = when (kind) {
    in personalMassKinds -> "кг"
    "sleep", "active_minutes" -> "мин"
    "steps", "running_steps" -> "шагов"
    "active_energy", "nutrition" -> "ккал"
    "distance" -> "м"
    "body_fat_percent", "spo2" -> "%"
    "body_water" -> "л"
    "bmi" -> "кг/м²"
    "basal_metabolic_rate" -> "ккал/день"
    "visceral_fat_level" -> "ур."
    else -> ""
}

private fun presentNumber(value: String?): Double? = value?.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
private fun amount(value: String, fraction: Int, locale: String): String = formatTodayAmount(plainFoodDecimal(value), fraction, locale)
private fun amount(value: Double, fraction: Int, locale: String): String = amount(value.toString(), fraction, locale)

/** Minutes follow the web's Math.round; an unknown duration remains unknown at its call site. */
fun personalDuration(minutes: Double, locale: String = "ru"): String {
    val whole = floor(minutes + 0.5).toLong()
    val hours = whole / 60
    val h = if (locale.startsWith("ru")) "ч" else "h"
    val m = if (locale.startsWith("ru")) "мин" else "min"
    return if (hours > 0) "$hours $h ${whole % 60} $m" else "$whole $m"
}

fun personalAmountText(kind: String, value: String?, units: PersonalDisplayUnits = PersonalDisplayUnits.Metric, locale: String = "ru"): String? {
    val number = presentNumber(value) ?: return null
    if (kind == "sleep") return personalDuration(number, locale)
    if (kind == "distance") return personalDistanceText(value, units, locale)
    if (kind in personalMassKinds) return personalMassText(value, units, locale)
    val fraction = if (kind in PERSONAL_BODY_KINDS && kind != "basal_metabolic_rate") 2 else 0
    return "${amount(requireNotNull(value), fraction, locale)} ${personalUnitOf(kind)}".trimEnd()
}

fun personalMassText(kilograms: String?, units: PersonalDisplayUnits, locale: String = "ru"): String? {
    val number = presentNumber(kilograms) ?: return null
    val text = if (units == PersonalDisplayUnits.Imperial) amount(number / 0.45359237, 2, locale) else amount(requireNotNull(kilograms), 2, locale)
    return "$text ${if (units == PersonalDisplayUnits.Imperial) "lb" else "кг"}"
}

fun personalHeightText(centimeters: String?, units: PersonalDisplayUnits, locale: String = "ru"): String? {
    val number = presentNumber(centimeters) ?: return null
    if (units == PersonalDisplayUnits.Metric) return "${amount(requireNotNull(centimeters), 1, locale)} см"
    val inches = floor(number / 2.54 * 10 + 0.5) / 10
    val feet = floor(inches / 12).toLong()
    return "$feet ft ${amount(inches - feet * 12, 1, locale)} in"
}

fun personalTemperatureText(celsius: String?, units: PersonalDisplayUnits, locale: String = "ru"): String? {
    val number = presentNumber(celsius) ?: return null
    return if (units == PersonalDisplayUnits.Imperial) "${amount(number * 9 / 5 + 32, 2, locale)} °F"
    else "${amount(requireNotNull(celsius), 2, locale)} °C"
}

fun personalDistanceText(meters: String?, units: PersonalDisplayUnits, locale: String = "ru"): String? {
    val number = presentNumber(meters) ?: return null
    return "${amount(number / if (units == PersonalDisplayUnits.Imperial) 1609.344 else 1000.0, 2, locale)} ${if (units == PersonalDisplayUnits.Imperial) "мили" else "км"}"
}

fun personalDistanceInput(meters: String?, units: PersonalDisplayUnits): String {
    val number = presentNumber(meters) ?: return ""
    return formatTodayAmount(plainFoodDecimal((number / if (units == PersonalDisplayUnits.Imperial) 1609.344 else 1000.0).toString()), 6, "en").replace(",", "")
}

/** Decimal multiplication in common Kotlin: no floating point enters an operation payload. */
fun personalDistanceTargetMeters(input: String, units: PersonalDisplayUnits): String? {
    val normalized = input.trim().replace(',', '.').takeIf { it.isNotEmpty() && it.length <= 50 } ?: return null
    val parts = normalized.split('.')
    if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
    val multiplier = if (units == PersonalDisplayUnits.Imperial) 1_609_344 else 1000
    val scale = parts.getOrElse(1) { "" }.length + if (units == PersonalDisplayUnits.Imperial) 3 else 0
    var carry = 0
    val reversed = StringBuilder()
    for (digit in parts.joinToString("").reversed()) {
        val product = (digit - '0') * multiplier + carry
        reversed.append(('0'.code + product % 10).toChar())
        carry = product / 10
    }
    while (carry > 0) { reversed.append(('0'.code + carry % 10).toChar()); carry /= 10 }
    val digits = reversed.reverse().toString().trimStart('0').ifEmpty { "0" }.padStart(scale + 1, '0')
    if (scale == 0) return digits
    return (digits.dropLast(scale) + "." + digits.takeLast(scale)).trimEnd('0').trimEnd('.')
}

fun personalSensorAmount(value: String?, unit: String, units: PersonalDisplayUnits = PersonalDisplayUnits.Metric, locale: String = "ru"): String? {
    presentNumber(value) ?: return null
    if (unit in setOf("celsius", "°C")) return personalTemperatureText(value, units, locale)
    val label = when (unit) { "bpm" -> "уд/мин"; "ms" -> "мс"; "index" -> "индекс"; else -> unit }
    return "${amount(requireNotNull(value), 2, locale)} $label".trimEnd()
}

fun personalAgeOn(birth: String?, day: String): Int? {
    val birthday = birth?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return null
    if (birthday > date) return null
    return date.year - birthday.year - if (date.monthNumber < birthday.monthNumber ||
        (date.monthNumber == birthday.monthNumber && date.dayOfMonth < birthday.dayOfMonth)) 1 else 0
}

fun personalRequiredFoldSites(gender: String?): List<String> = when (gender) {
    "male" -> listOf("chest", "abdomen", "thigh")
    "female" -> listOf("triceps", "suprailiac", "thigh")
    else -> emptyList()
}

fun personalOrigin(record: PersonalObservation): String = when {
    record.corrected -> "corrected"
    record.method == "derived" -> "estimate"
    record.method == "self_report" -> "manual"
    else -> "device"
}

fun personalProvenance(record: PersonalObservation, locale: String = "ru"): String {
    val russian = locale.startsWith("ru")
    val label = when (personalOrigin(record)) {
        "corrected" -> if (russian) "Исправлено пользователем" else "Your correction"
        "estimate" -> if (russian) "Оценка" else "Estimate"
        "manual" -> if (russian) "Вручную" else "Manual entry"
        else -> if (russian) "Данные устройства" else "Device data"
    }
    return listOfNotNull(record.sourceName?.takeIf { it.isNotBlank() }, label).joinToString(" · ")
}

/** Contributions carry the ranked source shares; selected facts are the legacy fallback. */
fun personalMetricSources(metric: PersonalMetric): List<String> {
    val resolution = metric.resolution
    val contributions = resolution?.contributions.orEmpty()
    if (contributions.isNotEmpty()) return contributions.sortedWith(compareByDescending<PersonalContribution> {
        it.value?.toDoubleOrNull() ?: 0.0
    }.thenByDescending { it.count }).map { it.sourceName ?: if (it.sourceId == null) "Вручную" else it.sourceId }.distinct()
    val selected = resolution?.selected.orEmpty().mapNotNull { it.sourceName ?: it.sourceId }
    if (selected.isNotEmpty()) return selected.distinct()
    return (listOfNotNull(metric.sourceName) + metric.records.filterNot { it.excluded }.mapNotNull { it.sourceName }).distinct()
}

fun personalSleepConflict(resolution: PersonalResolution?): String? {
    val reasons = resolution?.alternatives.orEmpty().map { it.reason }.toSet()
    return listOf("sleep_report_correction_conflict", "sleep_report_epoch_conflict", "sleep_report_conflict", "same_source_overlap").firstOrNull { it in reasons }
}

/** Point observations have no duration coverage; only reported intervals can establish it. */
fun personalCoverageSeconds(coverage: PersonalCoverage?): Double? {
    if (coverage == null || (coverage.pointCount != null && coverage.pointCount != 0 && coverage.intervalCount == 0)) return null
    return coverage.reportedSeconds ?: coverage.coveredSeconds
}

/** The value can be present in an unqualified producer reply without being a displayable score. */
fun personalScoreValue(score: PersonalDerivedScore): String? = score.value?.takeIf {
    score.status in setOf("available", "partial") && presentNumber(it) != null
}

fun personalSleepSessions(metric: PersonalMetric): List<PersonalSleepSession> {
    if (metric.kind != "sleep") return emptyList()
    if (metric.resolution != null) return metric.resolution.sessions
    if (metric.status != "available") return emptyList()
    return metric.records.filterNot { it.excluded }.map {
        PersonalSleepSession(it.id, it.startAt, it.endAt, it.value, it.sourceId, it.sourceName, it.stages)
    }
}
