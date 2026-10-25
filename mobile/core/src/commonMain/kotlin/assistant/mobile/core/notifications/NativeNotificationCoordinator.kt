package assistant.mobile.core.notifications

import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/** Local-only delivery: no push registration, cookie bridge or background account credentials. */
class NativeNotificationCoordinator(
    private val scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val platform: NotificationAlarmPlatform,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var bindingJob: Job? = null
    private var retryJob: Job? = null
    private val mutableState = MutableStateFlow(platform.state())
    val state: StateFlow<NotificationDeviceState> = mutableState.asStateFlow()

    /** Start after native session restoration, preserving the saved same-session device opt-in. */
    fun start() {
        if (bindingJob != null) return
        bindingJob = scope.launch {
            combine(sessions.leases, sessions.identity) { lease, identity -> lease to identity }.collectLatest { (lease, identity) ->
                if (lease == null || identity == null) {
                    retryJob?.cancel()
                    retryJob = null
                    platform.clear()
                    refreshState()
                    return@collectLatest
                }
                val expires = runCatching { Instant.parse(identity.sessionExpiresAt).toEpochMilliseconds() }.getOrNull()
                val device = identity.deviceId
                if (expires == null || expires <= clockMs() || device.isNullOrBlank() || lease.sessionId.isBlank()) {
                    platform.clear()
                    refreshState()
                    return@collectLatest
                }
                val owner = NotificationSession(lease.namespace.instance, lease.namespace.origin,
                    lease.namespace.userId, device, lease.sessionId, expires)
                sessions.withLease(lease) { platform.bind(owner) }
                refreshState()
                sync()
                delay((expires - clockMs()).coerceAtLeast(1))
                if (sessions.isCurrent(lease)) {
                    platform.clear()
                    refreshState()
                    sessions.expire(lease)
                }
            }
        }
    }

    fun refreshState() { mutableState.value = platform.state() }

    /** Called after lease invalidation, before restart; an old network sync cannot reinstall. */
    fun clearForServerSwitch() {
        retryJob?.cancel()
        retryJob = null
        platform.clear()
        refreshState()
    }

    /** Called only after the owner's explicit action and the platform permission result. */
    suspend fun enable(): HttpOutcome {
        val lease = sessions.lease() ?: return HttpOutcome.Unauthorized
        if (platform.state().permission != NotificationPermission.Granted) {
            refreshState()
            return HttpOutcome.Forbidden
        }
        sessions.withLease(lease) { platform.setEnabled(true) } ?: return HttpOutcome.StaleSession
        refreshState()
        return sync()
    }

    suspend fun disable(): HttpOutcome {
        val lease = sessions.lease() ?: return HttpOutcome.Unauthorized
        sessions.withLease(lease) { platform.setEnabled(false) } ?: return HttpOutcome.StaleSession
        retryJob?.cancel()
        retryJob = null
        refreshState()
        // This native package has no remote push channel to remove.
        return HttpOutcome.Success(200, "{}".encodeToByteArray(), emptyMap())
    }

    /** Serialize snapshots and retry the durable receipt before obtaining another projection. */
    suspend fun sync(): HttpOutcome {
        val result = syncOnce()
        val lease = sessions.lease()
        if (result !is HttpOutcome.Success && result != HttpOutcome.Unauthorized && result != HttpOutcome.Forbidden &&
            result != HttpOutcome.StaleSession && result !is HttpOutcome.Conflict &&
            !(result is HttpOutcome.Failure && result.status < 500) &&
            lease != null && platform.pendingAck()?.sessionId == lease.sessionId && platform.state().enabled) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(30_000)
                retryJob = null
                if (sessions.isCurrent(lease)) sync()
            }
        } else if (platform.pendingAck() == null) {
            retryJob?.cancel()
            retryJob = null
        }
        return result
    }

    private suspend fun syncOnce(): HttpOutcome = mutex.withLock {
        val lease = sessions.lease() ?: return@withLock HttpOutcome.Unauthorized
        refreshState()
        if (!state.value.enabled) return@withLock HttpOutcome.Success(200, "{}".encodeToByteArray(), emptyMap())
        var conflicts = 0
        while (true) {
            if (!sessions.isCurrent(lease)) return@withLock HttpOutcome.StaleSession
            val pending = platform.pendingAck()
            if (pending != null) {
                if (pending.sessionId != lease.sessionId) return@withLock HttpOutcome.StaleSession
                val ack = acknowledge(pending, lease)
                refreshState()
                if (ack !is HttpOutcome.Success) {
                    if (ack is HttpOutcome.Conflict && conflicts++ == 0) {
                        sessions.withLease(lease) { platform.acknowledged(pending.snapshotId) }
                    } else return@withLock ack
                }
            }
            val response = http.request("GET", "/v1/notifications/schedule", lease = lease)
            if (response !is HttpOutcome.Success) return@withLock response
            val schedule = runCatching { json.decodeFromString<NotificationSchedule>(response.body.decodeToString()) }.getOrNull()
                ?: return@withLock HttpOutcome.InvalidResponse
            val accepted = try {
                sessions.withLease(lease) { platform.install(schedule) }
            } catch (_: IllegalArgumentException) { return@withLock HttpOutcome.InvalidResponse }
            if (accepted == null) return@withLock HttpOutcome.StaleSession
            val receipt = platform.pendingAck()
                ?: return@withLock HttpOutcome.StorageUnavailable
            if (accepted.toSet().size != accepted.size || accepted.any { key -> schedule.items.none { it.key == key } })
                return@withLock HttpOutcome.InvalidResponse
            refreshState()
            val ack = acknowledge(receipt, lease)
            refreshState()
            if (ack is HttpOutcome.Conflict && conflicts++ == 0) {
                sessions.withLease(lease) { platform.acknowledged(receipt.snapshotId) }
                continue
            }
            return@withLock ack
        }
        @Suppress("UNREACHABLE_CODE")
        HttpOutcome.InvalidResponse
    }

    private suspend fun acknowledge(ack: NotificationScheduleAck, lease: assistant.mobile.core.SessionLease): HttpOutcome {
        val body = buildJsonObject {
            put("snapshot_id", ack.snapshotId)
            put("installed", JsonArray(ack.installed.map(::JsonPrimitive)))
        }.toString().encodeToByteArray()
        var result: HttpOutcome = HttpOutcome.NetworkFailure
        repeat(3) { attempt ->
            if (!sessions.isCurrent(lease)) return HttpOutcome.StaleSession
            result = http.request("POST", "/v1/notifications/schedule/ack", body, lease)
            if (result is HttpOutcome.Success) {
                val receipt = runCatching {
                    val objectValue = json.parseToJsonElement((result as HttpOutcome.Success).body.decodeToString()) as JsonObject
                    (objectValue["installed"] as JsonArray).map { element ->
                        element.jsonPrimitive.takeIf { it.isString }?.content ?: error("Invalid receipt key")
                    }
                }.getOrNull() ?: return HttpOutcome.InvalidResponse
                if (receipt.toSet().size != receipt.size || receipt.toSet() != ack.installed.toSet()) return HttpOutcome.InvalidResponse
                sessions.withLease(lease) { platform.acknowledged(ack.snapshotId) }
                return result
            }
            if (result != HttpOutcome.NetworkFailure && !(result is HttpOutcome.Failure && (result as HttpOutcome.Failure).status >= 500))
                return result
            if (attempt < 2) delay(500L * (attempt + 1))
        }
        return result
    }
}
