package assistant.mobile.core.places

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
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

class PlacesController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val reader: PlacesReader,
) {
    private val mutableState = MutableStateFlow<PlacesState?>(null)
    val state: StateFlow<PlacesState?> = mutableState.asStateFlow()
    private val visibility = MutableStateFlow(0L)
    private var active = false
    private var selectedDate: String? = null
    private var boundLease: SessionLease? = null
    private val reads = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, visibility) { lease, _ -> lease }.collectLatest { lease ->
            bind(lease)
            if (lease != null && active) refresh()
        }
    }

    fun activate(date: String? = null) {
        require(date == null || validPlacesDate(date))
        active = true
        bind(sessions.lease())
        setDate(date)
        visibility.update { it + 1 }
    }
    fun selectDate(date: String?) {
        require(date == null || validPlacesDate(date))
        setDate(date)
        visibility.update { it + 1 }
    }
    fun deactivate() { active = false }
    fun close() { owner.cancel(); active = false; boundLease = null; mutableState.value = null }

    suspend fun refresh() = reads.withLock {
        val lease = sessions.lease() ?: return@withLock
        bind(lease)
        val date = selectedDate
        if (date == null) {
            change(lease, date) { copy(days = days.copy(loading = true, error = null)) }
            try { val data = reader.days(lease); change(lease, date) { copy(days = PlacesQuery(data)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { change(lease, date) { copy(days = days.copy(loading = false, error = nativeError(failure.outcome()))) } }
        } else {
            change(lease, date) { copy(day = day.copy(loading = true, error = null)) }
            try { val data = reader.day(date, lease); change(lease, date) { copy(day = PlacesQuery(data)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { change(lease, date) { copy(day = day.copy(loading = false, error = nativeError(failure.outcome()))) } }
        }
    }

    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val lease = sessions.lease() ?: return null
        val date = selectedDate ?: return null
        return try { reader.tile(z, x, y, lease).takeIf { owned(lease, date) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    private fun setDate(date: String?) {
        if (selectedDate == date) return
        selectedDate = date
        mutableState.update { it?.copy(selectedDate = date, day = PlacesQuery()) }
    }
    private fun bind(lease: SessionLease?) {
        if (boundLease === lease) return
        boundLease = lease
        selectedDate = null
        mutableState.value = lease?.let { PlacesState(it.namespace) }
    }
    private fun owned(lease: SessionLease, date: String?) = boundLease === lease && sessions.isCurrent(lease) && selectedDate == date
    private inline fun change(lease: SessionLease, date: String?, update: PlacesState.() -> PlacesState) {
        if (owned(lease, date)) mutableState.update { it?.update() }
    }
}
private fun Exception.outcome(): HttpOutcome = (this as? PlacesRequestException)?.outcome ?: HttpOutcome.NetworkFailure
