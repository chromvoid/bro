package assistant.mobile.core.speech

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.nativeError
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Settings use immediate, guarded requests: the API has revisions but no receipt lookup, and
 * connection credentials must never enter the command journal, drafts, diagnostics or a cache.
 * Failed writes are never repeated automatically; the owner explicitly reloads or sends again.
 */
class SpeechSettingsController(
    scope: CoroutineScope,
    private val sessions: SessionManager,
    private val http: NativeHttpClient,
    private val onInvalidateConnection: (String) -> Unit = {},
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val createId: () -> String = ::newSpeechConnectionId,
) {
    private val mutableState = MutableStateFlow<SpeechSettingsState?>(null)
    val state: StateFlow<SpeechSettingsState?> = mutableState.asStateFlow()
    private var boundLease: SessionLease? = null
    private var pendingVoiceChoice: String? = null

    init {
        scope.launch {
            sessions.leases.collect { lease ->
                if (lease !== boundLease) {
                    boundLease = lease
                    pendingVoiceChoice = null
                    mutableState.value = if (lease == null) null else SpeechSettingsState()
                }
            }
        }
    }

    suspend fun refresh() {
        val lease = bind() ?: return
        if (state.value?.loading == true || state.value?.busy == true) return
        update(lease) { copy(loading = true, error = null, editor = null) }
        try {
            val outcome = http.request("GET", endpoint, lease = lease)
            if (outcome !is HttpOutcome.Success) {
                update(lease) { copy(loading = false, error = safeError(outcome), retryAtMillis = outcome.retryAt()) }
                return
            }
            val snapshot = runCatching { decodeSpeechSettings(outcome.body) }.getOrNull()
            if (snapshot == null) {
                update(lease) { copy(loading = false, error = GeneratedStrings.Errors.server) }
                return
            }
            if (!sessions.isCurrent(lease)) return
            val previous = state.value?.snapshot
            previous?.connections?.forEach { old ->
                if (snapshot.connections.none { it.id == old.id && it.revision == old.revision && it.enabled && it.hasKey })
                    onInvalidateConnection(old.id)
            }
            pendingVoiceChoice = null
            update(lease) {
                copy(snapshot = snapshot, preferences = snapshot.preferences, loading = false, error = null,
                    retryAtMillis = null, catalogs = catalogs.filter { (id, catalog) ->
                        snapshot.connections.any { it.id == id && it.enabled && it.hasKey && it.revision == catalog.revision }
                    })
            }
            for (connection in snapshot.connections.filter { it.enabled && it.hasKey }) {
                if (!sessions.isCurrent(lease)) return
                val provider = snapshot.providers.find { it.id == connection.provider } ?: continue
                if (!provider.dynamicModels) update(lease) {
                    copy(catalogs = catalogs + (connection.id to SpeechCatalogState(connection.revision, SpeechCatalogPhase.Ready, provider.models)))
                } else if (state.value?.catalogs?.get(connection.id)?.phase !in setOf(SpeechCatalogPhase.Ready, SpeechCatalogPhase.Loading)) {
                    loadModels(lease, connection, refresh = false)
                }
            }
        } catch (cancelled: CancellationException) {
            update(lease) { copy(loading = false) }
            throw cancelled
        }
    }

    suspend fun refreshModels(id: String) {
        val lease = bind() ?: return
        val connection = state.value?.snapshot?.connections?.find { it.id == id && it.enabled && it.hasKey } ?: return
        if (state.value?.busy == true || state.value?.catalogs?.get(id)?.phase == SpeechCatalogPhase.Loading) return
        loadModels(lease, connection, refresh = true)
    }

    private suspend fun loadModels(lease: SessionLease, connection: SpeechConnection, refresh: Boolean) {
        if (!sessions.isCurrent(lease) || state.value?.snapshot?.connections?.none {
            it.id == connection.id && it.revision == connection.revision && it.enabled && it.hasKey
        } != false) return
        update(lease) { copy(catalogs = catalogs + (connection.id to SpeechCatalogState(connection.revision, SpeechCatalogPhase.Loading))) }
        try {
            val outcome = http.request("GET", "$endpoint/connections/${connection.id.encodeURLPathPart()}/models?capability=all${if (refresh) "&refresh=1" else ""}", lease = lease)
            val models = if (outcome is HttpOutcome.Success) runCatching {
                NativeJson.decodeFromString<SpeechModelCatalog>(outcome.body.decodeToString(throwOnInvalidSequence = true)).models
                    .also(::validateSpeechModels)
            }.getOrNull() else null
            update(lease) {
                if (snapshot?.connections?.none { it.id == connection.id && it.revision == connection.revision } != false) {
                    if (catalogs[connection.id]?.revision == connection.revision) copy(catalogs = catalogs - connection.id) else this
                }
                else copy(catalogs = catalogs + (connection.id to if (models != null) {
                    SpeechCatalogState(connection.revision, SpeechCatalogPhase.Ready, models)
                } else SpeechCatalogState(connection.revision, SpeechCatalogPhase.Failed,
                    error = if (outcome is HttpOutcome.Success) GeneratedStrings.SpeechSettings.modelsUnavailable else catalogError(outcome))),
                    retryAtMillis = outcome.retryAt())
            }
            settleVoice(lease)
        } catch (cancelled: CancellationException) {
            update(lease) { copy(catalogs = catalogs - connection.id) }
            throw cancelled
        }
    }

    fun updatePreferences(value: SpeechPreferences) = edit {
        val previous = preferences ?: return@edit this
        if (value.revision != previous.revision) return@edit this
        val changedVoiceSource = previous.tts != value.tts || previous.ttsModel != value.ttsModel
        if (changedVoiceSource) pendingVoiceChoice = value.tts
        copy(preferences = value).withPendingVoice()
    }

    fun openConnection(id: String? = null) = edit {
        val connection = id?.let { target -> personalConnections.find { it.id == target } }
        if (id != null && connection == null) return@edit this
        val provider = connection?.provider ?: providers.firstOrNull()?.id ?: return@edit this
        copy(editor = if (connection == null) SpeechConnectionEditor(createId(), provider = provider)
            else SpeechConnectionEditor(connection.id, connection.revision, connection.provider, connection.label, connection.enabled, connection.hasKey))
    }

    fun editConnectionProvider(value: String) = edit {
        copy(editor = editor?.let { if (it.revision == 0L && providers.any { provider -> provider.id == value }) it.copy(provider = value) else it })
    }
    fun editConnectionLabel(value: String) = edit { copy(editor = editor?.copy(label = value.take(100))) }
    fun editConnectionEnabled(value: Boolean) = edit { copy(editor = editor?.copy(enabled = value)) }
    fun closeConnection() {
        val lease = bind() ?: return
        update(lease) { copy(editor = null) }
    }

    /** The caller clears its non-saveable password field before invoking this request. */
    suspend fun saveConnection(apiKey: String) {
        val lease = bind() ?: return
        val form = state.value?.editor ?: return
        if (!canWrite()) return
        if (form.label.isBlank() || (!form.hasKey && apiKey.isBlank()) || state.value?.providers?.none { it.id == form.provider } != false) {
            update(lease) { copy(error = if (!form.hasKey && apiKey.isBlank()) GeneratedStrings.SpeechSettings.keyMissing else GeneratedStrings.Errors.invalid) }
            return
        }
        val replacesKey = apiKey.isNotBlank()
        val body = buildJsonObject {
            put("provider", form.provider)
            put("label", form.label.trim())
            put("enabled", form.enabled)
            put("revision", form.revision)
            if (replacesKey) put("api_key", apiKey.trim())
        }.toString().encodeToByteArray()
        write(lease, "PUT", "$endpoint/connections/${form.id.encodeURLPathPart()}", body,
            GeneratedStrings.SpeechSettings.saved) {
            if (replacesKey || !form.enabled) onInvalidateConnection(form.id)
        }
    }

    suspend fun deleteConnection() {
        val lease = bind() ?: return
        val form = state.value?.editor?.takeIf { it.revision > 0 } ?: return
        if (!canWrite()) return
        val body = buildJsonObject { put("revision", form.revision) }.toString().encodeToByteArray()
        write(lease, "DELETE", "$endpoint/connections/${form.id.encodeURLPathPart()}", body,
            GeneratedStrings.SpeechSettings.removed) { onInvalidateConnection(form.id) }
    }

    suspend fun savePreferences() {
        val lease = bind() ?: return
        val form = state.value ?: return
        val preferences = form.preferences ?: return
        if (!canWrite() || !form.validPreferences || !form.dirty) return
        val resolved = preferences.copy(asrModel = form.asrModel?.id,
            fallbackAsrModel = form.backupModel?.id.takeIf { preferences.fallbackAsr != null },
            ttsModel = form.ttsModel?.id.takeIf { preferences.tts != null })
        write(lease, "PUT", "$endpoint/preferences", NativeJson.encodeToString(resolved).encodeToByteArray(),
            GeneratedStrings.SpeechSettings.preferencesSaved)
    }

    private suspend fun write(
        lease: SessionLease, method: String, path: String, body: ByteArray,
        notice: LocalizedString, onSuccess: () -> Unit = {},
    ) {
        update(lease) { copy(busy = true, error = null, notice = null) }
        try {
            val outcome = http.request(method, path, body, lease = lease)
            if (!sessions.isCurrent(lease)) return
            if (outcome is HttpOutcome.Success) {
                onSuccess()
                update(lease) { copy(busy = false, editor = null, notice = notice) }
                refresh()
            } else update(lease) { copy(busy = false, error = safeError(outcome, write = true), retryAtMillis = outcome.retryAt()) }
        } catch (cancelled: CancellationException) {
            update(lease) { copy(busy = false, error = GeneratedStrings.SpeechSettings.unknown) }
            throw cancelled
        } finally { body.fill(0) }
    }

    private fun canWrite(): Boolean = state.value?.let {
        !it.busy && !it.loading && it.retryAtMillis?.let { deadline -> deadline > clockMs() } != true
    } == true

    private fun settleVoice(lease: SessionLease) = update(lease) { withPendingVoice() }
    private fun SpeechSettingsState.withPendingVoice(): SpeechSettingsState {
        val selection = preferences ?: return this
        if (pendingVoiceChoice == null || pendingVoiceChoice != selection.tts) return this
        val model = ttsModel ?: return this
        pendingVoiceChoice = null
        return if (selection.voice !in model.voices && model.defaultVoice in model.voices)
            copy(preferences = selection.copy(voice = requireNotNull(model.defaultVoice))) else this
    }

    private fun bind(): SessionLease? {
        val lease = sessions.lease() ?: return null
        if (boundLease !== lease) {
            boundLease = lease
            pendingVoiceChoice = null
            mutableState.value = SpeechSettingsState()
        }
        return lease
    }
    private fun update(lease: SessionLease, change: SpeechSettingsState.() -> SpeechSettingsState) {
        if (sessions.isCurrent(lease) && boundLease === lease) mutableState.update { it?.change() }
    }
    private fun edit(change: SpeechSettingsState.() -> SpeechSettingsState) {
        val lease = bind() ?: return
        if (state.value?.busy != false) return
        update(lease) { change().copy(error = null, notice = null) }
    }

    private companion object { const val endpoint = "/v1/me/speech" }
}

private fun HttpOutcome.retryAt(): Long? = (this as? HttpOutcome.RateLimited)?.retryAtMillis

/** Only stable error codes are interpreted; upstream messages may contain submitted credentials. */
private fun errorCode(outcome: HttpOutcome): String? {
    val bytes = when (outcome) {
        is HttpOutcome.Failure -> outcome.body
        is HttpOutcome.Conflict -> outcome.body
        else -> return null
    }
    return runCatching { NativeJson.parseToJsonElement(bytes.decodeToString()).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
}

private fun safeError(outcome: HttpOutcome, write: Boolean = false): LocalizedString = when {
    outcome is HttpOutcome.Conflict -> GeneratedStrings.SpeechSettings.changed
    errorCode(outcome) == "speech_key_unavailable" -> GeneratedStrings.SpeechSettings.keyStoreUnavailable
    write && outcome == HttpOutcome.NetworkFailure -> GeneratedStrings.SpeechSettings.unknown
    else -> nativeError(outcome)
}

private fun catalogError(outcome: HttpOutcome): LocalizedString = when (errorCode(outcome)) {
    "application_limit" -> GeneratedStrings.SpeechSettings.CatalogErrors.application_limit
    "provider_limit" -> GeneratedStrings.SpeechSettings.CatalogErrors.provider_limit
    "busy" -> GeneratedStrings.SpeechSettings.CatalogErrors.busy
    "timeout" -> GeneratedStrings.SpeechSettings.CatalogErrors.timeout
    "connection" -> GeneratedStrings.SpeechSettings.CatalogErrors.connection
    "secret_unavailable", "speech_key_unavailable" -> GeneratedStrings.SpeechSettings.CatalogErrors.secret_unavailable
    else -> GeneratedStrings.SpeechSettings.modelsUnavailable
}

@OptIn(ExperimentalUuidApi::class)
private fun newSpeechConnectionId(): String = Uuid.random().toString()
