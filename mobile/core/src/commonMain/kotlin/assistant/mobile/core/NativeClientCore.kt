package assistant.mobile.core

import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.JournalStore
import assistant.mobile.core.journal.StaleJournalSession
import assistant.mobile.core.journal.SubmissionCoordinator
import assistant.mobile.core.journal.SubmissionReceiptValidator
import assistant.mobile.core.tasks.TASK_OPERATIONS
import assistant.mobile.core.tasks.validateTaskSubmissionReceipt
import assistant.mobile.core.timers.TIMER_OPERATIONS
import assistant.mobile.core.timers.validateTimerSubmissionReceipt
import assistant.mobile.core.food.FOOD_OPERATIONS
import assistant.mobile.core.food.validateFoodSubmissionReceipt
import assistant.mobile.core.personal.PERSONAL_OPERATIONS
import assistant.mobile.core.personal.validatePersonalSubmissionReceipt
import assistant.mobile.core.sharing.isSharingResourceEndpoint
import assistant.mobile.core.sharing.validateSharingSubmissionReceipt
import assistant.mobile.core.challenges.isChallengeWriteEndpoint
import assistant.mobile.core.challenges.validateChallengeSubmissionReceipt
import assistant.mobile.core.circle.isCircleMessageEndpoint
import assistant.mobile.core.circle.validateCircleSubmissionReceipt
import assistant.mobile.core.activity.ACTIVITY_OPERATIONS
import assistant.mobile.core.activity.validateActivitySubmissionReceipt
import assistant.mobile.core.activity.validateActivityStepsSubmissionReceipt
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** Section repositories supply authoritative reads; the shared stream only invalidates them. */
interface NativeClientData {
    suspend fun refetchAll(lease: SessionLease)
    suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation)
}

/**
 * One graph per installation/home origin. It binds mandatory logout erasure, recovery, expiry and the
 * single foreground event owner; construction itself does not authenticate or send requests.
 */
class NativeClientCore(
    scope: CoroutineScope,
    instance: String,
    origin: String,
    secretStore: SessionSecretStore,
    private val journal: JournalStore,
    private val engine: HttpClientEngine,
    data: NativeClientData,
    liveSockets: LiveSocketFactory,
    clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    allowInsecureLoopback: Boolean = false,
    clearCachedAccount: suspend (AccountNamespace) -> Unit = {},
    serverOrigin: () -> String = { origin },
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val lifetime = CoroutineScope(scope.coroutineContext + job)
    val sessions = SessionManager(
        instance, origin, secretStore, { namespace ->
            try { journal.clearAccount(namespace) } finally { clearCachedAccount(namespace) }
        }, clockMs, allowInsecureLoopback,
    )
    val http = NativeHttpClient(engine, origin, sessions, clockMs, allowInsecureLoopback, serverOrigin)
    val submissions = SubmissionCoordinator(journal, http, sessions, clockMs,
        validateReceipt = SubmissionReceiptValidator { entry, response ->
            if (entry.endpoint == FOOD_OPERATIONS) validateFoodSubmissionReceipt(entry, response)
            if (entry.endpoint == TASK_OPERATIONS) validateTaskSubmissionReceipt(entry, response)
            if (entry.endpoint == TIMER_OPERATIONS) validateTimerSubmissionReceipt(entry, response)
            if (entry.endpoint == PERSONAL_OPERATIONS && !validateActivityStepsSubmissionReceipt(entry, response)) {
                validatePersonalSubmissionReceipt(entry, response)
            }
            if (entry.endpoint == ACTIVITY_OPERATIONS) validateActivitySubmissionReceipt(entry, response)
            if (isSharingResourceEndpoint(entry.endpoint)) validateSharingSubmissionReceipt(entry, response)
            if (isChallengeWriteEndpoint(entry.endpoint)) validateChallengeSubmissionReceipt(entry, response)
            if (isCircleMessageEndpoint(entry.endpoint)) validateCircleSubmissionReceipt(entry, response)
        },
    )
    val drafts = AccountDrafts(journal, sessions, clockMs)
    val live = LiveConnection(lifetime, sessions, liveSockets)
    val events = AccountEvents(
        lifetime, sessions, http, JournalEventCursorStore(journal),
        object : AccountEventEffects {
            override suspend fun refetchAll(lease: SessionLease) = data.refetchAll(lease)
            override suspend fun recover(lease: SessionLease) {
                submissions.recover(lease)
            }
            override suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation) =
                data.invalidate(lease, invalidation)
        },
        EventStreamPolicy(nowMillis = clockMs),
    )

    init {
        lifetime.launch {
            combine(sessions.leases, sessions.identity) { lease, identity -> lease to identity }
                .collectLatest { (lease, identity) ->
                    if (lease == null || identity == null) return@collectLatest
                    val expiry = runCatching { Instant.parse(identity.sessionExpiresAt).toEpochMilliseconds() }
                        .getOrDefault(clockMs())
                    while (sessions.isCurrent(lease)) {
                        val remaining = expiry - clockMs()
                        if (remaining <= 0) {
                            sessions.expire(lease)
                            return@collectLatest
                        }
                        delay(remaining)
                    }
                }
        }
    }

    suspend fun restore(): SessionResult = sessions.restore()

    /** Close the old authority and erase every account in its origin before selecting another. */
    suspend fun clearForServerSwitch(): SessionRevocation? = sessions.clearForServerSwitch {
        journal.clearOrigin(sessions.instance, sessions.origin)
    }

    fun setForeground(foreground: Boolean) {
        live.setForeground(foreground)
        events.setForeground(foreground)
    }

    suspend fun close() {
        live.close()
        events.close()
        lifetime.cancel()
        job.join()
        http.close()
        engine.close()
    }
}

/** Draft access shares the session's mutation gate, so logout cannot race a late disk write. */
class AccountDrafts(
    private val journal: JournalStore,
    private val sessions: SessionManager,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun read(lease: SessionLease, target: String): Draft? {
        if (!sessions.isCurrent(lease)) throw StaleJournalSession()
        val value = journal.draft(lease.namespace, target)
        if (!sessions.isCurrent(lease)) throw StaleJournalSession()
        return value
    }

    suspend fun save(lease: SessionLease, target: String, json: ByteArray, revision: Long) {
        require(revision >= 0)
        sessions.withLease(lease) {
            journal.saveDraft(Draft(lease.namespace, target, json.copyOf(), revision, clockMs()))
            true
        } ?: throw StaleJournalSession()
    }

    suspend fun delete(lease: SessionLease, target: String) {
        sessions.withLease(lease) {
            journal.deleteDraft(lease.namespace, target)
            true
        } ?: throw StaleJournalSession()
    }
}
