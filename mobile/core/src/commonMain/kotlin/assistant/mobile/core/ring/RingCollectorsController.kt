package assistant.mobile.core.ring

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.devices.devicesServerError
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLPathPart
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

/** Person-session reads and explicit remote disable; BLE ingest uses a separate narrow grant. */
class RingCollectorsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
) {
    private val mutableState = MutableStateFlow<RingCollectorsState?>(null)
    val state: StateFlow<RingCollectorsState?> = mutableState.asStateFlow()
    private val visibility = MutableStateFlow(false to 0L)
    private val visibleOwners = mutableSetOf<Any>()
    private var boundLease: SessionLease? = null
    private val reads = Mutex()
    private val writes = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, visibility) { lease, visible -> lease to visible.first }
            .collectLatest { (lease, active) ->
                bind(lease)
                if (lease != null && active) refresh()
            }
    }

    fun activate(owner: Any = this) {
        visibleOwners += owner
        bind(sessions.lease())
        visibility.update { true to it.second + 1 }
    }
    fun deactivate(owner: Any = this) {
        visibleOwners -= owner
        visibility.update { visibleOwners.isNotEmpty() to it.second }
    }
    fun close() { owner.cancel(); visibleOwners.clear(); boundLease = null; mutableState.value = null }

    suspend fun refresh() = reads.withLock {
        val lease = sessions.lease() ?: return@withLock
        bind(lease)
        if ("personal.read" !in sessions.identity.value?.scopes.orEmpty()) {
            change(lease) { copy(items = emptyList(), loading = false) }
            return@withLock
        }
        change(lease) { copy(loading = true, error = null, serverError = null) }
        val outcome = http.request("GET", "/v1/personal/rings", lease = lease)
        if (!owned(lease)) return@withLock
        val data = (outcome as? HttpOutcome.Success)?.let { runCatching { decodeRingCollectors(it.body) }.getOrNull() }
        if (data == null) {
            val failure = if (outcome is HttpOutcome.Success) HttpOutcome.InvalidResponse else outcome
            change(lease) { copy(loading = false, error = nativeError(failure), serverError = devicesServerError(failure)) }
        } else change(lease) { copy(items = data, loading = false) }
    }

    /** No automatic retry: a lost response is resolved by rereading the authoritative source. */
    suspend fun disable(collectorId: String): Boolean {
        if (!writes.tryLock()) return false
        try {
            val lease = sessions.lease() ?: return false
            bind(lease)
            if ("personal.write" !in sessions.identity.value?.scopes.orEmpty()) return false
            if (state.value?.items?.none { it.collectorId == collectorId && it.enabled } != false) return false
            change(lease) { copy(busyCollector = collectorId, error = null, serverError = null) }
            try {
                val outcome = http.request("POST", "/v1/personal/rings/${collectorId.encodeURLPathPart()}/disable",
                    "{}".encodeToByteArray(), lease)
                if (!owned(lease)) return false
                refresh()
                if (outcome !is HttpOutcome.Success) change(lease) {
                    copy(error = nativeError(outcome), serverError = devicesServerError(outcome))
                }
                return outcome is HttpOutcome.Success && owned(lease)
            } finally { change(lease) { copy(busyCollector = null) } }
        } finally { writes.unlock() }
    }

    private fun bind(lease: SessionLease?) {
        if (boundLease === lease) return
        boundLease = lease
        mutableState.value = lease?.let { RingCollectorsState(it.namespace) }
    }
    private fun owned(lease: SessionLease) = boundLease === lease && sessions.isCurrent(lease)
    private inline fun change(lease: SessionLease, update: RingCollectorsState.() -> RingCollectorsState) {
        if (owned(lease)) mutableState.update { it?.update() }
    }
}
