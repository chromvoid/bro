package assistant.mobile.core.activity

import assistant.mobile.core.personal.PersonalMetric
import assistant.mobile.core.personal.PersonalResolvedDay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.time.Instant

/** A resolver counter is a separate observation; no client code sums it into ordinary steps. */
fun activityMetricValue(metric: PersonalMetric?): String? = metric?.takeIf { it.status == "available" && it.resolution?.status != "conflict" }?.value?.takeIf { it.isNotBlank() && it.toDoubleOrNull()?.isFinite() == true }
fun movementCoverage(metric: PersonalMetric?): String = when {
    metric?.complete == false || metric?.resolution?.coverage?.complete == false || metric?.resolution?.status == "partial" -> "partial"
    metric?.complete == true || metric?.resolution?.coverage?.complete == true -> "complete"
    else -> "coverageUnknown"
}
fun activityRange(anchor: String, period: Int, kind: String? = null): ActivityRange {
    require(period in setOf(7, 30, 90) && (kind == null || kind in ACTIVITY_KINDS))
    return ActivityRange(LocalDate.parse(anchor).minus(DatePeriod(days = period - 1)).toString(), anchor, kind)
}
fun sessionWindow(session: ActivitySession): Pair<Long, Long>? {
    val start = session.startedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return null
    val minutes = session.durationMinutes?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
    return start to (start + (minutes * 60_000).toLong())
}
sealed interface ActivityJournalEntry {
    val id: String
    val date: String
    val startedAt: String?
    data class Manual(val session: ActivitySession) : ActivityJournalEntry {
        override val id: String get() = "manual:${session.id}"
        override val date: String get() = session.date
        override val startedAt: String? get() = session.startedAt
    }
    data class Device(val workout: DeviceWorkout) : ActivityJournalEntry {
        override val id: String get() = "device:${workout.id}"
        override val date: String get() = workout.date
        override val startedAt: String get() = workout.startAt
    }
}
fun journalGroups(sessions: List<ActivitySession>, reports: List<DeviceWorkout>, kind: String?): List<Pair<String, List<ActivityJournalEntry>>> {
    val windows = sessions.mapNotNull(::sessionWindow)
    val entries: List<ActivityJournalEntry> = sessions.map { ActivityJournalEntry.Manual(it) } + reports.filter { report ->
        (kind == null || kind == "running" && report.sportType == "running") && windows.none { window ->
            Instant.parse(report.startAt).toEpochMilliseconds() < window.second && window.first < Instant.parse(report.endAt).toEpochMilliseconds()
        }
    }.map { ActivityJournalEntry.Device(it) }
    return entries.groupBy { it.date }.entries.sortedByDescending { it.key }.map { (date, items) -> date to items.sortedWith(compareByDescending<ActivityJournalEntry> { it.startedAt ?: "" }.thenBy { it.id }) }
}
fun coherentWorkoutHeartRatePages(pages: List<WorkoutHeartRatePage>): Boolean {
    val first = pages.firstOrNull() ?: return true
    var previous = -1
    for (page in pages) {
        if (page.workoutId != first.workoutId || page.sourceId != first.sourceId || page.fingerprint != first.fingerprint || page.reportId != first.reportId || page.reportRevision != first.reportRevision || page.workout != first.workout) return false
        for (chunk in page.chunks) {
            if (chunk.workoutId != first.workoutId || chunk.sequence <= previous) return false
            previous = chunk.sequence
        }
    }
    return true
}
/** Terminal report boundaries retain recovery and exclude all readings within preserved pauses. */
fun workoutHeartRateSamples(pages: List<WorkoutHeartRatePage>): List<WorkoutPulse> {
    if (!coherentWorkoutHeartRatePages(pages)) return emptyList()
    return pages.flatMap { page -> page.chunks.flatMap { chunk -> chunk.samples.mapNotNull { sample ->
        if (sample.quality == "invalid") return@mapNotNull null
        val timestamp = Instant.parse(chunk.startAt).toEpochMilliseconds() + sample.offsetMs
        if (page.workout.pauses.any { timestamp >= Instant.parse(it.startAt).toEpochMilliseconds() && timestamp < Instant.parse(it.endAt).toEpochMilliseconds() }) return@mapNotNull null
        val phase = if (timestamp >= Instant.parse(page.workout.endAt).toEpochMilliseconds()) "recovery" else if (page.workout.kind == "workouts") "workout" else "running"
        WorkoutPulse(Instant.fromEpochMilliseconds(timestamp).toString(), sample.bpm, sample.quality, phase)
    } } }
}
fun workoutPoints(samples: List<WorkoutPulse>, phase: String): List<ActivityChartPoint> = samples.filter { it.quality == "valid" && it.phase == phase }.mapNotNull { pulse ->
    runCatching { ActivityChartPoint(Instant.parse(pulse.observedAt).toEpochMilliseconds().toDouble(), pulse.bpm.toDouble()) }.getOrNull()
}
fun coherentWorkoutTrackPages(pages: List<WorkoutTrackPage>): Boolean {
    val first = pages.firstOrNull() ?: return true
    return pages.all { it.workoutId == first.workoutId && it.sourceId == first.sourceId && it.fingerprint == first.fingerprint && it.summary?.fingerprint == first.summary?.fingerprint && it.workout == first.workout }
}
fun coherentTrackVersionPages(pages: List<WorkoutTrackVersionsPage>): Boolean {
    val first = pages.firstOrNull() ?: return true
    if (pages.any { it.workoutId != first.workoutId || it.sourceId != first.sourceId || it.fingerprint != first.fingerprint || it.latestRevision != first.latestRevision }) return false
    val versions = pages.flatMap { it.items }
    val latest = first.latestRevision ?: return versions.isEmpty() && pages.all { it.nextCursor == null }
    return versions.firstOrNull()?.revision == latest && versions.withIndex().all { (index, value) -> value.isLatest == (value.revision == latest) && (index == 0 || value.revision < versions[index - 1].revision) }
}
fun dailyMetricPoints(days: List<PersonalResolvedDay>, kind: String): List<ActivityChartPoint> = days.filter { it.metric.kind == kind }.mapNotNull { day -> activityMetricValue(day.metric)?.toDoubleOrNull()?.let { ActivityChartPoint(atDay(day.date), it) } }.sortedBy { it.x }
fun summaryPoints(summary: ActivitySummary?, field: String, kind: String? = null): List<ActivityChartPoint> = summary?.daily?.mapNotNull { day ->
    if (field == "duration_minutes" && kind == null && day.sessions > 0 && day.missingDurationSessions >= day.sessions) return@mapNotNull null
    val value = if (kind != null) day.distanceKmByKind[kind]?.toDoubleOrNull() else when (field) { "sessions" -> day.sessions.toDouble(); "duration_minutes" -> day.durationMinutes?.toDoubleOrNull(); "working_sets" -> day.workingSets.toDouble(); else -> null }
    value?.takeIf { it.isFinite() }?.let { ActivityChartPoint(atDay(day.date), it) }
} ?: emptyList()
/** Assistance improves downward; series differing in load basis/equipment remain separate. */
fun progressPoints(series: ExerciseProgressSeries, field: String): List<ActivityChartPoint> = series.points.groupBy { it.date }.entries.sortedBy { it.key }.mapNotNull { (date, points) ->
    val values = points.mapNotNull { when (field) { "load_kg" -> it.loadKg?.toDoubleOrNull(); "repetitions" -> it.repetitions?.toDouble(); else -> null } }.filter { it.isFinite() }
    if (values.isEmpty()) null else ActivityChartPoint(atDay(date), if (field == "load_kg" && series.snapshot.loadBasis == "assistance") values.min() else values.max())
}
data class StrengthDay(val date: String, val points: List<ExerciseProgressPoint>)
fun strengthDays(series: ExerciseProgressSeries): Pair<StrengthDay?, StrengthDay?> {
    val days = series.points.groupBy { it.date }.entries.sortedByDescending { it.key }.map { StrengthDay(it.key, it.value) }
    return days.getOrNull(0) to days.getOrNull(1)
}
private fun atDay(date: String): Double = Instant.parse("${date}T00:00:00Z").toEpochMilliseconds().toDouble()
