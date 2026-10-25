package assistant.mobile.core.today

import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionManager
import assistant.mobile.core.activity.isActivityStepsSubmission
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class TodayAttentionEntry(val key: String, val label: LocalizedString, val requiredScope: String)

data class TodayAttentionState(
    val namespace: AccountNamespace? = null,
    val entries: List<TodayAttentionEntry> = emptyList(),
    val loading: Boolean = false,
    val error: LocalizedString? = null,
    val stale: Boolean = false,
)

/** Only durable unknown outcomes appear here; opening a row never resends its command. */
fun todayAttentionEntries(entries: List<Submission>, scopes: Set<String>): List<TodayAttentionEntry> =
    entries.filter { it.state == SubmissionState.UNCERTAIN }.mapNotNull { entry ->
        val (scope, label) = when (entry.endpoint.split('/').getOrNull(2)) {
            "conversation" -> "session.control" to GeneratedStrings.Today.Sections.conversation
            "nutrition" -> "nutrition.read" to GeneratedStrings.Today.Sections.nutrition
            "tasks" -> "tasks.read" to GeneratedStrings.Today.Sections.tasks
            "timers" -> "timers.read" to GeneratedStrings.Today.Sections.timers
            "personal" -> if (isActivityStepsSubmission(entry)) "activity.read" to GeneratedStrings.Nav.activity
                else "personal.read" to GeneratedStrings.Today.Sections.personal
            "activity" -> "activity.read" to GeneratedStrings.Nav.activity
            else -> return@mapNotNull null
        }
        if (scope in scopes) TodayAttentionEntry(entry.key, label, scope) else null
    }

/** Room owns journal observation, so background lookup and local receipts update the same card. */
class TodayAttentionController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val observe: (AccountNamespace) -> Flow<List<Submission>>,
) {
    private val active = MutableStateFlow(false)
    private val revision = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(TodayAttentionState())
    val state: StateFlow<TodayAttentionState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(sessions.leases, sessions.identity, active, revision) { lease, identity, visible, _ ->
                Triple(lease, identity, visible)
            }.collectLatest { (lease, identity, visible) ->
                if (lease == null || identity == null) {
                    mutableState.value = TodayAttentionState()
                    return@collectLatest
                }
                val sameAccount = mutableState.value.namespace == lease.namespace
                val previous = mutableState.value.takeIf { sameAccount } ?: TodayAttentionState(namespace = lease.namespace)
                val scopes = identity.scopes.toSet()
                // Revocation clears even a previously visible card before another read finishes.
                val permitted = previous.entries.filter { it.requiredScope in scopes }
                mutableState.value = previous.copy(entries = permitted, loading = visible, error = null,
                    stale = visible && permitted.isNotEmpty())
                if (!visible) return@collectLatest
                try {
                    observe(lease.namespace).collectLatest { entries ->
                        if (sessions.isCurrent(lease)) mutableState.value = TodayAttentionState(
                            lease.namespace, todayAttentionEntries(entries, scopes),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (sessions.isCurrent(lease)) mutableState.value = mutableState.value.copy(
                        loading = false, error = GeneratedStrings.Today.cardError,
                        stale = mutableState.value.entries.isNotEmpty(),
                    )
                }
            }
        }
    }

    fun activate() { active.value = true }
    fun deactivate() { active.value = false }
    fun refresh() { revision.value += 1 }
}
