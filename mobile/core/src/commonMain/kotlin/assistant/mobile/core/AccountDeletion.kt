package assistant.mobile.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class DeletionSuccessor(val id: String, val name: String)

@Serializable
data class DeletionSpace(val id: String, val name: String, val successors: List<DeletionSuccessor>)

@Serializable
data class AccountDeletionOptions(
    val spaces: List<DeletionSpace>,
    @SerialName("last_admin") val lastAdmin: Boolean,
    val warning: String? = null,
)

@Serializable
enum class DeletionStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
}

/** Stored only in the protected deletion store, never the command database. */
@Serializable
data class PendingAccountDeletion(
    val namespace: AccountNamespace,
    val credential: String,
    val key: String,
    val successors: Map<String, String>,
) {
    override fun toString(): String = "PendingAccountDeletion(redacted)"
}

@Serializable
data class AccountDeletionReceipt(
    @SerialName("deletion_id") val deletionId: String,
    val status: DeletionStatus,
    @SerialName("receipt_token") val receiptToken: String,
    @SerialName("status_url") val statusUrl: String,
) {
    override fun toString(): String = "AccountDeletionReceipt(status=$status, token=redacted)"
}

/** Atomic replacement discards the original session proof once a receipt has been obtained. */
@Serializable
data class StoredAccountDeletion(
    val namespace: AccountNamespace,
    val pending: PendingAccountDeletion? = null,
    val receipt: AccountDeletionReceipt? = null,
) {
    override fun toString(): String = "StoredAccountDeletion(redacted)"
}

/** Separate Keystore-protected storage survives person-session revocation and excludes backups. */
interface DeletionSecretStore {
    suspend fun load(instance: String, origin: String): StoredAccountDeletion?
    suspend fun save(record: StoredAccountDeletion)
    suspend fun delete(instance: String, origin: String)
}

data class AccountDeletionState(
    val namespace: AccountNamespace? = null,
    val options: AccountDeletionOptions? = null,
    val successors: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val status: DeletionStatus? = null,
    val hasRecovery: Boolean = false,
    val failure: HttpOutcome? = null,
)

@Serializable
private data class DeletionStatusReply(
    @SerialName("deletion_id") val deletionId: String,
    val status: DeletionStatus,
)

/**
 * Deletion has its own password-free write-ahead recovery proof. Replaying an empty password can
 * only recover the original admission, so it is safe after a lost response or session revocation.
 * No password, receipt token or original credential is exposed through presentation state.
 */
class AccountDeletionController(
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val secrets: DeletionSecretStore,
    private val onAccepted: suspend (AccountNamespace) -> Unit,
    private val createKey: () -> String = ::newDeletionKey,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AccountDeletionState())
    val state: StateFlow<AccountDeletionState> = mutableState.asStateFlow()
    private var stored: StoredAccountDeletion? = null
    private var optionsLease: SessionLease? = null
    private var cleanedNamespace: AccountNamespace? = null

    /** Invoke on startup and whenever the active account changes. Never sends a new deletion. */
    suspend fun restore() = operation {
        stored = null
        optionsLease = null
        mutableState.value = AccountDeletionState(busy = true)
        readStored()
        val record = stored ?: return@operation
        if (visible(record.namespace)) mutableState.value = mutableState.value.copy(
            namespace = record.namespace,
            status = record.receipt?.status,
            hasRecovery = record.pending != null,
        )
        // Cleanup is account-addressed and must finish even when another account hides this UI.
        if (record.receipt != null) withContext(NonCancellable) {
            accepted(record.namespace)
            if (record.receipt.status == DeletionStatus.COMPLETED) {
                secrets.delete(sessions.instance, sessions.origin)
                stored = null
            }
        }
    }

    suspend fun loadOptions() = operation {
        val lease = sessions.lease() ?: return@operation fail(HttpOutcome.Unauthorized)
        optionsLease = null
        mutableState.value = AccountDeletionState(namespace = lease.namespace, busy = true)
        readStored()
        mutableState.value = state.value.copy(
            hasRecovery = visibleRecord()?.pending != null,
            status = visibleRecord()?.receipt?.status,
            successors = visibleRecord()?.pending?.successors.orEmpty(),
        )
        val reply = http.request("GET", "/v1/me/account-deletion-options", lease = lease)
        if (!sessions.isCurrent(lease)) return@operation fail(HttpOutcome.StaleSession)
        if (reply !is HttpOutcome.Success) return@operation fail(reply)
        val options = try {
            NativeJson.decodeFromString<AccountDeletionOptions>(reply.body.decodeToString()).also {
                require(it.spaces.map(DeletionSpace::id).distinct().size == it.spaces.size)
                require(it.spaces.all { space -> validDeletionId(space.id) &&
                    space.successors.all { successor -> validDeletionId(successor.id) } })
            }
        } catch (_: Exception) { return@operation fail(HttpOutcome.InvalidResponse) }
        optionsLease = lease
        mutableState.value = mutableState.value.copy(options = options)
    }

    fun selectSuccessor(spaceId: String, userId: String?) {
        val current = state.value
        if (current.busy || current.hasRecovery || current.status != null) return
        if (optionsLease?.let(sessions::isCurrent) != true) return
        val space = current.options?.spaces?.singleOrNull { it.id == spaceId } ?: return
        if (userId != null && space.successors.none { it.id == userId }) return
        val choices = current.successors.toMutableMap()
        if (userId == null) choices.remove(spaceId) else choices[spaceId] = userId
        mutableState.value = current.copy(successors = choices, failure = null)
    }

    fun clearFailure() { mutableState.value = state.value.copy(failure = null) }

    fun acknowledgeCompleted() {
        if (!state.value.busy && state.value.status == DeletionStatus.COMPLETED)
            mutableState.value = AccountDeletionState()
    }

    /** The password is serialized into a transient request only, after durable proof is saved. */
    suspend fun submit(password: String) = operation {
        val lease = sessions.lease() ?: return@operation fail(HttpOutcome.Unauthorized)
        // Never interpret a failed/corrupt read as an empty store. A fresh key could otherwise
        // overwrite the only proof of an earlier request whose admission is still unknown.
        readStored()
        if (state.value.namespace != lease.namespace || optionsLease !== lease)
            return@operation fail(HttpOutcome.StaleSession)
        val options = state.value.options ?: return@operation fail(HttpOutcome.RejectedRequest)
        if (password.isEmpty()) return@operation fail(HttpOutcome.RejectedRequest)
        val previous = visibleRecord()
        if (previous?.receipt != null) return@operation fail(HttpOutcome.RejectedRequest)
        if (previous?.pending != null) {
            if (previous.pending.credential != lease.credential) return@operation fail(HttpOutcome.RejectedRequest)
            // Explicit re-confirmation of an unknown attempt uses its original identity and key.
            transmit(previous.pending, password, lease, initial = false)
            return@operation
        }
        val choices = state.value.successors.toMap()
        require(choices.all { (space, user) -> options.spaces.any {
            it.id == space && it.successors.any { successor -> successor.id == user }
        } })
        val proof = PendingAccountDeletion(lease.namespace, lease.credential, createKey(), choices)
        validatePending(proof)
        val record = StoredAccountDeletion(lease.namespace, pending = proof)
        // Storage failure blocks dispatch. There is never a password-bearing database entry.
        sessions.withLease(lease) { secrets.save(record); true }
            ?: return@operation fail(HttpOutcome.StaleSession)
        stored = record
        mutableState.value = state.value.copy(hasRecovery = true)
        transmit(proof, password, lease, initial = true)
    }

    /** The original key, choices and credential are reused, always with an empty password. */
    suspend fun recover() = operation {
        readStored()
        val proof = visibleRecord()?.pending ?: return@operation fail(HttpOutcome.RejectedRequest)
        val matchingLease = sessions.lease()?.takeIf {
            it.namespace == proof.namespace && it.credential == proof.credential
        }
        transmit(proof, "", matchingLease, initial = false)
    }

    suspend fun refreshStatus() = operation {
        readStored()
        val record = visibleRecord() ?: return@operation fail(HttpOutcome.RejectedRequest)
        val receipt = record.receipt ?: return@operation fail(HttpOutcome.RejectedRequest)
        val outcome = http.deletionStatus(receipt.deletionId, receipt.receiptToken)
        if (!visible(record.namespace)) return@operation
        if (outcome !is HttpOutcome.Success) return@operation fail(outcome)
        val reply = try {
            NativeJson.decodeFromString<DeletionStatusReply>(outcome.body.decodeToString()).also {
                require(it.deletionId == receipt.deletionId)
            }
        } catch (_: Exception) { return@operation fail(HttpOutcome.InvalidResponse) }
        val updated = record.copy(receipt = receipt.copy(status = reply.status))
        // A completed receipt still drives local cleanup after a storage failure/restart.
        if (reply.status == DeletionStatus.COMPLETED && cleanedNamespace == record.namespace) {
            secrets.delete(sessions.instance, sessions.origin)
            stored = null
        } else {
            secrets.save(updated)
            stored = updated
        }
        mutableState.value = state.value.copy(status = reply.status, hasRecovery = false)
    }

    private suspend fun transmit(proof: PendingAccountDeletion, password: String, lease: SessionLease?, initial: Boolean) {
        if (lease != null && !sessions.isCurrent(lease)) return fail(HttpOutcome.StaleSession)
        val body = buildJsonObject {
            put("password", password)
            put("space_successors", buildJsonObject {
                for ((space, successor) in proof.successors.entries.sortedBy { it.key }) put(space, successor)
            })
        }.toString().encodeToByteArray()
        val outcome = try {
            http.deleteAccount(SessionLease(proof.namespace, -1, proof.credential), body, proof.key)
        } finally { body.fill(0) }
        when (outcome) {
            is HttpOutcome.Success -> {
                val receipt = try {
                    NativeJson.decodeFromString<AccountDeletionReceipt>(outcome.body.decodeToString()).also(::validateReceipt)
                } catch (_: Exception) { return fail(HttpOutcome.InvalidResponse) }
                withContext(NonCancellable) {
                    // Retain pending proof if receipt persistence fails, so restart can recover it.
                    val record = StoredAccountDeletion(proof.namespace, receipt = receipt)
                    secrets.save(record)
                    stored = record
                    if (visible(proof.namespace)) mutableState.value = AccountDeletionState(
                        namespace = proof.namespace, busy = true, status = receipt.status,
                    )
                    accepted(proof.namespace)
                }
            }
            else -> {
                // Only an initial definite refusal releases the proof; recovery must never guess
                // that an earlier admission did not happen when the original response was lost.
                if (initial && (outcome == HttpOutcome.Forbidden || outcome is HttpOutcome.RateLimited ||
                    outcome is HttpOutcome.Failure && outcome.status in setOf(400, 404, 413, 415, 422))) {
                    secrets.delete(sessions.instance, sessions.origin)
                    stored = null
                    mutableState.value = state.value.copy(hasRecovery = false)
                }
                if (outcome == HttpOutcome.Unauthorized && lease != null && sessions.isCurrent(lease)) sessions.expire(lease)
                if (outcome == HttpOutcome.Forbidden && lease != null && sessions.isCurrent(lease)) sessions.refreshIdentity(lease)
                if (visible(proof.namespace)) fail(outcome)
            }
        }
    }

    private suspend fun operation(block: suspend () -> Unit) = lock.withLock {
        mutableState.value = state.value.copy(busy = true, failure = null)
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(HttpOutcome.StorageUnavailable) }
        finally { mutableState.value = state.value.copy(busy = false) }
    }

    private fun visibleRecord() = stored?.takeIf { visible(it.namespace) }
    private fun visible(namespace: AccountNamespace): Boolean = sessions.lease()?.namespace.let { it == null || it == namespace }
    private fun fail(outcome: HttpOutcome) { mutableState.value = state.value.copy(failure = outcome) }

    private suspend fun readStored() {
        val record = secrets.load(sessions.instance, sessions.origin)?.also(::validateStored)
        stored = record
    }

    private suspend fun accepted(namespace: AccountNamespace) {
        val current = sessions.lease()
        if (current?.namespace == namespace) sessions.expire(current)
        onAccepted(namespace)
        cleanedNamespace = namespace
    }

    private fun validateStored(record: StoredAccountDeletion) {
        require(record.namespace.instance == sessions.instance && record.namespace.origin == sessions.origin)
        require((record.pending == null) != (record.receipt == null))
        record.pending?.let { require(it.namespace == record.namespace); validatePending(it) }
        record.receipt?.let(::validateReceipt)
    }

    private fun validatePending(proof: PendingAccountDeletion) {
        require(validSessionCredential(proof.credential) && validDeletionId(proof.key))
        require(proof.successors.size <= 100 && proof.successors.all { (space, user) -> validDeletionId(space) && validDeletionId(user) })
    }

    private fun validateReceipt(receipt: AccountDeletionReceipt) {
        require(validDeletionId(receipt.deletionId) && validSessionCredential(receipt.receiptToken))
        require(receipt.statusUrl == "/v1/account-deletions/${receipt.deletionId}")
    }
}

@OptIn(ExperimentalUuidApi::class)
internal fun validDeletionId(value: String): Boolean = runCatching { Uuid.parse(value).toString() == value }.getOrDefault(false)

@OptIn(ExperimentalUuidApi::class)
private fun newDeletionKey(): String = Uuid.random().toString()
