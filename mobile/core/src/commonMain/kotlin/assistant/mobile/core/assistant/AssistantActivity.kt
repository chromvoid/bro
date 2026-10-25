package assistant.mobile.core.assistant

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SseByteParser
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

@Serializable
enum class AssistantActivityStatus {
    @SerialName("active") Active,
    @SerialName("completed") Completed,
    @SerialName("failed") Failed,
    @SerialName("unavailable") Unavailable,
}

@Serializable
enum class AssistantActivityPhase {
    @SerialName("waiting_model") WaitingModel,
    @SerialName("thinking") Thinking,
    @SerialName("generating") Generating,
    @SerialName("tool") Tool,
    @SerialName("compacting") Compacting,
}

@Serializable
enum class AssistantActivitySource {
    @SerialName("assistant") Assistant,
    @SerialName("compaction") Compaction,
}

@Serializable
enum class AssistantActivityStepKind {
    @SerialName("model") Model,
    @SerialName("tool") Tool,
    @SerialName("compaction") Compaction,
}

@Serializable
enum class AssistantActivityStepStatus {
    @SerialName("running") Running,
    @SerialName("completed") Completed,
    @SerialName("failed") Failed,
}

@Serializable
data class AssistantActivityStep(
    val id: String,
    val kind: AssistantActivityStepKind,
    val status: AssistantActivityStepStatus,
    val label: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("duration_ms") val durationMs: Double?,
) {
    init { require(durationMs == null || durationMs.isFinite() && durationMs >= 0) }
}

/** A server-authorized durable projection, independently of disposable model text. */
@Serializable
data class AssistantConfirmation(val id: String, val service: String, val text: String) {
    init {
        require(id.isNotEmpty() && id.length <= 200)
        require(service.length <= 100 && text.length <= 32_768)
    }
}

/** Complete snapshots replace text. These objects never enter the history cache or journal. */
@Serializable
data class AssistantActivitySnapshot(
    @SerialName("message_id") val messageId: String,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val seq: Long,
    val status: AssistantActivityStatus,
    val phase: AssistantActivityPhase? = null,
    @SerialName("preview_text") val previewText: String,
    val source: AssistantActivitySource? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("elapsed_ms") val elapsedMs: Double,
    @SerialName("model_call") val modelCall: Int,
    val attempt: Int,
    val steps: List<AssistantActivityStep>,
    val usage: JsonElement? = null,
    // Missing means an older server; [] explicitly removes the prior authorized projection.
    val confirmed: List<AssistantConfirmation>? = null,
) {
    init {
        require(seq >= 0 && modelCall >= 0 && attempt >= 0)
        require(elapsedMs.isFinite() && elapsedMs >= 0)
        require(previewText.length <= 128_000 && steps.size <= 128)
        require(confirmed == null || confirmed.size <= 128)
    }
}

enum class AssistantActivityConnection {
    Connecting, Connected, Reconnecting, Offline, Ended, Unavailable, Denied,
}

data class AssistantActivityState(
    val messageId: String,
    val snapshot: AssistantActivitySnapshot? = null,
    val preview: String = "",
    val confirmed: List<AssistantConfirmation> = emptyList(),
    val connection: AssistantActivityConnection = AssistantActivityConnection.Connecting,
) {
    val terminal: Boolean get() = snapshot?.status == AssistantActivityStatus.Completed ||
        snapshot?.status == AssistantActivityStatus.Failed
}

data class AssistantActivityTransition(
    val state: AssistantActivityState,
    val accepted: Boolean,
    val rereadDurable: Boolean = false,
)

/** The reducer knows run/sequence identity, never guesses a receipt from preview text. */
class AssistantActivityReducer(private val messageId: String) {
    var state: AssistantActivityState = AssistantActivityState(messageId)
        private set
    private var run: String? = null
    private var sequence = -1L
    private var confirmations: List<AssistantConfirmation>? = null
    private val retired = mutableSetOf<String>()
    private var ended = false

    fun connection(value: AssistantActivityConnection): AssistantActivityState {
        val clear = value in setOf(
            AssistantActivityConnection.Connecting, AssistantActivityConnection.Reconnecting,
            AssistantActivityConnection.Offline, AssistantActivityConnection.Unavailable,
            AssistantActivityConnection.Denied,
        )
        state = state.copy(
            connection = value,
            snapshot = if (clear) null else state.snapshot,
            preview = if (clear) "" else state.preview,
            confirmed = if (value == AssistantActivityConnection.Denied) emptyList() else state.confirmed,
        )
        return state
    }

    fun apply(value: AssistantActivitySnapshot, firstInConnection: Boolean = false): AssistantActivityTransition {
        fun ignored() = AssistantActivityTransition(state, accepted = false)
        if (ended || value.messageId != messageId) return ignored()
        if (value.status == AssistantActivityStatus.Unavailable) {
            state = state.copy(confirmed = value.confirmed ?: state.confirmed)
            connection(AssistantActivityConnection.Unavailable)
            return AssistantActivityTransition(state, accepted = true)
        }
        if (value.status == AssistantActivityStatus.Active && value.runId.isNullOrEmpty()) return ignored()
        val nextRun = value.runId.orEmpty()
        if (nextRun in retired) return ignored()
        if (nextRun != run) {
            run?.let(retired::add)
            run = nextRun
            sequence = -1
        }
        if (value.seq < sequence || value.seq == sequence && !firstInConnection &&
            value.confirmed == confirmations) return ignored()
        sequence = value.seq
        confirmations = value.confirmed
        ended = value.status != AssistantActivityStatus.Active
        state = state.copy(
            snapshot = value,
            preview = if (value.status == AssistantActivityStatus.Completed) state.preview else publicAssistantPreview(value),
            confirmed = value.confirmed ?: state.confirmed,
            connection = if (ended) AssistantActivityConnection.Ended else AssistantActivityConnection.Connected,
        )
        return AssistantActivityTransition(state, accepted = true, rereadDurable = ended)
    }
}

fun publicAssistantPreview(value: AssistantActivitySnapshot): String =
    if (value.status == AssistantActivityStatus.Active && value.source == AssistantActivitySource.Assistant)
        value.previewText else ""

data class AssistantActivityPolicy(
    val idleMilliseconds: Long = 20_000,
    val maxAttempts: Int = 6,
    val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init { require(idleMilliseconds > 0 && maxAttempts in 1..6) }
}

/** Dedicated read-only GET. The caller cancels collection on visibility/message/lease changes. */
class AssistantActivityStream(
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val policy: AssistantActivityPolicy = AssistantActivityPolicy(),
) {
    suspend fun collect(
        lease: SessionLease,
        messageId: String,
        onState: (AssistantActivityState) -> Unit,
        onTerminal: suspend () -> Unit,
    ) {
        require(messageId.isNotEmpty())
        val reducer = AssistantActivityReducer(messageId)
        for (attempt in 0 until policy.maxAttempts) {
            if (!sessions.isCurrent(lease)) return
            onState(reducer.connection(if (attempt == 0) AssistantActivityConnection.Connecting
                else AssistantActivityConnection.Reconnecting))
            var terminal = false
            var notBefore = 0L
            try {
                val result = open(lease, messageId) { headers, channel ->
                    require(headers["content-type"]?.substringBefore(';')?.trim()
                        .equals("text/event-stream", ignoreCase = true))
                    read(channel, lease, reducer) { transition ->
                        onState(transition.state)
                        if (transition.rereadDurable) terminal = true
                    }
                }
                if (!sessions.isCurrent(lease)) return
                when (result) {
                    HttpOutcome.Unauthorized, HttpOutcome.Forbidden -> {
                        onState(reducer.connection(AssistantActivityConnection.Denied))
                        return
                    }
                    is HttpOutcome.Failure -> if (result.status in setOf(404, 410)) {
                        onState(reducer.connection(AssistantActivityConnection.Denied))
                        return
                    }
                    HttpOutcome.StaleSession -> return
                    is HttpOutcome.RateLimited -> notBefore = result.retryAtMillis
                    else -> Unit
                }
            } catch (_: TimeoutCancellationException) {
                // Any byte, including a heartbeat comment, resets the per-read idle timer.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed read only retries this GET; admission and durable recovery stay outside.
            }
            if (!sessions.isCurrent(lease)) return
            if (terminal) {
                onTerminal()
                return
            }
            if (attempt < policy.maxAttempts - 1) {
                onState(reducer.connection(AssistantActivityConnection.Reconnecting))
                delay(maxOf(1_000L shl attempt, notBefore - policy.nowMillis()))
            }
        }
        if (sessions.isCurrent(lease)) onState(reducer.connection(AssistantActivityConnection.Offline))
    }

    /** Header acquisition has the same idle bound as a body read, rather than HTTP's 45 s socket limit. */
    private suspend fun open(
        lease: SessionLease,
        messageId: String,
        consume: suspend (Map<String, String>, ByteReadChannel) -> Unit,
    ): HttpOutcome = coroutineScope {
        val headersArrived = CompletableDeferred<Unit>()
        val request = async {
            try {
                http.stream("/v1/conversation/messages/${messageId.encodeURLPathPart()}/activity", lease,
                    mapOf("Accept" to "text/event-stream", "Cache-Control" to "no-store")) { _, headers, channel ->
                    headersArrived.complete(Unit)
                    consume(headers, channel)
                }
            } finally {
                headersArrived.complete(Unit)
            }
        }
        try {
            withTimeout(policy.idleMilliseconds) { headersArrived.await() }
            request.await()
        } finally {
            request.cancel()
        }
    }

    private suspend fun read(
        channel: ByteReadChannel,
        lease: SessionLease,
        reducer: AssistantActivityReducer,
        onTransition: (AssistantActivityTransition) -> Unit,
    ) {
        // The web contract permits 128 confirmations plus 128k preview; UTF-8 frames exceed 64k.
        val parser = SseByteParser(maxLineBytes = 16_777_216, maxEventBytes = 16_777_216)
        val bytes = ByteArray(8_192)
        var first = true
        try {
            while (sessions.isCurrent(lease)) {
                val size = withTimeout(policy.idleMilliseconds) { channel.readAvailable(bytes) }
                if (size < 0) return
                for (event in parser.push(bytes, size)) {
                    if (!sessions.isCurrent(lease)) return
                    if (event.name != "activity") continue
                    val value = try {
                        NativeJson.decodeFromString<AssistantActivitySnapshot>(event.data)
                    } catch (_: IllegalArgumentException) { continue }
                    val transition = reducer.apply(value, firstInConnection = first)
                    if (!transition.accepted) continue
                    first = false
                    onTransition(transition)
                    if (transition.rereadDurable || value.status == AssistantActivityStatus.Unavailable) return
                }
            }
        } finally {
            channel.cancel(null)
        }
    }
}

/** Exact tool identities describe observed work, not guessed natural-language meaning. */
fun assistantStepLabel(step: AssistantActivityStep): LocalizedString = when (step.kind) {
    AssistantActivityStepKind.Model -> GeneratedStrings.Assistant.Activity.model
    AssistantActivityStepKind.Compaction -> GeneratedStrings.Assistant.Activity.compacting
    AssistantActivityStepKind.Tool -> when (step.label) {
        "nutrition.search", "nutrition.resolve" -> GeneratedStrings.Assistant.Progress.searchingFood
        "nutrition.packaged_search" -> GeneratedStrings.Assistant.Progress.searchingPackaged
        "nutrition.barcode" -> GeneratedStrings.Assistant.Progress.searchingBarcode
        "nutrition.web_search" -> GeneratedStrings.Assistant.Progress.searchingWeb
        "nutrition.estimate" -> GeneratedStrings.Assistant.Progress.calculating
        "nutrition.compare" -> GeneratedStrings.Assistant.Progress.comparingFood
        "nutrition.log", "nutrition.log_dish", "nutrition.log_composition", "nutrition.import_and_log" -> GeneratedStrings.Assistant.Progress.addingFood
        "nutrition.update", "nutrition.update_dish" -> GeneratedStrings.Assistant.Progress.editingFood
        "nutrition.delete", "nutrition.delete_dish" -> GeneratedStrings.Assistant.Progress.removingFood
        "nutrition.undo" -> GeneratedStrings.Assistant.Progress.undoingFood
        "nutrition.update_draft" -> GeneratedStrings.Assistant.Progress.editingDraft
        "nutrition.cancel_draft" -> GeneratedStrings.Assistant.Progress.cancellingDraft
        "nutrition.import" -> GeneratedStrings.Assistant.Progress.savingCatalog
        "nutrition.save_composition" -> GeneratedStrings.Assistant.Progress.savingComposition
        "nutrition.portion" -> GeneratedStrings.Assistant.Progress.savingPortion
        "nutrition.favorite" -> GeneratedStrings.Assistant.Progress.updatingFavorites
        "nutrition.day" -> GeneratedStrings.Assistant.Progress.readingDiary
        "nutrition.drafts", "nutrition.draft" -> GeneratedStrings.Assistant.Progress.readingDrafts
        "nutrition.compositions" -> GeneratedStrings.Assistant.Progress.readingCompositions
        "nutrition.profile" -> GeneratedStrings.Assistant.Progress.readingProfile
        "nutrition.profile_update" -> GeneratedStrings.Assistant.Progress.updatingProfile
        "nutrition.weight" -> GeneratedStrings.Assistant.Progress.savingWeight
        "nutrition.goal_propose" -> GeneratedStrings.Assistant.Progress.preparingGoal
        "assistant.context", "assistant.history_search", "assistant.history_read", "assistant.archive_read", "research.sources" -> GeneratedStrings.Assistant.Progress.reading
        "research.investigate" -> GeneratedStrings.Assistant.Progress.researching
        "research.quick_search" -> GeneratedStrings.Assistant.Progress.lookingUp
        "research.source_change" -> GeneratedStrings.Assistant.Progress.updatingSources
        "tasks.get", "tasks.list", "tasks.projects", "tasks.assistant_status" -> GeneratedStrings.Assistant.Progress.readingTasks
        "tasks.operate" -> GeneratedStrings.Assistant.Progress.changingTasks
        "tasks.delegate", "tasks.delegation_change" -> GeneratedStrings.Assistant.Progress.delegatingTask
        "tasks.answer" -> GeneratedStrings.Assistant.Progress.answeringTask
        "tasks.review_result" -> GeneratedStrings.Assistant.Progress.reviewingTaskResult
        "timers.list" -> GeneratedStrings.Assistant.Progress.readingTimers
        "timers.start" -> GeneratedStrings.Assistant.Progress.startingTimer
        "timers.remind" -> GeneratedStrings.Assistant.Progress.settingReminder
        "timers.cancel" -> GeneratedStrings.Assistant.Progress.cancellingTimer
        "timers.cancel_reminder" -> GeneratedStrings.Assistant.Progress.cancellingReminder
        "assistant.notifications" -> GeneratedStrings.Assistant.Progress.readingNotifications
        "assistant.notification_update" -> GeneratedStrings.Assistant.Progress.updatingNotifications
        "assistant.system_status" -> GeneratedStrings.Assistant.Progress.checkingSystem
        "assistant.confirm" -> GeneratedStrings.Assistant.Progress.applyingProposal
        "assistant.proposal_cancel" -> GeneratedStrings.Assistant.Progress.cancellingProposal
        "personal.body_history", "personal.resolved", "personal.caliper_history" -> GeneratedStrings.Assistant.Progress.readingPersonal
        "personal.sources", "personal.sync_status" -> GeneratedStrings.Assistant.Progress.checkingSources
        "personal.sync" -> GeneratedStrings.Assistant.Progress.syncingSource
        "personal.caliper_add", "personal.caliper_complete" -> GeneratedStrings.Assistant.Progress.savingMeasurement
        "personal.caliper_delete" -> GeneratedStrings.Assistant.Progress.removingMeasurement
        "weather.forecast" -> GeneratedStrings.Assistant.Progress.readingWeather
        "places.history" -> GeneratedStrings.Assistant.Progress.readingPlaces
        else -> GeneratedStrings.Assistant.Activity.tool
    }
}

fun assistantPhaseLabel(state: AssistantActivityState): LocalizedString {
    if (state.connection == AssistantActivityConnection.Ended) return GeneratedStrings.Assistant.Activity.verifying
    val snapshot = state.snapshot
    if (state.connection != AssistantActivityConnection.Connected || snapshot == null) return GeneratedStrings.Assistant.processing
    return when (snapshot.phase) {
        AssistantActivityPhase.Tool -> snapshot.steps.lastOrNull {
            it.kind == AssistantActivityStepKind.Tool && it.status == AssistantActivityStepStatus.Running
        }?.let(::assistantStepLabel) ?: GeneratedStrings.Assistant.Activity.tool
        AssistantActivityPhase.WaitingModel -> GeneratedStrings.Assistant.Activity.waiting_model
        AssistantActivityPhase.Thinking -> GeneratedStrings.Assistant.Activity.thinking
        AssistantActivityPhase.Generating -> GeneratedStrings.Assistant.Activity.generating
        AssistantActivityPhase.Compacting -> GeneratedStrings.Assistant.processing
        null -> GeneratedStrings.Assistant.processing
    }
}
