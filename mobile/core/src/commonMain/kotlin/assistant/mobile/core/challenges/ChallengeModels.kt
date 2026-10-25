package assistant.mobile.core.challenges

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.circle.CircleQuery
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.today.formatTodayAmount
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.floor
import kotlin.time.Instant

/** Public challenge rules and allowed aggregates; another person's goal never enters a row. */
data class Challenge(val id: String, val name: String, val startDate: String, val endDate: String,
    val startsAt: String, val tolerance: String)
enum class ChallengeState { Upcoming, Running, Finished }
enum class Participation { Pending, Active, NoGoal }
data class ChallengeTerms(val calorieTarget: String, val timezone: String)
data class ChallengeRank(val userId: String, val name: String, val days: Int, val streak: Int, val place: Int)
data class ChallengeBoard(val challenge: Challenge, val participation: Participation?, val terms: ChallengeTerms?, val ranking: List<ChallengeRank>)
data class ChallengeDraft(val name: String = "", val start: String = "", val end: String = "", val tolerance: String = "10")
data class ChallengesState(val namespace: AccountNamespace, val today: String, val timezone: String, val nowMillis: Long,
    val spaceId: String? = null, val challengeId: String? = null, val canCreate: Boolean = false,
    val challenges: CircleQuery<List<Challenge>> = CircleQuery(), val board: CircleQuery<ChallengeBoard> = CircleQuery(),
    val busy: Boolean = false, val gone: Boolean = false, val error: LocalizedString? = null, val serverError: String? = null,
    val pending: List<Submission> = emptyList()) {
    val joinOpen get() = board.data?.challenge?.let { joinOpen(it, nowMillis) } == true
    val writeBlocked get() = busy || pending.isNotEmpty()
}

fun challengeState(challenge: Challenge, today: String): ChallengeState = when {
    today < challenge.startDate -> ChallengeState.Upcoming
    today > challenge.endDate -> ChallengeState.Finished
    else -> ChallengeState.Running
}
fun joinOpen(challenge: Challenge, nowMillis: Long): Boolean = nowMillis < Instant.parse(challenge.startsAt).toEpochMilliseconds()
fun challengeDraft(today: String) = ChallengeDraft(start = shiftChallengeDay(today, 1), end = shiftChallengeDay(today, 14))
fun shiftChallengeDay(day: String, days: Int) = LocalDate.parse(day).plus(DatePeriod(days = days)).toString()
/** Exactly the web form validation; authoritative rules and scoring remain on the server. */
fun challengeDraftError(draft: ChallengeDraft, today: String): LocalizedString? {
    val start = runCatching { LocalDate.parse(draft.start).also { require(it.toString() == draft.start) } }.getOrNull()
    val end = runCatching { LocalDate.parse(draft.end).also { require(it.toString() == draft.end) } }.getOrNull()
    val tolerance = draft.tolerance.replace(',', '.').toDoubleOrNull()
    return when {
        draft.name.trim().isEmpty() -> GeneratedStrings.Circle.challengeNameRequired
        start == null || end == null || end < start -> GeneratedStrings.Circle.challengeDates
        draft.start <= today -> GeneratedStrings.Circle.challengeFuture
        tolerance == null || !tolerance.isFinite() || tolerance !in 0.0..100.0 -> GeneratedStrings.Circle.challengeTolerance
        else -> null
    }
}
/** JS Math.round for the visible band; this is not a local score or permission calculation. */
fun challengeBand(target: String, tolerance: String, locale: String = "ru"): String? {
    val goal = target.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val share = tolerance.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val low = floor(goal * (1 - share / 100) + .5).toLong()
    val high = floor(goal * (1 + share / 100) + .5).toLong()
    return "${formatTodayAmount(low.toString(), 0, locale)}–${formatTodayAmount(high.toString(), 0, locale)}"
}
