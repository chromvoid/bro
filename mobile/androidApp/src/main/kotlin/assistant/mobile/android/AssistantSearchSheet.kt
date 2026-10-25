package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.assistant.AssistantSearchController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSearchSheet(search: AssistantSearchController, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val state by search.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(GeneratedStrings.Assistant.search), style = MaterialTheme.typography.titleLarge)
            Text(localized(GeneratedStrings.Assistant.searchHint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(state.text, { search.setCriteria(it.take(160), state.afterDay, state.throughDay) },
                label = { Text(localized(GeneratedStrings.Assistant.searchPlaceholder)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("assistant_search_query"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.afterDay, { search.setCriteria(state.text, it, state.throughDay) },
                    label = { Text(localized(GeneratedStrings.Assistant.searchAfter)) },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("assistant_search_after"))
                OutlinedTextField(state.throughDay, { search.setCriteria(state.text, state.afterDay, it) },
                    label = { Text(localized(GeneratedStrings.Assistant.searchThrough)) },
                    placeholder = { Text("YYYY-MM-DD") }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("assistant_search_through"))
            }
            if (!state.validBounds) Text(localized(GeneratedStrings.Assistant.searchDateError), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { scope.launch { search.search() } }, enabled = !state.loading && state.ready,
                modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_search_submit")) {
                Text(localized(if (state.loading) GeneratedStrings.State.loading else GeneratedStrings.Common.search))
            }
            state.error?.let { ErrorMessage(it, "assistant_search_error") }
            state.hits.forEach { hit ->
                TextButton(onClick = { onPick(hit.id) }, modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 64.dp).testTag("assistant_search_result_${hit.id}")) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${localized(if (hit.author == "assistant") GeneratedStrings.Assistant.searchAssistant else GeneratedStrings.Assistant.you)} · ${hit.occurredAt}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(hit.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (state.completed && !state.loading && state.hits.isEmpty() && state.error == null)
                Text(localized(GeneratedStrings.Assistant.searchEmpty))
            if (state.cursor != null) TextButton(onClick = { scope.launch { search.loadMore() } }, enabled = !state.loading,
                modifier = Modifier.heightIn(min = 48.dp).testTag("assistant_search_more")) {
                Text(localized(GeneratedStrings.Assistant.searchMore))
            }
        }
    }
}
