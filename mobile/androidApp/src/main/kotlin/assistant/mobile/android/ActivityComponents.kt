package assistant.mobile.android

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import assistant.mobile.core.SessionLease
import assistant.mobile.core.activity.ActivityController
import assistant.mobile.design.R as DesignR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ActivityActivation(controller: ActivityController, lease: SessionLease, initialDay: String? = null) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(controller, lease, owner, initialDay) {
        val visibilityOwner = Any()
        var first = true
        fun activate() { controller.activate(if (first) initialDay else null, visibilityOwner); first = false }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> activate()
            Lifecycle.Event.ON_STOP -> controller.deactivate(visibilityOwner)
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) activate()
        onDispose { owner.lifecycle.removeObserver(observer); controller.deactivate(visibilityOwner) }
    }
}

@Composable
internal fun ActivitySection(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun ActivityAction(label: Int, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable
internal fun ActivityField(value: String, onChange: (String) -> Unit, label: Int, tag: String,
    enabled: Boolean, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(value, onValueChange = onChange, label = { Text(stringResource(label)) }, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
internal fun ActivityChoices(values: List<String>, selected: String, label: @Composable (String) -> String,
    tag: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("${tag}_choices"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected = selected == value, onClick = { onSelect(value) },
            enabled = enabled, label = { Text(label(value)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_$value")) }
    }
}

@Composable
internal fun ActivityDayNav(day: String, today: String, onSelect: (String) -> Unit) {
    Text(activityDay(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("activity_day"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onSelect(activityShiftDay(day, -1)) }, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("activity_day_previous")) { Text(stringResource(DesignR.string.day_previous)) }
        TextButton(onClick = { onSelect(today) }, enabled = day != today, modifier = Modifier.heightIn(min = 48.dp)
            .testTag("activity_day_today")) { Text(stringResource(DesignR.string.activity_to_today)) }
        TextButton(onClick = { onSelect(activityShiftDay(day, 1)) }, enabled = day < today,
            modifier = Modifier.heightIn(min = 48.dp).testTag("activity_day_next")) {
            Text(stringResource(DesignR.string.day_next))
        }
    }
}

@Composable
internal fun activityKind(kind: String): String = stringResource(when (kind) {
    "walking" -> DesignR.string.activity_kinds_walking
    "running" -> DesignR.string.activity_kinds_running
    "cycling" -> DesignR.string.activity_kinds_cycling
    "strength" -> DesignR.string.activity_kinds_strength
    "swimming" -> DesignR.string.activity_kinds_swimming
    "mobility" -> DesignR.string.activity_kinds_mobility
    "all" -> DesignR.string.activity_all_kinds
    else -> DesignR.string.activity_kinds_other
})

@Composable
internal fun activityLoadBasis(basis: String): String = stringResource(when (basis) {
    "per_implement" -> DesignR.string.activity_load_bases_per_implement
    "additional" -> DesignR.string.activity_load_bases_additional
    "assistance" -> DesignR.string.activity_load_bases_assistance
    "bodyweight" -> DesignR.string.activity_load_bases_bodyweight
    else -> DesignR.string.activity_load_bases_total
})

@Composable
internal fun activityStatus(status: String): String = stringResource(when (status) {
    "available" -> DesignR.string.resolution_status_available
    "conflict" -> DesignR.string.resolution_status_conflict
    "unavailable" -> DesignR.string.resolution_status_unavailable
    "partial" -> DesignR.string.resolution_status_partial
    "unknown" -> DesignR.string.resolution_status_unknown
    else -> DesignR.string.resolution_status_no_data
})

internal val activityKinds = listOf("walking", "running", "cycling", "strength", "swimming", "mobility", "other")
internal val activityLoadBases = listOf("total", "per_implement", "additional", "assistance", "bodyweight")
internal fun activityShiftDay(day: String, amount: Int): String = runCatching { LocalDate.parse(day).plusDays(amount.toLong()).toString() }.getOrDefault(day)
internal fun activityDay(day: String): String = runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))) }.getOrDefault(day)
internal fun activityMoment(stamp: String, zone: String): String = runCatching {
    Instant.parse(stamp).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("d MMMM HH:mm", Locale.forLanguageTag("ru-RU")))
}.getOrDefault(stamp)
