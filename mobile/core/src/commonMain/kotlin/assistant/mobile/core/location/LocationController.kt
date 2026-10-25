package assistant.mobile.core.location

import assistant.mobile.core.GeneratedStrings
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

/** Explicit buttons own all permission requests and changes; opening settings is read-only. */
class LocationController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val api: LocationApi,
    private val position: PositionSource,
    private val background: BackgroundRecording,
) {
    private val mutableState = MutableStateFlow<LocationState?>(null)
    val state: StateFlow<LocationState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(0L)
    private var active = false
    private var boundLease: SessionLease? = null
    private val reads = Mutex()
    private val writes = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, visible) { lease, _ -> lease }.collectLatest { lease ->
            bind(lease)
            if (lease != null && active) refresh()
        }
    }

    fun activate() { active = true; bind(sessions.lease()); visible.update { it + 1 } }
    fun deactivate() { active = false }
    fun close() { owner.cancel(); active = false; boundLease = null; mutableState.value = null }
    fun clearError() { mutableState.update { it?.copy(error = null) } }

    suspend fun refresh() = reads.withLock {
        val lease = sessions.lease() ?: return@withLock
        bind(lease)
        change(lease) { copy(snapshot = snapshot.copy(loading = true, error = null)) }
        // Like the web's independent source/background queries, a platform failure cannot hide history.
        try { val status = position.status(); change(lease) { copy(position = status) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { change(lease) { copy(position = PositionStatus(PositionPermission.UNAVAILABLE)) } }
        try { val recording = background.status(lease.namespace); change(lease) { copy(background = recording) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
        if (!owned(lease)) return@withLock
        try {
            val value = api.snapshot(lease)
            change(lease) { copy(snapshot = LocationQuery(value)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            change(lease) { copy(snapshot = snapshot.copy(loading = false, error = nativeError(failure.outcome()))) }
        }
    }

    suspend fun requestPosition(): Boolean = write { lease ->
        val status = position.request()
        change(lease) { copy(position = status) }
        if (!owned(lease) || status.permission != PositionPermission.GRANTED) return@write false
        val fix = position.current() ?: return@write false
        if (!owned(lease)) return@write false
        api.sendFix(fix, lease)
        refresh()
        owned(lease)
    }

    suspend fun enableBackground(): Boolean = write { lease ->
        val recording = background.enable(lease.namespace)
        change(lease) { copy(background = recording, error = if (recording.enabled) null else GeneratedStrings.LocationSettings.backgroundDenied) }
        if (!owned(lease)) return@write false
        refresh()
        recording.enabled && owned(lease)
    }

    suspend fun disableBackground(): Boolean = write { lease ->
        val recording = background.disable()
        change(lease) { copy(background = recording) }
        if (!owned(lease)) return@write false
        refresh()
        !recording.enabled && owned(lease)
    }

    /** The view invokes this only after the same confirmation as the web. */
    suspend fun deleteHistory(): Boolean = write { lease ->
        api.deleteHistory(lease)
        if (!owned(lease)) return@write false
        refresh()
        owned(lease)
    }

    private suspend fun write(block: suspend (SessionLease) -> Boolean): Boolean {
        if (!writes.tryLock()) return false
        try {
            val lease = sessions.lease() ?: return false
            bind(lease)
            change(lease) { copy(busy = true, error = null) }
            return try { block(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                change(lease) { copy(error = nativeError(failure.outcome())) }
                if (owned(lease)) {
                    try { val status = background.status(lease.namespace); change(lease) { copy(background = status) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
                false
            } finally { change(lease) { copy(busy = false) } }
        } finally { writes.unlock() }
    }
    private fun bind(lease: SessionLease?) {
        if (boundLease === lease) return
        boundLease = lease
        mutableState.value = lease?.let { LocationState(it.namespace) }
    }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private inline fun change(lease: SessionLease, update: LocationState.() -> LocationState) {
        if (owned(lease)) mutableState.update { it?.update() }
    }
}

private fun Exception.outcome(): HttpOutcome = (this as? LocationRequestException)?.outcome ?: HttpOutcome.NetworkFailure
