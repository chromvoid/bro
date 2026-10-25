package assistant.mobile.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.assistant.AssistantController
import assistant.mobile.core.assistant.AssistantReply
import assistant.mobile.core.assistant.AssistantTurn
import assistant.mobile.core.assistant.AssistantUsage
import assistant.mobile.core.assistant.rows
import assistant.mobile.design.ui.StatusBadge
import assistant.mobile.design.ui.StatusTone
import io.ktor.http.Url
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSourcesSheet(reply: AssistantReply, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("assistant_sources_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(GeneratedStrings.Assistant.sourcesTitle), style = MaterialTheme.typography.titleLarge)
            if (reply.limitations.isNotEmpty()) Text(localized(GeneratedStrings.Assistant.limitations), style = MaterialTheme.typography.titleMedium)
            reply.limitations.forEach { Text(it) }
            reply.sources.forEach { source ->
                // Use the same exact citation boundary as assistant Markdown, including normalization.
                source.url.takeIf { it in reply.allowedLinks }?.let(::normalizedAssistantLink)?.let { url ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val host = remember(url) { runCatching { Url(url).host }.getOrDefault(url) }
                        TextButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(source.title?.takeIf(String::isNotBlank) ?: host, modifier = Modifier.fillMaxWidth())
                        }
                        Text(listOfNotNull(host, source.publishedAt).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                        source.text?.takeIf(String::isNotBlank)?.let { Text(it) }
                        when (source.relation) {
                            "supports" -> Text(localized(GeneratedStrings.Assistant.supports), style = MaterialTheme.typography.labelMedium)
                            "contradicts" -> Text(localized(GeneratedStrings.Assistant.contradicts), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantTurnDetailsSheet(turnId: String, controller: AssistantController, onDismiss: () -> Unit) {
    var turn by remember(turnId) { mutableStateOf<AssistantTurn?>(null) }
    var loading by remember(turnId) { mutableStateOf(true) }
    var attempt by remember(turnId) { mutableIntStateOf(0) }
    LaunchedEffect(turnId, attempt) {
        loading = true
        turn = controller.turnDetails(turnId)
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("assistant_turn_details"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(GeneratedStrings.Assistant.detailsTitle), style = MaterialTheme.typography.titleLarge)
            Text(localized(GeneratedStrings.Assistant.detailsServices), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                loading -> Text(localized(GeneratedStrings.State.loading))
                turn == null -> {
                    Text(localized(GeneratedStrings.Errors.server), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(localized(GeneratedStrings.Common.retry)) }
                }
                else -> {
                    val result = turn?.result
                    if (result?.unsupported == true) Text(localized(GeneratedStrings.Assistant.ResultActions.unavailable))
                    else {
                        val services = result?.services.orEmpty()
                        if (services.isEmpty()) Text(localized(if (result?.execution?.effects == "none") GeneratedStrings.Assistant.noChanges else GeneratedStrings.Assistant.detailsEmpty))
                        services.forEachIndexed { index, service ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(localized(serviceLabel(service.service)),
                                            modifier = Modifier.weight(1f).testTag("assistant_service_name_$index"),
                                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                        StatusBadge(
                                            text = localized(resultStateLabel(service.status)),
                                            tone = when (service.status) {
                                                "completed" -> StatusTone.Success
                                                "rejected" -> StatusTone.Destructive
                                                else -> StatusTone.Warning
                                            },
                                            modifier = Modifier.testTag("assistant_service_status_$index"),
                                        )
                                    }
                                    val texts = listOfNotNull(
                                        service.receiptText?.let { it to MaterialTheme.colorScheme.onSurface },
                                        service.message?.takeIf { service.error.isNullOrEmpty() }
                                            ?.let { it to MaterialTheme.colorScheme.onSurfaceVariant },
                                        service.question?.takeIf { service.error.isNullOrEmpty() }
                                            ?.let { it to MaterialTheme.colorScheme.onSurface },
                                    )
                                    texts.filter { it.first.isNotBlank() }.distinctBy { it.first }.forEach { (text, color) ->
                                        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
                                    }
                                }
                            }
                        }
                    }
                    AssistantUsageDetails(turn?.usage ?: AssistantUsage())
                }
            }
            Text(localized(GeneratedStrings.Assistant.detailsDiagnostic), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(turnId, modifier = Modifier.testTag("assistant_turn_diagnostic"),
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun AssistantUsageDetails(usage: AssistantUsage) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    Column(Modifier.fillMaxWidth().testTag("assistant_usage"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localized(GeneratedStrings.Assistant.Usage.title), style = MaterialTheme.typography.titleMedium)
        Text(localized(GeneratedStrings.Assistant.Usage.note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        usage.rows().forEach { row ->
            val value = row.value?.let(formatter::format)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(localized(row.label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(if (value == null) localized(GeneratedStrings.Assistant.Usage.unknown)
                    else if (row.milliseconds) localized(GeneratedStrings.Assistant.Usage.milliseconds(value)) else value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            }
        }
    }
}

private fun serviceLabel(service: String?): LocalizedString = when (service) {
    "nutrition" -> GeneratedStrings.Nav.food
    "tasks" -> GeneratedStrings.Nav.tasks
    "personal" -> GeneratedStrings.Nav.personal
    "accounts" -> GeneratedStrings.Nav.accounts
    "workers" -> GeneratedStrings.Nav.workers
    "timers" -> GeneratedStrings.Services.timers
    "research" -> GeneratedStrings.Services.research
    else -> GeneratedStrings.Services.general
}

private fun resultStateLabel(state: String?): LocalizedString = when (state) {
    "completed" -> GeneratedStrings.Assistant.State.completed
    "rejected" -> GeneratedStrings.Assistant.State.rejected
    "failed" -> GeneratedStrings.Assistant.State.failed
    "interrupted" -> GeneratedStrings.Assistant.State.interrupted
    else -> GeneratedStrings.Assistant.State.unknown
}
