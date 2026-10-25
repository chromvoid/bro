package assistant.mobile.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.food.shiftFoodDay
import assistant.mobile.core.personal.personalAmountText
import assistant.mobile.core.sharing.*
import assistant.mobile.design.R as DesignR
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

@Composable
internal fun SharedScreen(controller: SharingController, lease: SessionLease, sharedId: String? = null,
    initialDay: String? = null, onOpen: (String?) -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.surface == SharingSurface.Shared && it.sharedId == sharedId }
    val scope = rememberCoroutineScope()
    SharingLifecycle(controller, lease, SharingSurface.Shared, sharedId = sharedId, initialDay = initialDay)
    ScreenColumn("screen_Shared",
        title = localized(state?.selected?.let { sharingSectionLabel(it.name) } ?: GeneratedStrings.Shared.title)) {
        state?.selected?.let { Text(localized(GeneratedStrings.Shared.of(it.ownerName))) }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "shared_loading")
        state?.let { current ->
            CircleQueryStatus(current.overview, "shared_overview") { scope.launch { controller.refresh() } }
            TextButton(onClick = { scope.launch { controller.refresh() } }, enabled = !current.overview.loading && !current.projection.loading,
                modifier = Modifier.heightIn(min = 48.dp).testTag("shared_refresh")) { Text(localized(GeneratedStrings.Circle.checkAccess)) }
            if (sharedId == null) {
                if (current.overview.data != null && current.opened.isEmpty()) CircleNotice(GeneratedStrings.Shared.empty, GeneratedStrings.Shared.emptyText, "shared_empty")
                current.opened.forEach { item ->
                    CircleRow(item.ownerName + " · " + localized(sharingSectionLabel(item.name)).lowercase(),
                        item.ownerLogin?.let { "@$it · " }.orEmpty() + localized(sharedLevel(item.permission)), "shared_resource_${item.id}") { onOpen(item.id) }
                }
            } else if (current.gone || current.overview.data != null && current.selected == null) {
                CircleNotice(GeneratedStrings.Shared.revoked, GeneratedStrings.Shared.revokedText, "shared_gone")
            } else if (current.selected != null) {
                val shownDay = current.projection.data?.date ?: current.day ?: current.today
                if (shownDay.isNotEmpty()) SharedDayNavigation(shownDay, current.today) { day -> scope.launch { controller.selectDay(day) } }
                CircleQueryStatus(current.projection, "shared_projection") { scope.launch { controller.refresh() } }
                current.projection.data?.let { projection ->
                    Column(Modifier.fillMaxWidth().testTag("shared_projection_$sharedId"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        projection.summary?.let { summary ->
                            SharedDataCard("shared_summary") {
                                Text(localized(GeneratedStrings.Shared.eaten))
                                Text(sharedAmount(summary.value) + " " + localized(GeneratedStrings.Food.kcal), style = MaterialTheme.typography.headlineMedium)
                                summary.target?.let { Text(localized(GeneratedStrings.Shared.goal(sharedAmount(it)))) }
                            }
                        }
                        projection.diary?.let { diary ->
                            SharedDataCard("shared_diary") {
                                Text(localized(GeneratedStrings.Shared.eaten))
                                Text(sharedAmount(diary.totals["energy_kcal"]?.known) + " " +
                                    (diary.goal?.let { localized(GeneratedStrings.Food.of(sharedAmount(it.calorieTarget))) }
                                        ?: localized(GeneratedStrings.Food.kcal)), style = MaterialTheme.typography.headlineMedium)
                            }
                            diary.items.forEach { item -> SharedDataCard("shared_item_${item.id}") {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(formatFoodAmount(item.quantity) + " " + sharedUnit(item.unit))
                                Text(sharedAmount(item.nutrients["energy_kcal"]))
                            } }
                            if (diary.items.isEmpty()) Text(localized(GeneratedStrings.Shared.noEntries))
                        }
                        projection.metrics.forEach { metric -> SharedDataCard("shared_metric_${metric.kind}") {
                            Text(personalMetricLabel(metric.kind))
                            Text(if (metric.status == "conflict") localized(GeneratedStrings.Personal.disagree)
                                else personalAmountText(metric.kind, metric.value, locale = current.locale) ?: localized(GeneratedStrings.Personal.noData),
                                style = MaterialTheme.typography.titleLarge)
                        } }
                        projection.profile?.let { profile -> SharedDataCard("shared_profile") {
                            Text(localized(GeneratedStrings.Personal.height)); Text(formatFoodAmount(profile.heightCm))
                            Text(localized(GeneratedStrings.Personal.birthDate)); Text(profile.birthDate ?: "—")
                        } }
                        Text(localized(sharedLevel(projection.permission)))
                        Text(localized(GeneratedStrings.Shared.readOnly))
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedDataCard(tag: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedDayNavigation(day: String, today: String, onDay: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val previous = localized(GeneratedStrings.Day.previous)
    val next = localized(GeneratedStrings.Day.next)
    Text(day, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("shared_day"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onDay(shiftFoodDay(day, -1)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("shared_previous_day").semantics { contentDescription = previous }) { Text("‹") }
        TextButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("shared_pick_day")) { Text(localized(GeneratedStrings.Day.pick)) }
        TextButton(onClick = { onDay(shiftFoodDay(day, 1)) }, enabled = day < today,
            modifier = Modifier.heightIn(min = 48.dp).testTag("shared_next_day").semantics { contentDescription = next }) { Text("›") }
    }
    if (day != today) TextButton(onClick = { onDay(today) }, modifier = Modifier.heightIn(min = 48.dp).testTag("shared_today")) { Text(localized(GeneratedStrings.Day.today)) }
    if (picking) {
        val maximum = remember(today) { LocalDate.parse(today).toEpochDay() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / 86_400_000 <= maximum })
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onDay(LocalDate.ofEpochDay(it / 86_400_000).toString()) }; picking = false
            }, enabled = picker.selectedDateMillis != null) { Text(localized(GeneratedStrings.Common.done)) }
        }, dismissButton = { TextButton(onClick = { picking = false }) { Text(localized(GeneratedStrings.Common.cancel)) } }) { DatePicker(picker) }
    }
}

private fun sharedAmount(value: String?): String = value?.let { formatFoodAmount(it, 0) } ?: "—"
@Composable
private fun sharedUnit(unit: String): String = when (unit) {
    "g" -> stringResource(DesignR.string.food_grams, "").trim()
    "ml" -> stringResource(DesignR.string.food_millilitres, "").trim()
    "serving" -> stringResource(DesignR.string.food_servings)
    else -> unit
}
private fun sharedLevel(permission: String) = when (permission) {
    "summary" -> GeneratedStrings.Shared.Level.summary
    "manage" -> GeneratedStrings.Shared.Level.manage
    else -> GeneratedStrings.Shared.Level.detail
}
