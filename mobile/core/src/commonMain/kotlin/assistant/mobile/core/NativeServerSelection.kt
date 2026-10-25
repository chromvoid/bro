package assistant.mobile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class NativeServerKind {
    @SerialName("default") DEFAULT,
    @SerialName("custom") CUSTOM,
}

/** Non-secret installation metadata; credentials remain in the selected origin's session store. */
@Serializable
data class NativeServerSelection(
    val kind: NativeServerKind,
    val origin: String,
    @SerialName("instance_id") val instanceId: String,
    val name: String,
    @SerialName("selected_at") val selectedAt: String,
)

interface NativeServerSelectionStore {
    /** An absent, unreadable or malformed record is null, selecting the build's default server. */
    fun read(): NativeServerSelection?
    /** Atomically replaces the complete record, or throws without committing it. */
    fun write(selection: NativeServerSelection)
}

fun nativeServerSelection(
    server: NativeServerCheckResult.Compatible,
    defaultOrigin: String,
    selectedAt: String = Clock.System.now().toString(),
    allowInsecureLoopback: Boolean = false,
): NativeServerSelection {
    val origin = canonicalOrigin(server.origin, allowInsecureLoopback)
    val kind = if (origin == canonicalOrigin(defaultOrigin, allowInsecureLoopback)) NativeServerKind.DEFAULT
        else NativeServerKind.CUSTOM
    return NativeServerSelection(kind, origin, server.instanceId, server.name, selectedAt)
        .also { validateNativeServerSelection(it, allowInsecureLoopback) }
}

fun decodeNativeServerSelection(bytes: ByteArray, allowInsecureLoopback: Boolean = false): NativeServerSelection? =
    runCatching {
        NativeJson.decodeFromString<NativeServerSelection>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
            validateNativeServerSelection(it, allowInsecureLoopback)
        }
    }.getOrNull()

@OptIn(ExperimentalUuidApi::class)
fun validateNativeServerSelection(selection: NativeServerSelection, allowInsecureLoopback: Boolean = false) {
    require(canonicalOrigin(selection.origin, allowInsecureLoopback) == selection.origin)
    Uuid.parse(selection.instanceId)
    require(selection.name.length in 1..120)
    Instant.parse(selection.selectedAt)
}

/** A checked server and its one-time material stay together in process memory. */
class NativeCheckedServer(val target: NativeServerCheckResult.Compatible, val invite: AuthLink?)

data class NativeServerSelectionState(
    val selected: NativeCheckedServer? = null,
    val confirmation: NativeCheckedServer? = null,
    val busy: Boolean = false,
    val error: LocalizedString? = null,
)

/** Coordinates only the transient server form; switching itself owns permissions and erasure. */
class NativeServerSelectionController(
    private val scope: CoroutineScope,
    private val currentOrigin: String,
    private val check: suspend (String) -> NativeServerCheckResult,
    private val switchServer: suspend (NativeServerCheckResult.Compatible, AuthLink?) -> NativeServerSwitchResult,
    private val onRedeem: (AuthLink) -> Unit,
    private val onClose: () -> Unit,
) {
    private val mutableState = MutableStateFlow(NativeServerSelectionState())
    val state = mutableState.asStateFlow()
    private var probeJob: Job? = null
    private var probeRevision = 0L

    fun clear() {
        probeRevision++
        probeJob?.cancel()
        probeJob = null
        mutableState.value = NativeServerSelectionState()
    }

    fun close() = clear()

    fun clearError() { mutableState.update { it.copy(error = null) } }

    fun probe(origin: String, invite: AuthLink? = null) {
        clear()
        val revision = probeRevision
        mutableState.value = NativeServerSelectionState(busy = true)
        probeJob = scope.launch {
            val result = check(origin)
            // A platform callback may finish even after its coroutine has been cancelled.
            if (revision != probeRevision) return@launch
            mutableState.value = when (result) {
                is NativeServerCheckResult.Compatible -> NativeServerSelectionState(selected = NativeCheckedServer(result, invite))
                else -> NativeServerSelectionState(error = result.message())
            }
            probeJob = null
        }
    }

    fun accept(value: NativeAuthDestination) {
        if (value.origin == currentOrigin) {
            clear()
            onRedeem(value.link)
        } else probe(value.origin, value.link)
    }

    fun requestConnect(hasSession: Boolean, hasCurrentData: Boolean) {
        val form = state.value
        val selected = form.selected ?: return
        if (form.busy) return
        if (hasSession || hasCurrentData) mutableState.update { it.copy(confirmation = selected) }
        else scope.launch { connect(selected) }
    }

    fun confirmSwitch() {
        val selected = state.value.confirmation ?: return
        scope.launch { connect(selected) }
    }

    fun dismissConfirmation() { mutableState.update { it.copy(confirmation = null) } }

    private suspend fun connect(selected: NativeCheckedServer) {
        val form = state.value
        if (form.selected !== selected || form.busy) return
        mutableState.value = form.copy(confirmation = null, busy = true, error = null)
        when (switchServer(selected.target, selected.invite)) {
            NativeServerSwitchResult.NoOp -> {
                selected.invite?.let(onRedeem) ?: onClose()
                mutableState.update { it.copy(busy = false) }
            }
            NativeServerSwitchResult.FirmwareUpdateActive -> mutableState.update {
                it.copy(busy = false, error = GeneratedStrings.NativeServer.Errors.firmwareUpdateActive)
            }
            NativeServerSwitchResult.EraseFailed -> mutableState.update {
                it.copy(busy = false, error = GeneratedStrings.NativeServer.Errors.eraseFailed)
            }
            NativeServerSwitchResult.Restarting -> Unit
        }
    }
}

private fun NativeServerCheckResult.message(): LocalizedString = when (this) {
    NativeServerCheckResult.InvalidAddress -> GeneratedStrings.NativeServer.Errors.invalidAddress
    NativeServerCheckResult.Unreachable -> GeneratedStrings.NativeServer.Errors.unreachable
    NativeServerCheckResult.NotBro -> GeneratedStrings.NativeServer.Errors.notBro
    NativeServerCheckResult.Unsupported -> GeneratedStrings.NativeServer.Errors.unsupported
    NativeServerCheckResult.AppTooOld -> GeneratedStrings.NativeServer.Errors.appTooOld
    is NativeServerCheckResult.Compatible -> error("A compatible server has no error message")
}
