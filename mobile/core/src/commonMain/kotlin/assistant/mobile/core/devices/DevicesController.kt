package assistant.mobile.core.devices

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Client-device reads and explicit owner actions over the account's private session. */
class DevicesController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
) {
    private val mutableState = MutableStateFlow<DevicesState?>(null)
    val state: StateFlow<DevicesState?> = mutableState.asStateFlow()
    private val visibility = MutableStateFlow(DevicesVisibility(false, 0))
    private var boundLease: SessionLease? = null
    private val reads = Mutex()
    private val writes = Mutex()
    private val owner = scope.launch {
        combine(sessions.leases, visibility) { lease, visible -> lease to visible }.collectLatest { (lease, visible) ->
            bind(lease)
            if (lease != null && visible.active) refresh()
        }
    }

    fun activate() {
        bind(sessions.lease())
        visibility.update { DevicesVisibility(true, it.generation + 1) }
    }

    fun deactivate() { visibility.update { it.copy(active = false) } }

    fun close() {
        owner.cancel()
        deactivate()
        boundLease = null
        mutableState.value = null
    }

    /** Also called after display writes while the list is hidden, so its next open is current. */
    suspend fun refresh() = reads.withLock {
        val lease = sessions.lease() ?: return@withLock
        bind(lease)
        change(lease) { copy(devices = devices.copy(loading = true, error = null, serverError = null)) }
        val outcome = http.request("GET", "/v1/devices", lease = lease)
        if (!owned(lease)) return@withLock
        val data = if (outcome is HttpOutcome.Success) runCatching { decodeDevices(outcome.body) }.getOrNull() else null
        if (data == null) {
            val failure = if (outcome is HttpOutcome.Success) HttpOutcome.InvalidResponse else outcome
            change(lease) { copy(devices = devices.copy(loading = false, error = nativeError(failure), serverError = devicesServerError(failure))) }
        } else change(lease) { copy(devices = DevicesQuery(data)) }
    }

    fun clearError() { mutableState.update { it?.copy(error = null, serverError = null) } }

    suspend fun approve(code: String, name: String = ""): PairedDevice? {
        if (!writes.tryLock()) return null
        try {
            val lease = sessions.lease() ?: return null
            bind(lease)
            val normalized = pairingCode(code)
            if (normalized == null) {
                change(lease) { copy(error = GeneratedStrings.Devices.codeInvalid, serverError = null) }
                return null
            }
            change(lease) { copy(busy = true, error = null, serverError = null) }
            try {
                val body = buildJsonObject {
                    put("code", normalized)
                    name.trim().takeIf { it.isNotEmpty() }?.let { put("name", it) }
                }.toString().encodeToByteArray()
                val outcome = http.request("POST", "/v1/devices/pairings/approve", body, lease)
                if (!owned(lease)) return null
                val paired = if (outcome is HttpOutcome.Success) runCatching { decodePairedDevice(outcome.body) }.getOrNull() else null
                if (paired == null) {
                    val failure = if (outcome is HttpOutcome.Success) HttpOutcome.InvalidResponse else outcome
                    change(lease) { copy(error = nativeError(failure), serverError = devicesServerError(failure)) }
                    return null
                }
                refresh()
                return if (owned(lease)) paired else null
            } finally { change(lease) { copy(busy = false) } }
        } finally { writes.unlock() }
    }

    suspend fun revoke(id: String): Boolean {
        if (!writes.tryLock()) return false
        try {
            val lease = sessions.lease() ?: return false
            bind(lease)
            val device = state.value?.devices?.data?.firstOrNull { it.id == id } ?: return false
            if (device.current) return false
            change(lease) { copy(busy = true, error = null, serverError = null) }
            try {
                val outcome = http.request("POST", "/v1/devices/${id.encodeURLPathPart()}/revoke", "{}".encodeToByteArray(), lease)
                if (!owned(lease)) return false
                val success = outcome is HttpOutcome.Success
                if (!success) change(lease) { copy(error = nativeError(outcome), serverError = devicesServerError(outcome)) }
                // The web refreshes even on a refused revoke; stale rows must not remain actionable.
                refresh()
                return success && owned(lease)
            } finally { change(lease) { copy(busy = false) } }
        } finally { writes.unlock() }
    }

    private fun bind(lease: SessionLease?) {
        if (boundLease === lease) return
        boundLease = lease
        mutableState.value = lease?.let { DevicesState(it.namespace) }
    }

    private fun owned(lease: SessionLease): Boolean = boundLease === lease && sessions.isCurrent(lease)

    private inline fun change(lease: SessionLease, update: DevicesState.() -> DevicesState) {
        if (owned(lease)) mutableState.update { it?.update() }
    }
}

/** A rapid stop/start must still request a fresh authoritative list. */
internal data class DevicesVisibility(val active: Boolean, val generation: Long)
