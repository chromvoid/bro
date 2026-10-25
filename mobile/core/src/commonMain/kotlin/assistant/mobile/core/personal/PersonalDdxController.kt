package assistant.mobile.core.personal

import assistant.mobile.core.AccountDrafts
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class PersonalDdxState(val namespace: AccountNamespace, val canWrite: Boolean,
    val login: PersonalDdxLogin? = null, val available: Boolean? = null, val canDisconnect: Boolean = false,
    val busy: Boolean = false, val checking: Boolean = false, val uncertain: Boolean = false,
    val retryStart: Boolean = false, val retryAt: String? = null, val errorCode: String? = null)

/** Enrollment never enters SubmissionCoordinator: only the public unfinished id is durable. */
class PersonalDdxController(
    private val scope: CoroutineScope, private val sessions: SessionManager, http: NativeHttpClient,
    private val drafts: AccountDrafts, private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val reader: PersonalDdxReader = NativePersonalDdxApi(http),
) {
    private val mutableState = MutableStateFlow<PersonalDdxState?>(null)
    val state: StateFlow<PersonalDdxState?> = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val owners = mutableMapOf<Any, SessionLease>()
    private var boundLease: SessionLease? = null
    private var pendingStart: PendingStart? = null
    private var restored = false
    private var actionVersion = 0L
    private val actions = Mutex()
    private val recoveryWrites = Mutex()

    private class PendingStart(val key: String, val bytes: ByteArray) {
        fun clear() = bytes.fill(0)
        override fun toString(): String = "PendingStart(redacted)"
    }

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, visible) { lease, identity, active -> Triple(lease, identity, active) }
                .collectLatest { (lease, identity, active) ->
                    bind(lease, identity)
                    if (!active || lease == null || state.value?.canWrite != true) return@collectLatest
                    refresh()
                    while (visible.value && owned(lease)) {
                        delay(2000)
                        if (state.value?.login?.active == true && state.value?.busy != true) refresh()
                    }
                }
        }
    }
    fun activate(owner: Any = DEFAULT_OWNER) {
        bind(sessions.lease(), sessions.identity.value)
        val lease = boundLease ?: return
        owners[owner] = lease
        val wasVisible = visible.value
        visible.value = true
        if (wasVisible) scope.launch { refresh() }
    }
    fun deactivate(owner: Any = DEFAULT_OWNER) {
        owners.remove(owner); visible.value = owners.values.any { it === boundLease }
        if (!visible.value) { pendingStart?.clear(); pendingStart = null }
    }
    suspend fun invalidate(invalidation: EventInvalidation) {
        if (invalidation is EventInvalidation.Domains && EventDomain.Personal !in invalidation.values) return
        if (visible.value) refresh()
    }
    suspend fun refresh() {
        val lease = boundLease ?: return
        if (!owned(lease) || state.value?.canWrite != true || state.value?.checking == true || state.value?.busy == true) return
        val version = actionVersion
        change(lease) { copy(checking = true) }
        try {
            var recoveredId: String? = null
            if (!restored) {
                val stored = drafts.read(lease, RECOVERY)
                recoveredId = stored?.json?.let { personalObject(it).sText("login_id") }
                if (owned(lease)) restored = true
            }
            val status = reader.status(lease)
            if (!owned(lease) || version != actionVersion) return
            change(lease) { copy(available = status.available, canDisconnect = status.canDisconnect) }
            val pending = pendingStart
            when {
                pending != null && status.activeLogin?.requestKey == pending.key -> accept(lease, status.activeLogin)
                pending != null -> change(lease) { copy(retryStart = true) }
                recoveredId != null -> {
                    val next = reader.login(recoveredId, lease)
                    if (owned(lease) && version == actionVersion) accept(lease, next)
                }
                status.activeLogin != null -> accept(lease, status.activeLogin)
                state.value?.login != null || recoveredId != null -> {
                    val id = state.value?.login?.id ?: requireNotNull(recoveredId)
                    val next = reader.login(id, lease)
                    if (owned(lease) && version == actionVersion) accept(lease, next)
                }
                else -> change(lease) { copy(uncertain = false, retryStart = false) }
            }
            if (owned(lease) && version == actionVersion) change(lease) { copy(errorCode = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (version == actionVersion) change(lease) { copy(errorCode = "network_error") } }
        finally { if (version == actionVersion) change(lease) { copy(checking = false) } }
    }

    suspend fun start(mode: String, phone: String, sourceId: String? = null, revision: Long? = null) = action { lease ->
        val before = state.value ?: return@action
        if (before.login?.active == true || before.uncertain || before.available != true) return@action
        if (waiting(before.retryAt)) { change(lease) { copy(errorCode = "resend_cooldown") }; return@action }
        val normalized = personalDdxPhone(phone)
        if (normalized == null) { change(lease) { copy(errorCode = "invalid_phone") }; return@action }
        require(mode in setOf("connect", "reconnect", "replace"))
        require(mode == "connect" || (sourceId != null && revision != null && revision >= 0))
        val bytes = buildJsonObject {
            put("phone", normalized); put("mode", mode)
            if (sourceId != null) { put("source_id", sourceId); put("expected_revision", requireNotNull(revision)) }
        }.toString().encodeToByteArray()
        pendingStart?.clear()
        val pending = PendingStart(newKey(), bytes)
        pendingStart = pending
        post(lease, "/v1/personal/ddx/logins", bytes, pending.key, clearBytes = false)
    }
    suspend fun repeatStart() = action { lease ->
        val pending = pendingStart ?: return@action
        if (state.value?.retryStart != true) return@action
        change(lease) { copy(retryStart = false) }
        post(lease, "/v1/personal/ddx/logins", pending.bytes, pending.key, clearBytes = false)
    }
    suspend fun submitCode(code: String) = action { lease ->
        val before = state.value ?: return@action
        val login = before.login ?: return@action
        if (login.state != "waiting_code" || before.uncertain) return@action
        val normalized = personalDdxCode(code)
        if (normalized == null) { change(lease) { copy(errorCode = "invalid_verification_code") }; return@action }
        val bytes = buildJsonObject { put("code", normalized) }.toString().encodeToByteArray()
        post(lease, "/v1/personal/ddx/logins/${login.id.encodeURLParameter()}/code", bytes)
    }
    suspend fun resend() = loginAction("resend")
    suspend fun cancel() = loginAction("cancel")
    private suspend fun loginAction(kind: String) = action { lease ->
        val before = state.value ?: return@action
        val login = before.login ?: return@action
        if (kind == "resend" && (before.uncertain || !login.canResend || waiting(before.retryAt))) return@action
        post(lease, "/v1/personal/ddx/logins/${login.id.encodeURLParameter()}/$kind", "{}".encodeToByteArray())
    }
    suspend fun disconnect(sourceId: String, revision: Long) = action { lease ->
        if (state.value?.canDisconnect != true || state.value?.login?.active == true) return@action
        try {
            reader.disconnect(sourceId, revision, newKey(), lease)
            change(lease) { copy(errorCode = null, uncertain = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failed(lease, failure); change(lease) { copy(uncertain = false) } }
    }
    fun clear() {
        val lease = boundLease ?: return
        val before = state.value ?: return
        if (!owned(lease) || before.login?.active == true || before.busy || before.uncertain) return
        actionVersion++
        pendingStart?.clear(); pendingStart = null
        change(lease) { copy(login = null, errorCode = null, retryStart = false, checking = false) }
    }

    private suspend fun action(block: suspend (SessionLease) -> Unit) {
        val lease = boundLease ?: return
        // A second tap cannot queue a credential after the originating action or account changed.
        if (state.value?.busy == true) return
        scope.async { actions.withLock {
            if (!owned(lease) || state.value?.canWrite != true) return@withLock
            actionVersion++
            change(lease) { copy(busy = true, checking = false, errorCode = null) }
            try { block(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(lease, failure) }
            finally { change(lease) { copy(busy = false) } }
        } }.await()
    }
    private suspend fun post(lease: SessionLease, path: String, bytes: ByteArray, key: String = newKey(), clearBytes: Boolean = true) {
        try {
            val login = reader.postLogin(path, bytes, key, lease)
            if (owned(lease)) accept(lease, login)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            failed(lease, failure)
            if (state.value?.uncertain != true) { pendingStart?.clear(); pendingStart = null }
        } finally { if (clearBytes) bytes.fill(0) }
    }
    private suspend fun accept(lease: SessionLease, login: PersonalDdxLogin) {
        if (!owned(lease)) return
        pendingStart?.clear(); pendingStart = null
        change(lease) { copy(login = login, retryAt = login.resendAt ?: retryAt, uncertain = false, retryStart = false, errorCode = null) }
        recoveryWrites.withLock {
            if (!owned(lease) || state.value?.login != login) return@withLock
            if (login.active) drafts.save(lease, RECOVERY,
                buildJsonObject { put("login_id", login.id) }.toString().encodeToByteArray(), 0)
            else drafts.delete(lease, RECOVERY)
        }
    }
    private fun failed(lease: SessionLease, failure: Exception) {
        val outcome = (failure as? PersonalReadException)?.outcome
        val uncertain = outcome == null || outcome in setOf(HttpOutcome.NetworkFailure, HttpOutcome.InvalidResponse)
        val code = when (outcome) {
            is HttpOutcome.Failure -> runCatching { personalObject(outcome.body).sText("error") }.getOrNull()
            is HttpOutcome.Conflict -> runCatching { personalObject(outcome.body).sText("error") }.getOrNull()
            is HttpOutcome.RateLimited -> "resend_cooldown"
            HttpOutcome.Forbidden -> "request_failed"
            else -> if (uncertain) "network_error" else "request_failed"
        }
        change(lease) { copy(errorCode = code?.takeIf { it in DDX_ERRORS } ?: "request_failed", uncertain = uncertain) }
    }
    private fun waiting(until: String?): Boolean = until?.let { runCatching { Instant.parse(it).toEpochMilliseconds() > clockMs() }.getOrDefault(false) } ?: false
    private fun bind(lease: SessionLease?, identity: NativeIdentity?) {
        if (lease == null || identity == null || identity.id != lease.namespace.userId) {
            pendingStart?.clear(); pendingStart = null; boundLease = null; mutableState.value = null
            owners.clear(); visible.value = false; actionVersion++; restored = false; return
        }
        val changed = boundLease !== lease
        val canWrite = "personal.write" in identity.scopes
        if (changed || !canWrite) {
            pendingStart?.clear(); pendingStart = null; actionVersion++; restored = false
            if (changed) { owners.entries.removeAll { it.value !== lease }; visible.value = owners.isNotEmpty() }
        }
        boundLease = lease
        mutableState.update { previous ->
            val before = if (changed || !canWrite) null else previous
            (before ?: PersonalDdxState(lease.namespace, canWrite)).copy(canWrite = canWrite)
        }
    }
    private fun owned(lease: SessionLease): Boolean = boundLease === lease && sessions.isCurrent(lease) &&
        "personal.write" in (sessions.identity.value?.scopes ?: emptyList())
    private fun change(lease: SessionLease, update: PersonalDdxState.() -> PersonalDdxState) {
        if (owned(lease)) mutableState.update { it?.update() }
    }
    @OptIn(ExperimentalUuidApi::class)
    private fun newKey(): String = Uuid.random().toString()
    private companion object {
        val DEFAULT_OWNER = Any()
        const val RECOVERY = "personal/ddx-login"
        val DDX_ERRORS = setOf("network_error", "request_failed", "invalid_phone", "invalid_verification_code", "resend_cooldown",
            "connection_unavailable", "invalid_request", "login_not_found", "login_expired", "login_interrupted",
            "invalid_code", "invalid_credentials", "authentication_required", "auth_required", "phone_mismatch", "stale_revision",
            "login_in_progress", "verification_in_progress", "too_many_attempts", "provider_error", "provider_unavailable",
            "push_failed", "code_rejected", "resend_limit", "expired", "cancelled", "request_changed", "already_connected",
            "connection_changed", "account_mismatch", "same_account", "rate_limited", "login_unavailable")
    }
}
