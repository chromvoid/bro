package assistant.mobile.core.food

import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.files.ExportFile
import assistant.mobile.core.files.FileExportDestination
import assistant.mobile.core.files.FileExportPort
import assistant.mobile.core.nativeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Range reads and explicit file exports share the authenticated account transport. */
class FoodStatsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val exports: FileExportPort,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<FoodStatsState?>(null)
    val state: StateFlow<FoodStatsState?> = mutableState.asStateFlow()
    // Activation is an event even if a fast stop/start is conflated into one StateFlow value.
    // The generation guarantees that returning to a stale screen always triggers its read.
    private data class Visibility(val active: Boolean = false, val generation: Long = 0)
    private val visible = MutableStateFlow(Visibility())
    private var boundLease: SessionLease? = null
    private val readLock = Mutex()
    private val exportLock = Mutex()

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, visibility -> Triple(lease, identity, visibility) }
                .collectLatest { (lease, identity, visibility) ->
                    bind(lease, identity)
                    if (visibility.active && lease != null && identity != null) refresh()
                }
        }
    }

    fun activate(day: String? = null) {
        bind(sessions.lease(), sessions.identity.value)
        if (day != null) {
            kotlinx.datetime.LocalDate.parse(day)
            mutableState.update { it?.copy(period = foodStatsPeriod(it.period.kind, minOf(day, it.today)), anchorDay = minOf(day, it.today), range = FoodQuery()) }
        }
        visible.update { it.copy(active = true, generation = it.generation + 1) }
    }

    fun deactivate() { visible.update { it.copy(active = false) } }

    suspend fun selectKind(kind: FoodStatsPeriodKind) {
        mutableState.update { it?.copy(period = foodStatsPeriod(kind, it.anchorDay), range = FoodQuery(), error = null) }
        refresh()
    }

    suspend fun shiftPeriod(step: Int) {
        require(step == -1 || step == 1)
        mutableState.update { if (it == null || (step > 0 && !it.canGoLater)) it else
            shiftFoodStatsPeriod(it.period, step).let { next -> it.copy(period = next, anchorDay = next.start, range = FoodQuery(), error = null) } }
        refresh()
    }

    suspend fun refresh() {
        if (!visible.value.active) return
        val lease = sessions.lease() ?: return
        bind(lease, sessions.identity.value)
        readLock.withLock {
            if (!owned(lease) || !visible.value.active || state.value?.canRead != true) return@withLock
            val period = state.value?.period ?: return@withLock
            change(lease, period) { copy(range = range.copy(loading = true, error = null)) }
            val outcome = http.request("GET", "/v1/nutrition/range?start=${period.start}&end=${period.end}", lease = lease)
            if (outcome is HttpOutcome.Success) {
                val data = runCatching { decodeFoodStatsRange(outcome.body, period) }.getOrNull()
                change(lease, period) { copy(range = if (data != null) FoodQuery(data) else range.copy(loading = false,
                    error = GeneratedStrings.Errors.server, stale = range.data != null)) }
            } else change(lease, period) { copy(range = range.copy(loading = false, error = nativeError(outcome), stale = range.data != null)) }
        }
    }

    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Nutrition !in invalidation.values && EventDomain.Catalog !in invalidation.values) return
        mutableState.update { it?.copy(range = it.range.copy(stale = it.range.data != null)) }
        refresh()
    }

    /** Export is a user action; selecting the format or viewing statistics never creates a file. */
    suspend fun export(format: FoodStatsExportFormat, destination: FileExportDestination) = exportLock.withLock {
        val lease = sessions.lease() ?: return@withLock
        if (!owned(lease) || state.value?.canRead != true) return@withLock
        change(lease) { copy(exporting = true, error = null) }
        try {
            val outcome = http.request("GET", "/v1/nutrition/export?format=${format.wire}", lease = lease)
            if (!owned(lease)) return@withLock
            if (outcome !is HttpOutcome.Success) {
                change(lease) { copy(error = nativeError(outcome)) }
                return@withLock
            }
            exports.export(ExportFile("nutrition.${format.wire}", format.mimeType, outcome.body), destination)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(error = GeneratedStrings.Errors.network) } }
        finally { change(lease) { copy(exporting = false) } }
    }

    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || lease.namespace.userId != identity.id) {
            boundLease = null; mutableState.value = null; return
        }
        val changed = boundLease !== lease
        boundLease = lease
        val today = foodToday(identity.timezone, clockMs())
        mutableState.update { old ->
            val before = if (changed) null else old
            val canRead = "nutrition.read" in identity.scopes
            val base = before ?: FoodStatsState(lease.namespace, today, identity.locale, canRead,
                foodStatsPeriod(FoodStatsPeriodKind.Week, today), anchorDay = today)
            base.copy(today = today, locale = identity.locale, canRead = canRead,
                range = if (canRead) base.range else FoodQuery(), error = if (canRead) base.error else null)
        }
    }

    private fun owned(lease: SessionLease): Boolean = sessions.isCurrent(lease) && boundLease === lease
    private fun change(lease: SessionLease, period: FoodStatsPeriod? = null, update: FoodStatsState.() -> FoodStatsState) {
        if (!owned(lease) || "nutrition.read" !in sessions.identity.value?.scopes.orEmpty()) return
        mutableState.update { if (it == null || !it.canRead || (period != null && it.period != period)) it else it.update() }
    }
}
