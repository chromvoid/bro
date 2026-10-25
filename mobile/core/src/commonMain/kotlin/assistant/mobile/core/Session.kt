package assistant.mobile.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/** A lease is an in-memory authority snapshot, never a journal record. */
class SessionLease(val namespace: AccountNamespace, val generation: Long, val credential: String,
    val sessionId: String = "") {
    override fun toString(): String = "SessionLease(generation=$generation, credential=redacted)"
}

/** Captured before local erasure; the credential and its server address never change together. */
class SessionRevocation internal constructor(private val request: suspend () -> HttpOutcome) {
    suspend fun revoke(): HttpOutcome = request()
}

@Serializable
data class StoredSession(
    val namespace: AccountNamespace,
    val credential: String,
    val identity: NativeIdentity,
    val deviceId: String,
    val sessionId: String,
    val expiresAt: String,
) {
    override fun toString(): String = "StoredSession(redacted)"
}

/** Platform implementations protect the complete private record and exclude it from backup. */
interface SessionSecretStore {
    suspend fun load(instance: String, origin: String): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun delete(instance: String, origin: String)
}

sealed interface SessionResult {
    data class SignedIn(val identity: NativeIdentity) : SessionResult
    data class Rejected(val outcome: HttpOutcome) : SessionResult
    data object SignedOut : SessionResult
    data object StorageUnavailable : SessionResult
    data object InvalidReply : SessionResult
}

class SessionManager(
    val instance: String,
    origin: String,
    private val secretStore: SessionSecretStore,
    private val onLogout: suspend (AccountNamespace) -> Unit,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    allowInsecureLoopback: Boolean = false,
) {
    /** The installation's home namespace; HTTP routing may use a different server origin. */
    val origin: String = canonicalOrigin(origin, allowInsecureLoopback)
    private val lock = Mutex()
    private var generation = 0L
    private var serverSwitchStarted = false
    private var serverSwitchRevocation: SessionRevocation? = null
    private var stored: StoredSession? = null
    private var transport: NativeHttpClient? = null
    private val mutableLeases = MutableStateFlow<SessionLease?>(null)
    val leases: StateFlow<SessionLease?> = mutableLeases.asStateFlow()
    private val mutableIdentity = MutableStateFlow<NativeIdentity?>(null)
    val identity: StateFlow<NativeIdentity?> = mutableIdentity.asStateFlow()

    internal fun attach(client: NativeHttpClient) {
        require(client.origin == origin)
        check(transport == null) { "A session owns one HTTP transport" }
        transport = client
    }

    fun lease(): SessionLease? = mutableLeases.value
    fun isCurrent(lease: SessionLease): Boolean = mutableLeases.value === lease

    /** Only durable local writes belong here; network calls must stay outside this gate. */
    suspend fun <T> withLease(lease: SessionLease, block: suspend () -> T): T? = lock.withLock {
        if (!isCurrent(lease)) null else block()
    }

    suspend fun restore(): SessionResult = lock.withLock {
        if (serverSwitchStarted) return@withLock SessionResult.SignedOut
        generation++
        mutableLeases.value = null
        mutableIdentity.value = null
        stored = null
        val record = try { secretStore.load(instance, origin) } catch (error: Exception) {
            if (error is CancellationException) throw error
            return@withLock SessionResult.StorageUnavailable
        } ?: return@withLock SessionResult.SignedOut
        if (record.namespace.instance != instance || record.namespace.origin != origin ||
            record.namespace.userId != record.identity.id || !validSessionCredential(record.credential) ||
            record.identity.id.isBlank() || record.sessionId.isBlank() || record.deviceId.isBlank() ||
            record.deviceId != record.identity.deviceId ||
            runCatching { Instant.parse(record.expiresAt).toEpochMilliseconds() <= clockMs() }.getOrDefault(true)) {
            try { secretStore.delete(instance, origin) } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withLock SessionResult.StorageUnavailable
            }
            return@withLock SessionResult.SignedOut
        }
        activate(record)
        SessionResult.SignedIn(record.identity)
    }

    suspend fun login(login: String, password: String, deviceName: String = "Android"): SessionResult =
        authenticate("/v1/auth/native/login", buildJsonObject {
            put("login", login)
            put("password", password)
            put("device_name", deviceName)
        }.toString().encodeToByteArray())

    suspend fun redeem(
        token: String,
        password: String,
        login: String? = null,
        name: String? = null,
        timezone: String = "Europe/Moscow",
        deviceName: String = "Android",
    ): SessionResult = authenticate("/v1/auth/native/redeem", buildJsonObject {
        put("token", token)
        put("password", password)
        login?.let { put("login", it) }
        name?.let { put("name", it) }
        put("timezone", timezone)
        put("device_name", deviceName)
    }.toString().encodeToByteArray())

    private suspend fun authenticate(endpoint: String, bytes: ByteArray): SessionResult {
        val attempt = lock.withLock {
            if (serverSwitchStarted) return SessionResult.Rejected(HttpOutcome.StaleSession)
            generation++
            mutableLeases.value = null
            mutableIdentity.value = null
            stored = null
            try { secretStore.delete(instance, origin) } catch (error: Exception) {
                if (error is CancellationException) throw error
                return SessionResult.StorageUnavailable
            }
            generation
        }
        val reply = http().request("POST", endpoint, bytes, lease = null)
        if (generation != attempt) return SessionResult.Rejected(HttpOutcome.StaleSession)
        if (reply !is HttpOutcome.Success) return SessionResult.Rejected(reply)
        val decoded = try { decodeNativeSession(reply.body) } catch (_: Exception) {
            return SessionResult.InvalidReply
        }
        if (Instant.parse(decoded.expiresAt).toEpochMilliseconds() <= clockMs()) return SessionResult.InvalidReply
        val record = StoredSession(
            AccountNamespace(instance, origin, decoded.user.id), decoded.credential,
            decoded.user.copy(scopes = decoded.scopes), decoded.device.id, decoded.session.id, decoded.expiresAt,
        )
        return lock.withLock {
            if (generation != attempt) return@withLock SessionResult.Rejected(HttpOutcome.StaleSession)
            try { secretStore.save(record) } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withLock SessionResult.StorageUnavailable
            }
            activate(record)
            SessionResult.SignedIn(record.identity)
        }
    }

    /** Explicit logout purges local account data even when server revocation cannot be confirmed. */
    suspend fun logout(): HttpOutcome {
        var storageFailure = false
        val current = lock.withLock {
            val captured = lease() ?: return HttpOutcome.Unauthorized
            generation++
            mutableLeases.value = null
            mutableIdentity.value = null
            stored = null
            withContext(NonCancellable) {
                try { secretStore.delete(instance, origin) } catch (_: Exception) {
                    storageFailure = true
                }
                try { onLogout(captured.namespace) } catch (_: Exception) {
                    storageFailure = true
                }
            }
            captured
        }
        val response = http().revoke(current)
        return if (storageFailure) HttpOutcome.StorageUnavailable else response
    }

    /** Origin-wide erasure is required even after expiry removed the last account lease. */
    internal suspend fun clearForServerSwitch(clearOrigin: suspend () -> Unit): SessionRevocation? = lock.withLock {
        if (!serverSwitchStarted) {
            serverSwitchRevocation = lease()?.let { captured ->
                val client = http()
                val targetOrigin = client.serverOrigin
                SessionRevocation { client.revoke(captured, targetOrigin) }
            }
            serverSwitchStarted = true
        }
        generation++
        mutableLeases.value = null
        mutableIdentity.value = null
        stored = null
        withContext(NonCancellable) {
            try { secretStore.delete(instance, origin) } finally { clearOrigin() }
        }
        serverSwitchRevocation
    }

    /** Expiry/revocation closes private consumers; drafts and uncertain writes remain isolated. */
    suspend fun expire(lease: SessionLease) = lock.withLock {
        if (!isCurrent(lease)) return@withLock
        generation++
        mutableLeases.value = null
        mutableIdentity.value = null
        stored = null
        // A platform storage error cannot keep private consumers open or mask HTTP 401.
        withContext(NonCancellable) {
            try { secretStore.delete(instance, origin) } catch (_: Exception) { }
        }
    }

    suspend fun refreshIdentity(lease: SessionLease? = this.lease()): HttpOutcome {
        if (lease == null) return HttpOutcome.Unauthorized
        val reply = http().request("GET", "/v1/me", lease = lease, refreshOnForbidden = false)
        if (reply !is HttpOutcome.Success) return reply
        val refreshed = try { NativeJson.decodeFromString<NativeIdentity>(reply.body.decodeToString()) }
            catch (_: Exception) { return HttpOutcome.InvalidResponse }
        return lock.withLock {
            if (!isCurrent(lease)) return@withLock HttpOutcome.StaleSession
            if (refreshed.id != lease.namespace.userId || refreshed.deviceId != stored?.deviceId)
                return@withLock HttpOutcome.InvalidResponse
            if (runCatching { Instant.parse(refreshed.sessionExpiresAt).toEpochMilliseconds() <= clockMs() }.getOrDefault(true))
                return@withLock HttpOutcome.InvalidResponse
            val record = stored!!.copy(identity = refreshed, expiresAt = refreshed.sessionExpiresAt)
            try { secretStore.save(record) } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withLock HttpOutcome.StorageUnavailable
            }
            stored = record
            mutableIdentity.value = refreshed
            reply
        }
    }

    private fun activate(record: StoredSession) {
        stored = record
        mutableIdentity.value = record.identity
        mutableLeases.value = SessionLease(record.namespace, generation, record.credential, record.sessionId)
    }

    private fun http(): NativeHttpClient = checkNotNull(transport) { "Session HTTP transport is not attached" }
}
