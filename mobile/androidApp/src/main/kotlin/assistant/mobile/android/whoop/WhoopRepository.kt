package assistant.mobile.android.whoop

import android.content.Context
import assistant.mobile.android.R
import assistant.mobile.design.R as DesignR
import assistant.mobile.core.SessionLease
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.SessionManager
import assistant.mobile.platform.AndroidNativeOrigins
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One native-package collector; raw history never passes through the UI or a person-token upload. */
internal class WhoopRepository internal constructor(
    private val origin: String,
    private val bluetooth: WhoopBluetooth,
    private val store: WhoopStorePort,
    private val http: WhoopHttp,
    private val work: WhoopWorkScheduler,
    private val revokes: WhoopRevokeQueue,
    private val installation: () -> String,
    private val message: (String) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    sessionIsCurrent: (SessionLease) -> Boolean = { false },
) {
    private val lock = Mutex()
    private val bindingFence = Any()
    private val sessionFence = Any()
    private val uploadLock = Mutex()
    private val revokeLock = Mutex()
    private val generation = AtomicLong(1)
    private val tasks = WhoopTaskLifetime(scope)
    @Volatile private var link: WhoopConnection? = null
    private val account = WhoopAccountOwner {
        tasks.cancelForeground()
        try { link?.close() } catch (_: Exception) { /* The permission may already be revoked. */ }
        link = null
    }
    @Volatile private var sessionLease: SessionLease? = null
    private var lastNativeNamespace: AccountNamespace? = null
    private var nativeDeviceId: String? = null
    private var sessionSource: SessionManager? = null
    private var isCurrentSession: (SessionLease) -> Boolean = sessionIsCurrent
    private var sessionJob: Job? = null
    @Volatile private var storageUnavailable = false
    @Volatile private var forgetPending = false
    @Volatile private var stateValue = loadState()
    private val mutableState = MutableStateFlow(stateValue)
    val states: StateFlow<WhoopState> get() = mutableState

    init {
        publish()
        if (forgetPending) try { forget() } catch (_: Exception) { storageUnavailable = true; publish() }
        else if (stateValue.backgroundEnabled) grant()?.let { work.schedule(it.identity) }
        if (!storageUnavailable && !forgetPending && grant() != null && store.count() > 0) enqueueUpload()
        try { if (revokes.all().isNotEmpty()) work.revoke() } catch (_: Exception) { storageUnavailable = true; publish() }
    }

    /** Attach only the native session owner. A lapsed foreground lease preserves its separate grant. */
    fun bind(sessions: SessionManager): Unit = synchronized(sessionFence) {
        sessionJob?.cancel()
        sessionSource = sessions
        isCurrentSession = sessions::isCurrent
        updateSession(sessions.lease(), sessions.identity.value?.deviceId)
        sessionJob = scope.launch {
            sessions.leases.collect { lease ->
                synchronized(sessionFence) { if (sessionSource === sessions) updateSession(lease, sessions.identity.value?.deviceId) }
            }
        }
    }

    internal fun updateSession(lease: SessionLease?, deviceId: String? = null): Unit = synchronized(sessionFence) {
        val previous = sessionLease
        sessionLease = lease
        if (lease != null) nativeDeviceId = deviceId
        lease?.let { lastNativeNamespace = it.namespace }
        if (previous != null && previous !== lease) account.change(null, "lapsed") { grant()?.user }
        val reason = if (lease == null) "lapsed" else "switch"
        val changedAccount = account.change(lease?.namespace?.userId, reason) { grant(includePending = true)?.user }
        if (changedAccount || lease != null && grant(includePending = true) != null && grant() == null) fenceNativeBinding()
        publish { it.copy(phase = if (tasks.busy) it.phase else "idle", operationId = if (tasks.busy) it.operationId else null) }
        grant()?.let {
            if (stateValue.backgroundEnabled) work.schedule(it.identity)
            if (store.count() > 0) enqueueUpload()
        }
        Unit
    }

    /** Local cleanup is addressed to its account; an old deletion receipt cannot log out a new one. */
    suspend fun logout(namespace: AccountNamespace) {
        val cleanup = synchronized(sessionFence) {
            if (namespace.origin != origin) return@synchronized null
            sessionSource?.let { sessions ->
                val actual = sessions.lease()
                if (sessionLease !== actual) updateSession(actual, sessions.identity.value?.deviceId)
            }
            val nativeBinding = nativeGrantOwnedBy(namespace)
            val foreground = (sessionLease?.namespace ?: lastNativeNamespace) == namespace
            if (!foreground && !nativeBinding) return@synchronized null
            if (foreground) {
                sessionLease = null
                lastNativeNamespace = null
                account.change(null, "logout") { grant()?.user }
            }
            // Only authenticated full-namespace ownership authorizes durable collector cleanup.
            // No grant also covers unreadable ciphertext from an older server/origin. Detach
            // foreground work without changing that queue, its marker, or the revoke journal.
            if (nativeBinding) forget()
            else {
                synchronized(bindingFence) {
                    generation.incrementAndGet(); work.cancelAll()
                    try { link?.close() } catch (_: Exception) { }
                    link = null
                }
                stateValue = stateValue.copy(scanResults = emptyList())
                mutableState.value = if (storageUnavailable) storageError() else mutableState.value.copy(
                    phase = "idle", user = null, scanResults = emptyList(), operationId = null)
                null
            }
        }
        if (cleanup != null) {
            cleanup.join()
            if (storageUnavailable) throw WhoopFailure("store_unavailable")
        }
    }

    /** A server switch abandons every binding of this origin, including lapsed and closed ones. */
    suspend fun clearForServerSwitch() {
        val cleanup = synchronized(sessionFence) {
            sessionJob?.cancel(); sessionJob = null; sessionSource = null
            sessionLease = null; lastNativeNamespace = null; nativeDeviceId = null
            forgetPending = true
            account.change(null, "logout") { null }
            account.invalidateBinding {
                synchronized(bindingFence) {
                    val previous = grant(includePending = true)
                    generation.incrementAndGet(); work.cancelAll()
                    try { link?.close() } catch (_: Exception) { }
                    link = null
                    store.markForgetPending()
                    previous?.let(revokes::add)
                }
            }
            tasks.launchCleanup {
                try {
                    lock.withLock {
                        synchronized(bindingFence) {
                            store.clearForServerSwitch()
                            storageUnavailable = false; stateValue = WhoopState()
                            // Keep this repository fenced until restart; publishing must not
                            // recreate the shared database encrypted for the departing origin.
                            mutableState.value = stateValue
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { storageUnavailable = true; publish(); return@launchCleanup }
            }
        }
        cleanup.join()
        if (storageUnavailable) throw WhoopFailure("store_unavailable")
        work.cancelAllForServerSwitch()
        work.revoke()
    }

    private fun loadState(): WhoopState = try {
        forgetPending = store.forgetPending()
        if (forgetPending) WhoopState() else WhoopState.restore(
            store.get("state") as? JsonObject ?: WhoopState().json(),
            store.get("background")?.jsonPrimitive?.booleanOrNull ?: false,
        )
    } catch (_: Exception) { storageUnavailable = true; storageError() }

    private fun storageError() = WhoopState(phase = "error", error = problem("store_unavailable"))
    private fun problem(code: String) = WhoopProblem(code, message(code))
    private fun grant(includePending: Boolean = false): WhoopGrant? {
        if ((storageUnavailable || forgetPending) && !includePending) return null
        return try {
            val value = (store.get("grant") as? JsonObject)?.let(::WhoopGrant) ?: return null
            if (includePending || nativeGrantAllowed(value)) value else null
        } catch (_: Exception) { storageUnavailable = true; null }
    }
    private fun nativeGrantOwnedBy(namespace: AccountNamespace): Boolean {
        val verified = grant() ?: return false
        if (namespace.origin != origin || verified.user != namespace.userId) return false
        return try {
            val marker = store.get("native_enrollment") as? JsonObject ?: return false
            marker.string("instance") == namespace.instance && marker.string("origin") == namespace.origin &&
                marker.string("user_id") == namespace.userId
        } catch (_: Exception) { storageUnavailable = true; false }
    }
    private fun nativeGrantMarker(value: WhoopGrant, lease: SessionLease): JsonObject {
        val device = nativeDeviceId?.takeIf { it.isNotBlank() } ?: throw WhoopFailure("not_authenticated")
        if (lease.namespace.origin != origin || lease.namespace.userId != value.user) throw WhoopFailure("not_authenticated")
        return obj {
            put("instance", lease.namespace.instance); put("origin", origin); put("user_id", value.user); put("device_id", device)
            put("collector_id", value.identity); put("source_id", value.source); put("generation", value.value["generation"] ?: JsonNull)
        }
    }
    private fun nativeGrantAllowed(value: WhoopGrant): Boolean {
        if ((store.get("native_enrollment_blocked") as? JsonPrimitive)?.booleanOrNull == true) return false
        val marker = store.get("native_enrollment") as? JsonObject ?: return false
        if (marker.string("instance").isNullOrBlank() || marker.string("device_id").isNullOrBlank() ||
            marker.string("origin") != origin || marker.string("user_id") != value.user ||
            marker.string("collector_id") != value.identity || marker.string("source_id") != value.source ||
            marker["generation"] != value.value["generation"]) return false
        // Keystore-authenticated native enrollment provenance also covers background process restarts.
        // collect/upload verify collector state with the server before using the grant.
        val lease = sessionLease ?: return true
        if (nativeDeviceId.isNullOrBlank() || lease.namespace.origin != origin || lease.namespace.userId != value.user) return false
        return marker == nativeGrantMarker(value, lease)
    }
    private fun fenceNativeBinding() {
        if (grant(includePending = true) == null) return
        synchronized(bindingFence) {
            store.put("native_enrollment_blocked", JsonPrimitive(true))
            generation.incrementAndGet(); work.cancelAll()
            try { link?.close() } catch (_: Exception) { }
            link = null
        }
    }

    @Synchronized private fun publish(update: (WhoopState) -> WhoopState = { it }) {
        if (storageUnavailable || forgetPending) {
            stateValue = (if (storageUnavailable) storageError() else WhoopState()).copy(user = account.user)
            mutableState.value = stateValue
            return
        }
        val grant = grant()
        val updated = update(stateValue)
        val permission = bluetooth.permissionState()
        try {
            stateValue = updated.copy(
                permission = if (permission == "required" && updated.permission == "denied") "denied" else permission,
                bluetoothEnabled = bluetooth.bluetoothEnabled(), queueCount = store.count(), queueBytes = store.bytes(),
                user = grant?.user ?: account.user, source = grant?.source,
            )
            store.put("state", stateValue.json())
            mutableState.value = if (grant == null && grant(includePending = true) != null) stateValue.copy(
                selected = null, source = null, user = account.user, backgroundEnabled = false,
                error = problem("native_enrollment_required"),
            ) else stateValue
        } catch (_: Exception) {
            storageUnavailable = true
            stateValue = storageError().copy(user = account.user)
            mutableState.value = stateValue
        }
    }

    fun reconcile() { publish() }
    fun permissionNames(): Array<String> = bluetooth.permissionNames()
    fun permissionResult(granted: Boolean) { publish { it.copy(permission = if (granted) "granted" else "denied") } }
    fun scan() = operation { owner ->
        if (bluetooth.permissionState() != "granted") throw WhoopFailure("permission_denied")
        if (!bluetooth.bluetoothEnabled()) throw WhoopFailure("bluetooth_off")
        publish { it.copy(phase = "scanning", scanResults = emptyList()) }
        val found = bluetooth.scan()
        requireGeneration(owner)
        publish { it.copy(scanResults = found) }
    }
    fun select(address: String) = operation { owner -> selectLocked(address, owner) }
    fun sync() = operation { owner ->
        if (account.user != grant()?.user) throw WhoopFailure("not_authenticated")
        try { collectLocked(owner, foreground = true) } finally { enqueueUpload() }
    }
    fun background(enabled: Boolean) = operation { owner ->
        val grant = grant() ?: throw WhoopFailure("not_bound")
        if (account.user != grant.user) throw WhoopFailure("not_authenticated")
        if (enabled) http.state(grant)
        current(owner) {
            store.put("background", JsonPrimitive(enabled))
            publish { it.copy(backgroundEnabled = enabled) }
            if (enabled) { work.schedule(grant.identity); enqueueUpload() } else work.cancelCollect(grant.identity)
        }
    }

    private fun operation(action: suspend (Long) -> Unit) {
        if (storageUnavailable) { fail(WhoopFailure("store_unavailable")); return }
        if (forgetPending || tasks.busy) return
        val id = UUID.randomUUID().toString()
        val owner = generation.get()
        publish { it.copy(operationId = id, error = null) }
        tasks.launchForeground {
            try {
                lock.withLock { requireGeneration(owner); action(owner) }
                if (owner == generation.get()) publish { it.copy(phase = "idle", operationId = null) }
            } catch (timeout: TimeoutCancellationException) { if (owner == generation.get()) fail(timeout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (owner == generation.get()) fail(failure) }
        }
    }

    private fun requireGeneration(owner: Long) {
        if (owner != generation.get()) throw CancellationException("WHOOP binding changed")
    }
    private fun <T> current(owner: Long, action: () -> T): T = synchronized(bindingFence) {
        requireGeneration(owner)
        action()
    }
    private fun fail(error: Exception) {
        val failure = error as? WhoopFailure ?: WhoopFailure(if (error is TimeoutCancellationException) "timeout" else "unavailable")
        // Platform diagnostics already come from this module's resources; pure-session codes resolve here.
        val detail = if (failure.message == failure.code) message(failure.code) else failure.message
        publish { it.copy(phase = "error", operationId = null, error = WhoopProblem(failure.code, detail)) }
    }

    fun forget(): Job {
        try {
            account.invalidateBinding {
                synchronized(bindingFence) {
                    val previous = grant(includePending = true)
                    generation.incrementAndGet()
                    forgetPending = true
                    work.cancelAll()
                    try { link?.close() } catch (_: Exception) { /* A revoked permission cannot retain authority. */ }
                    link = null
                    // The fence survives a crash before the credential reaches the revoke queue.
                    // Keep the old encrypted grant until both writes are durable.
                    store.markForgetPending()
                    previous?.let(revokes::add)
                }
            }
        } catch (_: Exception) {
            // Permission revocation/logout still blocks every worker when storage is broken.
            storageUnavailable = true
            publish()
            return scope.launch {}
        }
        publish()
        return tasks.launchCleanup {
            try {
                lock.withLock {
                    synchronized(bindingFence) {
                        if (storageUnavailable) throw WhoopFailure("store_unavailable")
                        store.clear()
                        storageUnavailable = false; forgetPending = false; stateValue = WhoopState(); publish()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { storageUnavailable = true; publish(); return@launchCleanup }
            // Logout awaits local erasure only. Network revocation is a durable, separate worker.
            work.revoke()
        }
    }

    private suspend fun selectLocked(address: String, owner: Long) {
        val found = stateValue.scanResults.singleOrNull { it.address == address } ?: throw WhoopFailure("not_scanned")
        val old = stateValue.selected
        if (grant() != null && old != null && old.address != address) throw WhoopFailure("already_bound")
        val (lease, accountOwner) = synchronized(sessionFence) {
            val lease = sessionLease?.takeIf(isCurrentSession) ?: throw WhoopFailure("not_authenticated")
            lease to account.capture()
        }
        if (nativeDeviceId.isNullOrBlank()) throw WhoopFailure("not_authenticated")
        if (grant() == null && grant(includePending = true) != null) {
            store.closeBinding("explicit_native_enrollment")
            stateValue = WhoopState(scanResults = stateValue.scanResults)
            publish()
        }
        fun checkpoint() {
            account.requireCurrent(accountOwner)
            requireGeneration(owner)
            if (!isCurrentSession(lease)) throw CancellationException("Native session changed")
        }
        publish { it.copy(phase = "pairing") }
        bluetooth.bond(address)
        checkpoint()
        val binding = UUID.nameUUIDFromBytes(
            "whoop-v1\n${installation()}\n$origin\n${accountOwner.user}\n${address.uppercase()}".toByteArray(Charsets.UTF_8),
        ).toString()
        account.enroll(accountOwner, grant(),
            issue = { check, issued -> http.enroll(lease, binding, found.name, { checkpoint(); check() }, issued) },
            state = { grant, check -> http.state(grant) { checkpoint(); check() } },
            persist = { grant, remote ->
                current(owner) {
                    checkpoint()
                    if (remote.string("profile") != WhoopState.PROFILE) throw WhoopFailure("invalid_binding")
                    store.put("grant", grant.value)
                    store.put("native_enrollment", nativeGrantMarker(grant, lease))
                    store.put("native_enrollment_blocked", JsonPrimitive(false))
                    store.put("server_cursor", remote["cursor"] ?: JsonNull)
                    publish { it.copy(selected = found) }
                }
            }, revoke = { grant ->
                revokes.add(grant)
                if (forgetPending || !revokePending()) work.revoke()
            },
        )
        checkpoint()
        currentCoroutineContext().ensureActive()
        try { collectLocked(owner, foreground = true) } finally { enqueueUpload() }
    }

    private suspend fun collectLocked(owner: Long, foreground: Boolean) {
        val grant = grant() ?: throw WhoopFailure("not_bound")
        val address = stateValue.selected?.address ?: throw WhoopFailure("not_bound")
        if (bluetooth.permissionState() != "granted") throw WhoopFailure("permission_denied")
        if (!bluetooth.bluetoothEnabled()) throw WhoopFailure("bluetooth_off")
        if (store.full()) throw WhoopFailure("queue_full")
        http.state(grant) { requireGeneration(owner) } // Check the native parent grant before opening BLE.
        requireGeneration(owner)
        val budget = if (foreground) WhoopPolicy.FOREGROUND_MS else WhoopPolicy.BACKGROUND_MS
        publish { it.copy(phase = "connecting") }
        val gatt = current(owner) {
            bluetooth.connection(address).also { link = it }
        }
        try {
            requireGeneration(owner)
            withTimeout(budget + 60_000) {
                gatt.open()
                requireGeneration(owner)
                val battery = gatt.battery(); val firmware = gatt.version("firmware"); val hardware = gatt.version("hardware")
                current(owner) {
                    store.put("device_state", obj {
                        put("observed_at", Instant.ofEpochMilli(now()).toString())
                        battery?.let { put("battery_percent", it) }
                        firmware?.let { put("firmware", it) }; hardware?.let { put("hardware", it) }
                    })
                    publish { it.copy(phase = "syncing", battery = battery ?: it.battery, firmware = firmware ?: it.firmware, hardware = hardware ?: it.hardware) }
                }
                val outcome = WhoopSession(gatt, now).run(now() + budget) { frames -> current(owner) { store.append(frames) } }
                current(owner) {
                    publish { it.copy(lastSync = Instant.ofEpochMilli(now()).toString(), historyComplete = outcome.complete) }
                    if (!outcome.complete && outcome.parts > 0) work.continueHistory(grant.identity)
                }
            }
        } finally { gatt.close(); if (link === gatt) link = null }
    }

    private fun enqueueUpload() { grant()?.let { work.upload(it.identity) } }

    internal suspend fun collect(collector: String, continuation: Boolean): Boolean = lock.withLock {
        if (grant()?.identity != collector || (!stateValue.backgroundEnabled && !continuation)) return@withLock true
        try { collectLocked(generation.get(), foreground = false); publish { it.copy(phase = "idle") } }
        catch (timeout: TimeoutCancellationException) { fail(timeout) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail(error) }
        finally { enqueueUpload() }
        true
    }

    internal suspend fun upload(collector: String): Boolean = uploadLock.withLock {
        val (grant, owner) = synchronized(bindingFence) {
            val grant = grant()?.takeIf { it.identity == collector } ?: return@withLock true
            grant to generation.get()
        }
        fun cursor(batch: JsonObject) = (batch["body"] as JsonObject)["cursor"]!!
        fun pending() = store.get("device_state") as? JsonObject
        try {
            var remote = http.state(grant)
            requireGeneration(owner)
            for (packet in 0 until 50) {
                var batch = current(owner) { store.batch(remote["cursor"] ?: JsonNull, pending()) } ?: break
                var reply = http.receipt(grant, batch.string("key")!!)
                requireGeneration(owner)
                if (reply.status == 404) { reply = http.send(grant, batch); requireGeneration(owner) }
                if (reply.status == 409) {
                    val checked = http.receipt(grant, batch.string("key")!!)
                    requireGeneration(owner)
                    if (checked.status == 200 && WhoopReceipts.matches(checked.value, grant.source, cursor(batch))) {
                        current(owner) { store.acknowledge(batch) }
                        remote = http.state(grant)
                        requireGeneration(owner)
                        continue
                    }
                    remote = http.state(grant)
                    batch = current(owner) { store.rebase(batch, remote["cursor"] ?: JsonNull) }
                    reply = http.send(grant, batch)
                    requireGeneration(owner)
                }
                if (reply.status in setOf(401, 403, 404)) throw WhoopFailure("grant_revoked")
                if (reply.status !in 200..299) throw WhoopFailure("upload_failed")
                if (!WhoopReceipts.matches(reply.value, grant.source, cursor(batch))) throw WhoopFailure("invalid_receipt")
                current(owner) { store.acknowledge(batch) }
                remote = http.state(grant)
                requireGeneration(owner)
            }
            current(owner) {
                publish { it.copy(lastUpload = Instant.ofEpochMilli(now()).toString(), error = if (it.phase == "error") it.error else null) }
                if (store.count() > 0) enqueueUpload()
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            synchronized(bindingFence) {
                if (owner != generation.get()) return@withLock true
                fail(error)
                if ((error as? WhoopFailure)?.code in setOf("grant_revoked", "invalid_binding")) {
                    work.cancelCollect(grant.identity)
                    store.put("background", JsonPrimitive(false)); publish { it.copy(backgroundEnabled = false) }
                    true
                } else false
            }
        }
    }

    internal suspend fun revokePending(): Boolean = revokeLock.withLock {
        try {
            for (grant in revokes.all()) {
                currentCoroutineContext().ensureActive()
                http.revoke(grant); revokes.remove(grant)
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    companion object {
        private val instances = mutableMapOf<String, WhoopRepository>()
        fun get(context: Context, origin: String): WhoopRepository = synchronized(instances) {
            instances.getOrPut(origin) { create(context.applicationContext, origin) }
        }
        private fun create(context: Context, origin: String): WhoopRepository = WhoopRepository(
            origin, AndroidWhoopBluetooth(context), AndroidWhoopStore(context, origin),
            NativeWhoopHttp(origin) { AndroidNativeOrigins.serverOrigin(context, origin) },
            AndroidWhoopWorkScheduler(context, origin), AndroidWhoopRevokeQueue(context, origin),
            installation = {
                val preferences = context.getSharedPreferences("native-whoop-installation", Context.MODE_PRIVATE)
                preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
                    check(preferences.edit().putString("id", it).commit())
                }
            },
            message = { code -> context.getString(when (code) {
                "whoop_no_reply" -> DesignR.string.whoop_error_whoop_no_reply
                "whoop_history_refused" -> DesignR.string.whoop_error_whoop_history_refused
                "not_authenticated" -> DesignR.string.whoop_error_not_authenticated
                "store_unavailable" -> R.string.whoop_runtime_store_unavailable
                "permission_denied" -> DesignR.string.whoop_error_permission_denied
                "bluetooth_off" -> DesignR.string.whoop_error_bluetooth_off
                "not_bound" -> R.string.whoop_runtime_not_bound
                "native_enrollment_required" -> R.string.whoop_runtime_native_enrollment_required
                "not_scanned" -> DesignR.string.whoop_error_not_scanned
                "already_bound" -> DesignR.string.whoop_error_already_bound
                "queue_full" -> DesignR.string.whoop_error_queue_full
                "grant_revoked" -> DesignR.string.whoop_error_grant_revoked
                "invalid_binding" -> R.string.whoop_runtime_invalid_binding
                "enrollment_failed" -> R.string.whoop_runtime_enrollment_failed
                "invalid_receipt" -> R.string.whoop_runtime_invalid_receipt
                "upload_failed" -> DesignR.string.whoop_error_upload_failed
                "timeout" -> DesignR.string.whoop_error_timeout
                "redirect_rejected" -> R.string.whoop_runtime_redirect_rejected
                "response_too_large" -> R.string.whoop_runtime_response_too_large
                "invalid_response" -> R.string.whoop_runtime_invalid_response
                else -> DesignR.string.whoop_error_unavailable
            }) },
        )
    }
}
