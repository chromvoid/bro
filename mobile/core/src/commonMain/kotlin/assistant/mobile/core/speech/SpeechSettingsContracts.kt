package assistant.mobile.core.speech

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpeechModel(
    val id: String,
    val name: String,
    val capabilities: List<String>,
    @SerialName("asr_mode") val asrMode: String?,
    val voices: List<String>,
    @SerialName("default_voice") val defaultVoice: String?,
    @SerialName("tts_segment_chars") val ttsSegmentChars: Int?,
    val selectable: Boolean,
    @SerialName("unavailable_reason") val unavailableReason: String?,
) {
    fun usable(capability: String): Boolean = selectable && capability in capabilities && when (capability) {
        "asr" -> asrMode in setOf("streaming", "file")
        "tts" -> voices.isNotEmpty()
        else -> false
    }
}

@Serializable
data class SpeechProvider(
    val id: String,
    val label: String,
    val capabilities: List<String>,
    @SerialName("connection_kind") val connectionKind: String,
    @SerialName("dynamic_models") val dynamicModels: Boolean = false,
    val models: List<SpeechModel> = emptyList(),
)

@Serializable
data class SpeechConnection(
    val id: String,
    val ownership: String,
    val provider: String,
    val label: String,
    val enabled: Boolean,
    val revision: Long,
    @SerialName("has_key") val hasKey: Boolean,
    val capabilities: List<String>,
)

@Serializable
data class SpeechPreferences(
    val revision: Long,
    val asr: String,
    @SerialName("fallback_asr") val fallbackAsr: String?,
    val tts: String?,
    val voice: String,
    @SerialName("tts_model") val ttsModel: String? = null,
    @SerialName("asr_model") val asrModel: String? = null,
    @SerialName("fallback_asr_model") val fallbackAsrModel: String? = null,
)

@Serializable
data class SpeechReadiness(val state: String, val code: String? = null) {
    val message: LocalizedString? get() = when {
        state == "configured" -> null
        code == "not_configured" -> GeneratedStrings.SpeechSettings.notConfigured
        code in setOf("no_access", "access_denied") -> GeneratedStrings.SpeechSettings.noAccess
        code == "auth_required" -> GeneratedStrings.SpeechSettings.authRequired
        else -> GeneratedStrings.SpeechSettings.unavailable
    }
}

@Serializable
data class SpeechSettingsSnapshot(
    val providers: List<SpeechProvider>,
    val connections: List<SpeechConnection>,
    val preferences: SpeechPreferences,
    val asr: SpeechReadiness? = null,
    val tts: SpeechReadiness? = null,
)

@Serializable
internal data class SpeechModelCatalog(val models: List<SpeechModel>)

enum class SpeechCatalogPhase { Loading, Ready, Failed }

data class SpeechCatalogState(
    val revision: Long,
    val phase: SpeechCatalogPhase,
    val models: List<SpeechModel> = emptyList(),
    val error: LocalizedString? = null,
)

/** Only non-secret metadata belongs in a controller state; the password field is request-local. */
data class SpeechConnectionEditor(
    val id: String,
    val revision: Long = 0,
    val provider: String,
    val label: String = "",
    val enabled: Boolean = true,
    val hasKey: Boolean = false,
)

data class SpeechSettingsState(
    val snapshot: SpeechSettingsSnapshot? = null,
    val preferences: SpeechPreferences? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val catalogs: Map<String, SpeechCatalogState> = emptyMap(),
    val editor: SpeechConnectionEditor? = null,
    val error: LocalizedString? = null,
    val notice: LocalizedString? = null,
    val retryAtMillis: Long? = null,
) {
    val dirty: Boolean get() = preferences != snapshot?.preferences
    val providers: List<SpeechProvider> get() = snapshot?.providers?.filter { it.connectionKind == "api_key" }.orEmpty()
    val personalConnections: List<SpeechConnection> get() = snapshot?.connections?.filter { it.ownership == "personal" }.orEmpty()
    val sharedConnections: List<SpeechConnection> get() = snapshot?.connections?.filter { it.ownership == "shared" }.orEmpty()

    fun catalog(choice: String?): SpeechCatalogState? = when (choice) {
        null -> null
        "auto", "local" -> snapshot?.providers?.find {
            it.connectionKind == if (choice == "auto") "subscription" else "local"
        }?.let { SpeechCatalogState(0, SpeechCatalogPhase.Ready, it.models) }
        else -> catalogs[choice]
    }

    fun connections(capability: String): List<SpeechConnection> = snapshot?.connections?.filter { connection ->
        connection.enabled && connection.hasKey && capability in connection.capabilities &&
            catalog(connection.id)?.let { catalog ->
                catalog.phase != SpeechCatalogPhase.Ready || catalog.models.any { capability in it.capabilities }
            } != false
    }.orEmpty()

    fun missing(choice: String?, capability: String): Boolean = choice != null && when (choice) {
        "auto" -> catalog(choice) == null
        "local" -> capability != "asr" || catalog(choice) == null
        else -> connections(capability).none { it.id == choice }
    }

    fun model(choice: String?, selected: String?, capability: String, excluded: String? = null): SpeechModel? =
        catalog(choice)?.takeIf { it.phase == SpeechCatalogPhase.Ready }?.models?.let { models ->
            if (selected != null) models.find { it.id == selected && capability in it.capabilities }
            else models.find { it.id != excluded && it.usable(capability) }
        }

    val asrModel: SpeechModel? get() = preferences?.let { model(it.asr, it.asrModel, "asr") }
    val excludedBackupModel: String? get() = preferences?.takeIf { it.asr == it.fallbackAsr }?.let { asrModel?.id }
    val backupModel: SpeechModel? get() = preferences?.let { model(it.fallbackAsr, it.fallbackAsrModel, "asr", excludedBackupModel) }
    val ttsModel: SpeechModel? get() = preferences?.let { model(it.tts, it.ttsModel, "tts") }
    val validTts: Boolean get() = preferences?.let {
        it.tts != null && !missing(it.tts, "tts") && ttsModel?.let { model -> model.usable("tts") && it.voice in model.voices } == true
    } == true
    val validPreferences: Boolean get() = preferences?.let {
        !missing(it.asr, "asr") && asrModel?.usable("asr") == true &&
            (it.fallbackAsr == null || (!missing(it.fallbackAsr, "asr") && backupModel?.let { model ->
                model.usable("asr") && model.id != excludedBackupModel
            } == true)) && (it.tts == null || validTts)
    } == true

    fun connectionLabel(connection: SpeechConnection): String =
        "${connection.label} · ${snapshot?.providers?.find { it.id == connection.provider }?.label ?: connection.provider}"
}

internal fun decodeSpeechSettings(bytes: ByteArray): SpeechSettingsSnapshot =
    NativeJson.decodeFromString<SpeechSettingsSnapshot>(bytes.decodeToString(throwOnInvalidSequence = true)).also { snapshot ->
        require(snapshot.preferences.revision >= 0 && snapshot.preferences.asr.isNotBlank())
        require(snapshot.connections.map { it.id }.distinct().size == snapshot.connections.size)
        snapshot.connections.forEach { require(it.id.isNotBlank() && it.provider.isNotBlank() && it.revision >= 0) }
        snapshot.providers.forEach { validateSpeechModels(it.models) }
    }

internal fun validateSpeechModels(models: List<SpeechModel>) {
    require(models.map { it.id }.distinct().size == models.size)
    models.forEach { model ->
        require(model.id.isNotBlank() && model.name.isNotBlank())
        require(model.voices.none { it.isBlank() } && model.voices.distinct().size == model.voices.size)
        require(model.ttsSegmentChars == null || model.ttsSegmentChars > 0)
    }
}
