package assistant.mobile.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class OriginMoveState {
    @SerialName("moved") MOVED,
    @SerialName("rejected") REJECTED,
}

/** The only durable move decision; local account data always stays under [home]. */
@Serializable
data class OriginMoveRecord(
    val home: String,
    val server: String,
    @SerialName("instance_id") val instanceId: String,
    val state: OriginMoveState,
    @SerialName("decided_at") val decidedAt: String,
)

interface OriginMoveRecordStore {
    /** An absent, unreadable or malformed record is null. */
    fun read(): OriginMoveRecord?
    /** Atomically replaces the complete record, or throws without committing it. */
    fun write(record: OriginMoveRecord)
}

/** This port deliberately has no save or delete operation. */
interface OriginMoveSessions {
    /** Checks only for the session file, without decrypting it. */
    fun exists(instance: String, origin: String): Boolean
    suspend fun load(instance: String, origin: String): StoredSession?
}

sealed interface OriginMoveConfigResult {
    data class Available(val instanceId: String) : OriginMoveConfigResult
    /** Includes network errors, timeouts, non-success statuses and malformed responses. */
    data object Unavailable : OriginMoveConfigResult
}

sealed interface OriginMoveMeResult {
    data class Identity(val userId: String, val deviceId: String) : OriginMoveMeResult
    /** Only HTTP 401 or 403. A different successful identity is checked by the coordinator. */
    data object Rejected : OriginMoveMeResult
    data object Unavailable : OriginMoveMeResult
}

/** Implementations never follow redirects and never mutate session storage. */
interface OriginMoveProbe {
    suspend fun config(origin: String): OriginMoveConfigResult
    suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult
}

data class OriginMoveResolution(val homeOrigin: String, val serverOrigin: String, val pending: Boolean)

/** A fixed process namespace with retryable background verification and dynamic server routing. */
class OriginMoveCoordinator(
    currentOrigin: String,
    previousOrigin: String?,
    private val sessions: OriginMoveSessions,
    private val records: OriginMoveRecordStore,
    private val probe: OriginMoveProbe,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val allowInsecureLoopback: Boolean = false,
    selection: NativeServerSelection? = null,
) {
    /** The process keeps this choice even when a later manual switch commits another record. */
    val initialServerSelection: NativeServerSelection? = selection

    private val custom = selection?.takeIf {
        it.kind == NativeServerKind.CUSTOM &&
            runCatching { validateNativeServerSelection(it, allowInsecureLoopback) }.isSuccess
    }
    val currentOrigin = canonicalOrigin(custom?.origin ?: currentOrigin, allowInsecureLoopback)
    private val previousOrigin = previousOrigin?.takeIf { custom == null && it.isNotEmpty() }
        ?.let { canonicalOrigin(it, allowInsecureLoopback) }
    private val checkLock = Mutex()
    private var nextAttemptAtMillis: Long? = null
    private val process by lazy {
        val record = if (custom == null) records.read()?.takeIf { validRecord(it, allowInsecureLoopback) } else null
        ProcessOrigins(select(record), record)
    }

    init { require(this.previousOrigin != this.currentOrigin) }

    val homeOrigin: String get() = process.initial.homeOrigin

    fun resolve(): OriginMoveResolution {
        val initial = process.initial
        val record = process.record
        val decided = record != null && record.home == initial.homeOrigin && record.server == currentOrigin
        return OriginMoveResolution(initial.homeOrigin, serverOrigin(initial.homeOrigin, record),
            initial.pending && !decided)
    }

    /** Links belong to the selected server, including the old host during a pending default move. */
    fun acceptsOrigin(origin: String?): Boolean {
        val resolved = resolve()
        return origin != null && (origin == currentOrigin || origin == resolved.serverOrigin ||
            (resolved.pending && origin == resolved.homeOrigin))
    }

    private fun select(record: OriginMoveRecord?): OriginMoveResolution {
        if (record?.state == OriginMoveState.MOVED && record.server == currentOrigin)
            return OriginMoveResolution(record.home, currentOrigin, false)
        val previous = previousOrigin ?: return current()
        val candidate = if (record?.state == OriginMoveState.MOVED && record.server == previous)
            record.home else previous
        val rejected = record?.state == OriginMoveState.REJECTED &&
            record.home == candidate && record.server == currentOrigin
        if (!rejected && sessions.exists(INSTANCE, candidate) && !sessions.exists(INSTANCE, currentOrigin))
            return OriginMoveResolution(candidate, movedServer(candidate, record) ?: candidate, true)
        return current()
    }

    /** Every network dispatch sees a committed move without rereading its file. */
    fun serverOrigin(home: String): String = serverOrigin(home, process.record)

    suspend fun checkPending(): OriginMoveResolution = checkLock.withLock {
        val pending = resolve()
        if (!pending.pending || !eligible(pending.homeOrigin)) return@withLock pending
        val now = clockMs()
        if (nextAttemptAtMillis?.let { now < it } == true) return@withLock pending
        nextAttemptAtMillis = now + RETRY_INTERVAL_MS
        val target = probe.config(currentOrigin)
        val instanceId = (target as? OriginMoveConfigResult.Available)?.instanceId?.let(::uuid)
            ?: return@withLock resolve()
        val old = probe.config(pending.serverOrigin)
        val oldInstance = (old as? OriginMoveConfigResult.Available)?.instanceId?.let(::uuid)
        var verifiedSession: StoredSession? = null
        val state = if (oldInstance != null && oldInstance != instanceId) {
            OriginMoveState.REJECTED
        } else {
            val stored = sessions.load(INSTANCE, pending.homeOrigin) ?: return@withLock resolve()
            if (stored.namespace.instance != INSTANCE || stored.namespace.origin != pending.homeOrigin ||
                stored.namespace.userId.isBlank() || stored.namespace.userId != stored.identity.id ||
                stored.deviceId.isBlank() || stored.deviceId != stored.identity.deviceId ||
                !validSessionCredential(stored.credential)) return@withLock resolve()
            verifiedSession = stored
            // The credential is sent only to the current origin declared by this build.
            when (val me = probe.me(currentOrigin, stored)) {
                is OriginMoveMeResult.Identity -> if (me.userId == stored.namespace.userId && me.deviceId == stored.deviceId)
                    OriginMoveState.MOVED else OriginMoveState.REJECTED
                OriginMoveMeResult.Rejected -> if (oldInstance == instanceId) return@withLock resolve()
                    else OriginMoveState.REJECTED
                OriginMoveMeResult.Unavailable -> return@withLock resolve()
            }
        }
        if (verifiedSession != null && !sameAuthority(verifiedSession, sessions.load(INSTANCE, pending.homeOrigin)))
            return@withLock resolve()
        currentCoroutineContext().ensureActive()
        if (!eligible(pending.homeOrigin)) return@withLock resolve()
        val decision = OriginMoveRecord(pending.homeOrigin, currentOrigin, instanceId, state,
            Instant.fromEpochMilliseconds(clockMs()).toString())
        records.write(decision)
        process.record = decision
        resolve()
    }

    private fun eligible(home: String): Boolean =
        sessions.exists(INSTANCE, home) && !sessions.exists(INSTANCE, currentOrigin)

    private fun sameAuthority(expected: StoredSession, current: StoredSession?): Boolean =
        current != null && current.namespace == expected.namespace && current.credential == expected.credential &&
            current.deviceId == expected.deviceId && current.sessionId == expected.sessionId

    private fun current() = OriginMoveResolution(currentOrigin, currentOrigin, false)
    private fun serverOrigin(home: String, record: OriginMoveRecord?): String =
        movedServer(home, record) ?: if (home == process.initial.homeOrigin) process.initial.serverOrigin else home

    private fun movedServer(home: String, record: OriginMoveRecord?): String? =
        record?.takeIf { it.state == OriginMoveState.MOVED && it.home == home }?.server

    private class ProcessOrigins(val initial: OriginMoveResolution, @Volatile var record: OriginMoveRecord?)

    private companion object {
        const val INSTANCE = "native"
        const val RETRY_INTERVAL_MS = 15 * 60 * 1_000L
    }
}

fun decodeOriginMoveRecord(bytes: ByteArray, allowInsecureLoopback: Boolean = false): OriginMoveRecord? =
    runCatching { NativeJson.decodeFromString<OriginMoveRecord>(bytes.decodeToString()) }
        .getOrNull()?.takeIf { validRecord(it, allowInsecureLoopback) }

private fun validRecord(record: OriginMoveRecord, allowInsecureLoopback: Boolean): Boolean = runCatching {
    require(canonicalOrigin(record.home, allowInsecureLoopback) == record.home)
    require(canonicalOrigin(record.server, allowInsecureLoopback) == record.server)
    require(record.home != record.server)
    require(uuid(record.instanceId) != null)
    Instant.parse(record.decidedAt)
}.isSuccess

@OptIn(ExperimentalUuidApi::class)
private fun uuid(value: String): String? = runCatching { Uuid.parse(value).toString() }.getOrNull()
