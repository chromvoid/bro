package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.speech.SpeechCatalogPhase
import assistant.mobile.core.speech.SpeechCatalogState
import assistant.mobile.core.speech.SpeechConnectionEditor
import assistant.mobile.core.speech.SpeechPreferences
import assistant.mobile.core.speech.SpeechSettingsController
import assistant.mobile.core.speech.SpeechSettingsState
import kotlinx.coroutines.launch

@Composable
internal fun SpeechSettingsScreen(
    controller: SpeechSettingsController,
    onPreview: (connection: String, voice: String, model: String?) -> Unit,
    onStopPreview: () -> Unit,
    previewPlaying: Boolean,
    previewProblem: LocalizedString? = null,
    ambientControls: @Composable () -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val wait = retrySeconds(state?.retryAtMillis)
    LaunchedEffect(controller) { controller.refresh() }
    DisposableEffect(controller) { onDispose { controller.closeConnection() } }
    ScreenColumn("screen_SpeechSettings", title = localized(GeneratedStrings.SpeechSettings.title)) {
        val form = state
        if (form == null || form.loading && form.snapshot == null) StatusMessage(GeneratedStrings.State.loading)
        if (form != null) {
            form.error?.let {
                ErrorMessage(it, "speech_settings_error")
                TextButton(onClick = { scope.launch { controller.refresh() } }, enabled = !form.busy && wait == 0L,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("speech_settings_reload")) {
                    Text(localized(GeneratedStrings.SpeechSettings.reload))
                }
            }
            form.notice?.let { StatusMessage(it, "speech_settings_notice") }
            if (wait > 0) StatusMessage(GeneratedStrings.Auth.pauseText(wait.toString()), "speech_settings_retry")
            form.preferences?.let { preferences ->
                SpeechPreferencesPanel(form, preferences, controller, previewPlaying, previewProblem, onPreview, onStopPreview)
            }
            SpeechHeading(GeneratedStrings.SpeechSettings.personalTitle)
            Text(localized(GeneratedStrings.SpeechSettings.personalHint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { controller.openConnection() }, enabled = !form.busy && form.providers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speech_connection_add")) {
                Text(localized(GeneratedStrings.SpeechSettings.add))
            }
            if (form.snapshot != null && form.personalConnections.isEmpty()) {
                Text(localized(GeneratedStrings.SpeechSettings.empty))
                Text(localized(GeneratedStrings.SpeechSettings.emptyHint), style = MaterialTheme.typography.bodySmall)
            }
            (form.personalConnections + form.sharedConnections).forEach { connection ->
                if (connection == form.sharedConnections.firstOrNull()) SpeechHeading(GeneratedStrings.SpeechSettings.sharedTitle)
                OutlinedCard(Modifier.fillMaxWidth().testTag("speech_connection_card_${connection.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(form.connectionLabel(connection), style = MaterialTheme.typography.titleMedium)
                        Text(localized(when {
                            !connection.enabled -> GeneratedStrings.SpeechSettings.disabled
                            connection.hasKey -> GeneratedStrings.SpeechSettings.keyPresent
                            else -> GeneratedStrings.SpeechSettings.keyMissing
                        }))
                        if (connection.ownership == "personal") {
                            TextButton(onClick = { controller.openConnection(connection.id) }, enabled = !form.busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("speech_connection_${connection.id}")) {
                                Text(localized(GeneratedStrings.SpeechSettings.edit))
                            }
                        }
                        if (connection.enabled && connection.hasKey) {
                            SpeechCatalog(form.catalogs[connection.id], connection.id)
                            TextButton(onClick = { scope.launch { controller.refreshModels(connection.id) } },
                                enabled = !form.busy && wait == 0L && form.catalogs[connection.id]?.phase != SpeechCatalogPhase.Loading,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("speech_models_refresh_${connection.id}")) {
                                Text(localized(GeneratedStrings.SpeechSettings.refreshModels))
                            }
                        }
                    }
                }
            }
        }
        ambientControls()
    }
    state?.editor?.let { editor ->
        SpeechConnectionDialog(controller, requireNotNull(state), editor,
            onSave = { secret -> scope.launch { controller.saveConnection(secret) } },
            onDelete = { scope.launch { controller.deleteConnection() } })
    }
}

@Composable
private fun SpeechPreferencesPanel(
    form: SpeechSettingsState,
    preferences: SpeechPreferences,
    controller: SpeechSettingsController,
    previewPlaying: Boolean,
    previewProblem: LocalizedString?,
    onPreview: (String, String, String?) -> Unit,
    onStopPreview: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val wait = retrySeconds(form.retryAtMillis)
    val available = !form.busy && !form.loading
    SpeechHeading(GeneratedStrings.SpeechSettings.selectionTitle)
    form.snapshot?.asr?.message?.let { StatusMessage(it, "speech_asr_readiness") }
    val asrOptions = buildList {
        add("auto" to localized(GeneratedStrings.SpeechSettings.auto))
        form.snapshot?.providers?.find { it.connectionKind == "local" }?.let { add("local" to it.label) }
        form.connections("asr").forEach { add(it.id to form.connectionLabel(it)) }
    }
    SpeechChoice(GeneratedStrings.SpeechSettings.asr, preferences.asr, asrOptions, "speech_asr", available) {
        controller.updatePreferences(preferences.copy(asr = it, asrModel = null))
    }
    if (preferences.asr == "auto") Text(localized(GeneratedStrings.SpeechSettings.autoHint), style = MaterialTheme.typography.bodySmall)
    SpeechModelChoice(form, preferences.asr, preferences.asrModel, "asr", GeneratedStrings.SpeechSettings.asrModel,
        "speech_asr_model", available) { controller.updatePreferences(preferences.copy(asrModel = it)) }
    SpeechChoice(GeneratedStrings.SpeechSettings.backup, preferences.fallbackAsr.orEmpty(),
        listOf("" to localized(GeneratedStrings.SpeechSettings.noBackup)) + form.connections("asr").map { it.id to form.connectionLabel(it) },
        "speech_backup", available) {
        controller.updatePreferences(preferences.copy(fallbackAsr = it.ifEmpty { null }, fallbackAsrModel = null))
    }
    Text(localized(GeneratedStrings.SpeechSettings.backupHint), style = MaterialTheme.typography.bodySmall)
    if (preferences.fallbackAsr != null) SpeechModelChoice(form, preferences.fallbackAsr, preferences.fallbackAsrModel,
        "asr", GeneratedStrings.SpeechSettings.backupModel, "speech_backup_model", available, form.excludedBackupModel) {
        controller.updatePreferences(preferences.copy(fallbackAsrModel = it))
    }
    SpeechChoice(GeneratedStrings.SpeechSettings.tts, preferences.tts.orEmpty(),
        listOf("auto" to localized(GeneratedStrings.SpeechSettings.auto), "" to localized(GeneratedStrings.SpeechSettings.noTts)) +
            form.connections("tts").map { it.id to form.connectionLabel(it) }, "speech_tts", available) {
        onStopPreview()
        controller.updatePreferences(preferences.copy(tts = it.ifEmpty { null }, ttsModel = null))
    }
    Text(localized(GeneratedStrings.SpeechSettings.ttsHint), style = MaterialTheme.typography.bodySmall)
    if (preferences.tts == "auto") Text(localized(GeneratedStrings.SpeechSettings.autoTtsHint), style = MaterialTheme.typography.bodySmall)
    val saved = form.snapshot?.preferences
    if (saved != null && preferences.tts == saved.tts && preferences.ttsModel == saved.ttsModel &&
        preferences.voice == saved.voice) {
        form.snapshot?.tts?.message?.let { StatusMessage(it, "speech_tts_readiness") }
    }
    if (preferences.tts != null) {
        SpeechModelChoice(form, preferences.tts, preferences.ttsModel, "tts", GeneratedStrings.SpeechSettings.model,
            "speech_tts_model", available) {
            onStopPreview()
            controller.updatePreferences(preferences.copy(ttsModel = it))
        }
        val voices = form.ttsModel?.voices.orEmpty()
        if (voices.isNotEmpty()) {
            SpeechChoice(GeneratedStrings.SpeechSettings.voice, preferences.voice,
                voices.map { it to if (it == "default") localized(GeneratedStrings.SpeechSettings.defaultVoice) else it },
                "speech_voice", available) {
                onStopPreview()
                controller.updatePreferences(preferences.copy(voice = it))
            }
            if (voices.size == 1) Text(localized(GeneratedStrings.SpeechSettings.singleVoiceHint), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            if (previewPlaying) onStopPreview() else preferences.tts?.let { onPreview(it, preferences.voice, form.ttsModel?.id) }
        }, enabled = previewPlaying || available && form.validTts && wait == 0L,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speech_preview")) {
            Text(localized(if (previewPlaying) GeneratedStrings.SpeechSettings.stop else GeneratedStrings.SpeechSettings.preview))
        }
        previewProblem?.let { ErrorMessage(it, "speech_preview_error") }
    }
    Button(onClick = { scope.launch { controller.savePreferences() } },
        enabled = available && wait == 0L && form.dirty && form.validPreferences,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speech_preferences_save")) {
        Text(localized(GeneratedStrings.SpeechSettings.save))
    }
}

@Composable
private fun SpeechModelChoice(
    form: SpeechSettingsState, connection: String?, selected: String?, capability: String,
    label: LocalizedString, tag: String, enabled: Boolean, excluded: String? = null, onChange: (String) -> Unit,
) {
    val catalog = form.catalog(connection)
    val models = catalog?.models.orEmpty().filter { capability in it.capabilities }
    val selectedId = selected ?: form.model(connection, selected, capability, excluded)?.id.orEmpty()
    SpeechChoice(label, selectedId, models.map { it.id to it.name }, tag, enabled,
        disabledOptions = models.filter { !it.usable(capability) || it.id == excluded }.map { it.id }.toSet(), onChange = onChange)
    if (catalog?.phase == SpeechCatalogPhase.Loading) StatusMessage(GeneratedStrings.SpeechSettings.modelsLoading, "${tag}_loading")
    catalog?.error?.let { ErrorMessage(it, "${tag}_error") }
    val model = models.find { it.id == selectedId }
    if (model?.asrMode == "file" && capability == "asr") Text(localized(GeneratedStrings.SpeechSettings.fileAsrHint),
        style = MaterialTheme.typography.bodySmall)
    if (catalog?.phase == SpeechCatalogPhase.Ready && models.isEmpty()) StatusMessage(
        if (capability == "tts") GeneratedStrings.SpeechSettings.NoModelsFor.tts else GeneratedStrings.SpeechSettings.NoModelsFor.asr,
        "${tag}_empty")
}

@Composable
private fun SpeechCatalog(catalog: SpeechCatalogState?, id: String) {
    var expanded by remember(id) { mutableStateOf(false) }
    when (catalog?.phase) {
        SpeechCatalogPhase.Loading -> StatusMessage(GeneratedStrings.SpeechSettings.modelsLoading, "speech_catalog_${id}_loading")
        SpeechCatalogPhase.Failed -> catalog.error?.let { ErrorMessage(it, "speech_catalog_${id}_error") }
        SpeechCatalogPhase.Ready -> {
            if (catalog.models.isNotEmpty()) TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speech_catalog_toggle_$id")) {
                Text(localized(GeneratedStrings.SpeechSettings.modelCounts(catalog.models.size.toString(),
                    catalog.models.count { "asr" in it.capabilities }.toString(), catalog.models.count { "tts" in it.capabilities }.toString())))
            }
            if (expanded) catalog.models.forEach { model ->
                Text(model.name, style = MaterialTheme.typography.titleSmall)
                Text(model.capabilities.filter { it in setOf("asr", "tts") }.joinToString(" · ") { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall)
                if (!model.selectable) Text(localized(GeneratedStrings.SpeechSettings.unavailableModel))
                else if ("tts" in model.capabilities && model.voices.isEmpty()) Text(localized(GeneratedStrings.SpeechSettings.voicesMissing))
            }
            if (catalog.models.isEmpty()) StatusMessage(GeneratedStrings.SpeechSettings.modelsEmpty)
        }
        null -> Unit
    }
}

@Composable
private fun SpeechConnectionDialog(
    controller: SpeechSettingsController,
    state: SpeechSettingsState,
    editor: SpeechConnectionEditor,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // No Saver: Android instance state, controller state and command storage never receive a key.
    var secret by remember(editor.id) { mutableStateOf("") }
    var deleting by remember(editor.id) { mutableStateOf(false) }
    val wait = retrySeconds(state.retryAtMillis)
    val submit = {
        val requestSecret = secret
        secret = ""
        keyboard?.hide()
        focus.clearFocus()
        onSave(requestSecret)
        Unit
    }
    val close = { secret = ""; controller.closeConnection(); Unit }
    Dialog(onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding()) {
            ScreenColumn("speech_connection_editor",
                title = localized(if (editor.revision > 0) GeneratedStrings.SpeechSettings.edit else GeneratedStrings.SpeechSettings.add),
                onBack = close) {
                SpeechChoice(GeneratedStrings.SpeechSettings.provider, editor.provider, state.providers.map { it.id to it.label },
                    "speech_connection_provider", !state.busy && editor.revision == 0L, onChange = controller::editConnectionProvider)
                AuthTextField(editor.label, controller::editConnectionLabel, GeneratedStrings.SpeechSettings.label,
                    "speech_connection_label", !state.busy)
                PasswordField(secret, { secret = it.take(4096) },
                    if (editor.hasKey) GeneratedStrings.SpeechSettings.replaceKey else GeneratedStrings.SpeechSettings.key,
                    "speech_connection_api_key", !state.busy,
                    onDone = { if (!state.busy && wait == 0L && editor.label.isNotBlank() && (editor.hasKey || secret.isNotBlank())) submit() })
                if (editor.hasKey) Text(localized(GeneratedStrings.SpeechSettings.keepKey), style = MaterialTheme.typography.bodySmall)
                Text(localized(GeneratedStrings.SpeechSettings.secretHint), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(editor.enabled, controller::editConnectionEnabled, enabled = !state.busy,
                        modifier = Modifier.testTag("speech_connection_enabled"))
                    Text(localized(GeneratedStrings.SpeechSettings.enabled))
                }
                state.error?.let { ErrorMessage(it, "speech_connection_error") }
                if (wait > 0) StatusMessage(GeneratedStrings.Auth.pauseText(wait.toString()))
                Button(onClick = submit, enabled = !state.busy && wait == 0L && editor.label.isNotBlank() && (editor.hasKey || secret.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speech_connection_save")) {
                    Text(localized(GeneratedStrings.SpeechSettings.save))
                }
                if (editor.revision > 0) TextButton(onClick = { secret = ""; deleting = true }, enabled = !state.busy && wait == 0L,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speech_connection_remove")) {
                    Text(localized(GeneratedStrings.SpeechSettings.remove))
                }
                TextButton(onClick = { secret = ""; controller.closeConnection() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("speech_connection_cancel")) {
                    Text(localized(GeneratedStrings.SpeechSettings.cancel))
                }
            }
        }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false },
            title = { Text(localized(GeneratedStrings.SpeechSettings.removeTitle)) },
            text = { Text(localized(GeneratedStrings.SpeechSettings.removeText)) },
            confirmButton = {
                TextButton(onClick = { deleting = false; onDelete() }, enabled = !state.busy && wait == 0L,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("speech_connection_delete_confirm")) {
                    Text(localized(GeneratedStrings.SpeechSettings.remove))
                }
            }, dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(localized(GeneratedStrings.SpeechSettings.cancel)) }
            })
    }
}

@Composable
private fun SpeechChoice(
    label: LocalizedString, selected: String, options: List<Pair<String, String>>, tag: String,
    enabled: Boolean, disabledOptions: Set<String> = emptySet(), onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localized(label), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
                Text(options.find { it.first == selected }?.second ?: localized(GeneratedStrings.SpeechSettings.unavailableChoice))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onChange(id) },
                        enabled = id !in disabledOptions, modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_$id"))
                }
            }
        }
    }
}

@Composable
private fun SpeechHeading(message: LocalizedString) {
    Text(localized(message), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
