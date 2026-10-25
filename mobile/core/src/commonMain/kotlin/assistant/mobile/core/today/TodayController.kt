package assistant.mobile.core.today

import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.nativeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Read-only day dashboard. The app's existing account stream calls [invalidate]; this owner never
 * opens a stream or submits a write. Queries are independent, account-bound and scope-projected.
 */
class TodayController(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val timerClockMs: () -> Long = monotonicMillisClock(),
) {
    private val mutableState = MutableStateFlow<TodayState?>(null)
    val state: StateFlow<TodayState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private var boundLease: SessionLease? = null
    private val locks = TodayCard.entries.associateWith { Mutex() }

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active ->
                Triple(lease, identity, active)
            }.collectLatest { (lease, identity, active) ->
                bind(lease, identity)
                if (lease == null || identity == null || !active) return@collectLatest
                coroutineScope {
                    // The clock must keep moving even when any independent GET is slow. Remember
                    // the observed day separately: a late response may already have rebound it.
                    var observedDay = state.value?.day
                    launch {
                        var expiredReadAt: Long? = null
                        while (sessions.isCurrent(lease)) {
                            delay(500)
                            bind(lease, sessions.identity.value)
                            val snapshot = state.value
                            val timerNow = snapshot?.timerNowMillis ?: timerClockMs()
                            val expired = snapshot?.timers?.data?.let { timers ->
                                timers.items.any { it.remaining(timers.readAtMillis, timerNow) == 0L }
                            } == true
                            if (!expired) expiredReadAt = null
                            else if (expiredReadAt == null) expiredReadAt = timerNow + 2000
                            else if (timerNow >= expiredReadAt && snapshot?.timers?.loading != true) {
                                // Zero is only a display value. The server decides whether it fired.
                                expiredReadAt = timerNow + 2000
                                launch { refreshCard(TodayCard.Timers) }
                            }
                            val day = state.value?.day
                            if (day != observedDay) {
                                observedDay = day
                                launch { refresh(datedCards) }
                            }
                        }
                    }
                    refresh()
                }
            }
        }
    }

    fun activate() { visible.value = true }
    fun deactivate() { visible.value = false }

    suspend fun refresh() = refresh(TodayCard.entries.toSet())

    /** Invalidations only reread the domains named by the shared SSE owner. */
    suspend fun invalidate(invalidation: EventInvalidation) {
        val cards = when (invalidation) {
            EventInvalidation.All -> TodayCard.entries.toSet()
            is EventInvalidation.Domains -> invalidation.values.flatMap { domain ->
                when (domain) {
                    EventDomain.Nutrition -> listOf(TodayCard.Food)
                    EventDomain.Tasks -> listOf(TodayCard.Tasks)
                    EventDomain.Timers -> listOf(TodayCard.Timers)
                    EventDomain.Notifications -> listOf(TodayCard.Notifications)
                    EventDomain.Personal -> listOf(TodayCard.Personal, TodayCard.Steps, TodayCard.WeightHistory)
                    EventDomain.Activity -> listOf(TodayCard.Personal, TodayCard.Steps)
                    else -> emptyList()
                }
            }.toSet()
        }
        for (card in cards) markStale(card)
        if (visible.value) refresh(cards)
    }

    suspend fun refreshCard(card: TodayCard) = locks.getValue(card).withLock {
        val lease = sessions.lease() ?: return@withLock
        bind(lease, sessions.identity.value)
        val current = state.value ?: return@withLock
        val day = current.day
        when (card) {
            TodayCard.Food -> read(lease, day, "/v1/nutrition/day?date=$day",
                { it.food }, { copy(food = it) }) { bytes -> decodeTodayFood(bytes).also { require(it.date == day) } }
            TodayCard.Tasks -> read(lease, day, "/v1/tasks/items?view=today&sort=created&limit=5",
                { it.tasks }, { copy(tasks = it) }) { bytes -> decodeTodayTasks(bytes).also { require(it.today == day && it.timezone == current.timezone) } }
            TodayCard.Timers -> read(lease, null, "/v1/timers/running",
                { it.timers }, { copy(timers = it) }) { bytes -> decodeTodayTimers(bytes, timerClockMs()) }
            TodayCard.Notifications -> read(lease, null, "/v1/notifications?limit=50",
                { it.notifications }, { copy(notifications = it) }, ::decodeTodayNotifications)
            TodayCard.Personal -> read(lease, day, "/v1/personal/overview?date=$day",
                { it.personal }, { copy(personal = it) }) { bytes -> decodeTodayPersonal(bytes).also { require(it.date == day) } }
            TodayCard.Steps -> read(lease, day, "/v1/personal/steps?date=$day",
                { it.steps }, { copy(steps = it) }) { bytes -> decodeTodayStepsOverride(bytes).also { require(it.date == day) } }
            TodayCard.WeightHistory -> {
                val from = LocalDate.parse(day).minus(DatePeriod(days = 30))
                read(lease, day, "/v1/personal/history?from=$from&to=$day&kind=weight",
                    { it.weightHistory }, { copy(weightHistory = it) }, ::decodeTodayWeightHistory)
            }
        }
    }

    private suspend fun refresh(cards: Set<TodayCard>) = coroutineScope {
        cards.map { card -> async { refreshCard(card) } }.awaitAll()
        Unit
    }

    private suspend fun <T> read(
        lease: SessionLease,
        day: String?,
        path: String,
        query: (TodayState) -> TodayQuery<T>?,
        replace: TodayState.(TodayQuery<T>) -> TodayState,
        decode: (ByteArray) -> T,
    ) {
        if (state.value?.let(query) == null) return
        val timezone = if (day != null) state.value?.timezone else null
        change(lease, day, timezone) { query(this)?.let { replace(it.loading()) } ?: this }
        try {
            val response = http.request("GET", path, lease = lease)
            if (response is HttpOutcome.Success) {
                val data = try { decode(response.body) } catch (_: Exception) {
                    change(lease, day, timezone) { query(this)?.let { replace(it.failed(GeneratedStrings.Today.cardError)) } ?: this }
                    return
                }
                change(lease, day, timezone) { query(this)?.let { replace(it.loaded(data)) } ?: this }
            } else change(lease, day, timezone) {
                query(this)?.let { replace(it.failed(nativeError(response))) } ?: this
            }
        } catch (cancelled: CancellationException) {
            change(lease, day, timezone) { query(this)?.let { replace(it.copy(loading = false)) } ?: this }
            throw cancelled
        }
    }

    private fun change(lease: SessionLease, day: String?, timezone: String?, update: TodayState.() -> TodayState) {
        if (!sessions.isCurrent(lease) || boundLease !== lease) return
        bind(lease, sessions.identity.value)
        mutableState.update { before ->
            if (before == null || (day != null && (before.day != day || before.timezone != timezone))) before
            else project(before, sessions.identity.value ?: return@update null).update()
        }
    }

    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            boundLease = null
            mutableState.value = null
            return
        }
        val now = clockMs()
        val day = todayIn(identity.timezone, now)
        val before = state.value?.takeIf { boundLease === lease }
        boundLease = lease
        val next = before ?: TodayState(lease.namespace, day, identity.timezone, now, identity.scopes, identity.locale)
        val shifted = next.day != day || next.timezone != identity.timezone
        mutableState.value = project(next.copy(day = day, timezone = identity.timezone, nowMillis = now,
            locale = identity.locale, timerNowMillis = timerClockMs(),
            food = if (shifted) null else next.food,
            tasks = if (shifted) null else next.tasks,
            personal = if (shifted) null else next.personal,
            steps = if (shifted) null else next.steps,
            weightHistory = if (shifted) null else next.weightHistory), identity)
    }

    private fun project(state: TodayState, identity: NativeIdentity): TodayState {
        val scopes = identity.scopes
        return state.copy(scopes = scopes,
            food = if ("nutrition.read" in scopes) state.food ?: TodayQuery() else null,
            tasks = if ("tasks.read" in scopes) state.tasks ?: TodayQuery() else null,
            timers = if ("timers.read" in scopes) state.timers ?: TodayQuery() else null,
            notifications = if ("session.control" in scopes) state.notifications ?: TodayQuery() else null,
            personal = if ("personal.read" in scopes) state.personal ?: TodayQuery() else null,
            steps = if ("personal.read" in scopes) state.steps ?: TodayQuery() else null,
            weightHistory = if ("personal.read" in scopes) state.weightHistory ?: TodayQuery() else null)
    }

    private fun markStale(card: TodayCard) {
        mutableState.update { current -> current?.run {
            when (card) {
                TodayCard.Food -> copy(food = food?.copy(stale = food.data != null))
                TodayCard.Tasks -> copy(tasks = tasks?.copy(stale = tasks.data != null))
                TodayCard.Timers -> copy(timers = timers?.copy(stale = timers.data != null))
                TodayCard.Notifications -> copy(notifications = notifications?.copy(stale = notifications.data != null))
                TodayCard.Personal -> copy(personal = personal?.copy(stale = personal.data != null))
                TodayCard.Steps -> copy(steps = steps?.copy(stale = steps.data != null))
                TodayCard.WeightHistory -> copy(weightHistory = weightHistory?.copy(stale = weightHistory.data != null))
            }
        } }
    }

    private companion object {
        val datedCards = setOf(TodayCard.Food, TodayCard.Tasks, TodayCard.Personal, TodayCard.Steps, TodayCard.WeightHistory)
    }
}

fun todayIn(timezone: String, nowMillis: Long): String =
    Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.of(timezone)).date.toString()

private fun monotonicMillisClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
