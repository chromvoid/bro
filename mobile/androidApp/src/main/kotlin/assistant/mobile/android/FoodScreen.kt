package assistant.mobile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.food.FoodController
import assistant.mobile.core.food.FoodDay
import assistant.mobile.core.food.FoodDish
import assistant.mobile.core.food.FoodEditDraft
import assistant.mobile.core.food.FoodEntry
import assistant.mobile.core.food.FoodItem
import assistant.mobile.core.food.FoodMealKind
import assistant.mobile.core.food.FoodQuery
import assistant.mobile.core.food.FoodState
import assistant.mobile.core.food.FoodTotal
import assistant.mobile.core.food.FoodWeekDay
import assistant.mobile.core.food.foodWeekStart
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.food.parseFoodAmount
import assistant.mobile.core.food.sameFoodAmount
import assistant.mobile.core.food.shiftFoodDay
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.design.R as DesignR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Day and receipt data come from the ledger; only form text and selection live in the UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodScreen(
    controller: FoodController,
    lease: SessionLease,
    onOpenAssistant: () -> Unit,
    onOpenStats: () -> Unit,
    initialDate: String? = null,
    modifier: Modifier = Modifier,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var selecting by remember(lease.namespace, state?.selectedDay) { mutableStateOf(false) }
    var selected by remember(lease.namespace, state?.selectedDay) { mutableStateOf(emptySet<String>()) }
    var grouping by remember(lease.namespace, state?.selectedDay) { mutableStateOf(false) }
    DisposableEffect(controller, lease, owner, initialDate) {
        var firstActivation = true
        fun activate() {
            controller.activate(if (firstActivation) initialDate else null)
            firstActivation = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> activate()
                Lifecycle.Event.ON_STOP -> controller.deactivate()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) activate()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.deactivate()
        }
    }
    Column(modifier.fillMaxSize()) {
        NativeTopBar(stringResource(DesignR.string.food_title))
        PullToRefreshBox(
            isRefreshing = state?.day?.let { it.loading && it.data != null } == true,
            onRefresh = { scope.launch { controller.refresh() } },
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("food_pull_refresh"),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("screen_Food"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onOpenStats,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("open_FoodStats")) {
                    Text(stringResource(DesignR.string.stats_title))
                }
                if (state == null) StatusMessage(GeneratedStrings.State.loading, "food_loading")
                state?.let { current ->
                    FoodDayNavigation(current) { day -> scope.launch { controller.selectDay(day) } }
                    FoodQueryStatus(current.week, "week") { scope.launch { controller.refresh() } }
                    FoodWeekStrip(current.selectedDay, current.today, current.week.data.orEmpty()) { day ->
                        scope.launch { controller.selectDay(day) }
                    }
                    if (current.draft == null) FoodPending(current, controller)
                    current.error?.let { ErrorMessage(it, "food_error") }
                    FoodQueryStatus(current.day, "day") { scope.launch { controller.refresh() } }
                    current.receipt?.let { receipt ->
                        OutlinedCard(Modifier.fillMaxWidth().testTag("food_receipt")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusMessage(if (receipt.kind == "operation.undo") GeneratedStrings.Common.undone
                                    else GeneratedStrings.Food.receipt, "food_receipt_status")
                                if (receipt.canUndo && current.canWrite) TextButton(
                                    onClick = { scope.launch { controller.undo() } }, enabled = !current.busy,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("food_undo"),
                                ) { Text(stringResource(DesignR.string.common_undo)) }
                            }
                        }
                    }
                    if (!current.canRead) UnavailableCard()
                    current.day.data?.let { day ->
                        FoodDaySummary(day)
                        if (current.canWrite) {
                            Button(onClick = { scope.launch { controller.openAdd() } }, enabled = !current.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add")) {
                                Text(stringResource(DesignR.string.food_add))
                            }
                            Button(onClick = onOpenAssistant, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("food_open_assistant")) { Text(stringResource(DesignR.string.food_ask_assistant)) }
                        }
                        if (day.drafts.isNotEmpty()) FoodSection(DesignR.string.food_drafts, "food_drafts") {
                            val missingQuantity = stringResource(DesignR.string.food_missing_quantity)
                            val missingUnit = stringResource(DesignR.string.food_missing_unit)
                            val missingSnapshot = stringResource(DesignR.string.food_missing_snapshot_id)
                            day.drafts.forEach { draft ->
                                Text(shownFoodName(draft.label), style = MaterialTheme.typography.bodyLarge)
                                Text(localized(GeneratedStrings.Food.draftText(draft.missingFields.joinToString(", ") {
                                    when (it) {
                                        "quantity" -> missingQuantity
                                        "unit" -> missingUnit
                                        "snapshot_id" -> missingSnapshot
                                        else -> it
                                    }
                                })), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (day.items.isEmpty() && day.drafts.isEmpty()) Text(stringResource(DesignR.string.food_empty_title),
                            modifier = Modifier.testTag("food_empty"), style = MaterialTheme.typography.titleMedium)
                        if (day.items.isNotEmpty()) {
                            FoodHeading(DesignR.string.food_entries)
                            if (current.canWrite && day.items.filterIsInstance<FoodEntry>().count { it.dishId == null } >= 2) {
                                TextButton(onClick = { selecting = !selecting; selected = emptySet() },
                                    enabled = !current.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("food_select")) {
                                    Text(stringResource(if (selecting) DesignR.string.common_cancel else DesignR.string.food_select))
                                }
                            }
                            if (selecting && selected.size >= 2) Button(onClick = { grouping = true }, enabled = !current.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_group")) {
                                Text(stringResource(DesignR.string.food_group))
                            }
                            day.sections.forEach { section ->
                                Text(foodMealKindLabel(section.kind), modifier = Modifier.semantics { heading() }
                                    .testTag("food_meal_kind_${section.kind?.wire ?: "none"}"),
                                    style = MaterialTheme.typography.titleMedium)
                                section.meals.forEach { meal ->
                                    FoodSection(null, "food_meal_${meal.key}") {
                                        val time = meal.at?.let { foodTime(it, current.timezone) }
                                        Text(if (time == null) stringResource(DesignR.string.food_no_time)
                                            else if (section.kind != null) stringResource(DesignR.string.food_meal_logged_at, time)
                                            else time, style = MaterialTheme.typography.labelLarge)
                                        meal.items.forEach { item ->
                                            FoodItemCard(item, current.canWrite, selecting, item.id in selected,
                                                onToggle = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                                                onRepeat = { if (item is FoodDish) scope.launch { controller.repeatDish(item) } },
                                                onOpen = { controller.beginEdit(item) })
                                        }
                                    }
                                }
                            }
                            if (day.items.any { it.estimated }) Text(stringResource(DesignR.string.food_estimated_legend),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (day.completed) StatusMessage(GeneratedStrings.Food.completed, "food_day_completed")
                        else if (current.canWrite && day.entries.isNotEmpty() && day.drafts.isEmpty() &&
                            day.totals["energy_kcal"]?.complete == true) TextButton(
                            onClick = { scope.launch { controller.completeDay() } }, enabled = !current.busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("food_complete_day"),
                        ) { Text(stringResource(DesignR.string.food_complete)) }
                    }
                }
            }
        }
    }
    state?.draft?.let { draft -> FoodEditSheet(requireNotNull(state), draft, controller) }
    state?.let { FoodAddScreen(controller, it, onOpenAssistant) }
    if (grouping && state != null) FoodGroupSheet(requireNotNull(state), selected.toList(), controller) {
        grouping = false
        if (controller.state.value?.error == null) { selecting = false; selected = emptySet() }
    }
}

@Composable
private fun FoodDayNavigation(state: FoodState, onSelect: (String) -> Unit) {
    val previousLabel = stringResource(DesignR.string.day_previous)
    val nextLabel = stringResource(DesignR.string.day_next)
    Text(fullFoodDayLabel(state.selectedDay), modifier = Modifier.testTag("food_date").semantics { heading() },
        style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onSelect(shiftFoodDay(state.selectedDay, -1)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("food_previous_day")
                .semantics { contentDescription = previousLabel }) {
            Text("‹", style = MaterialTheme.typography.headlineMedium)
        }
        TextButton(onClick = { onSelect(state.today) }, enabled = state.selectedDay != state.today,
            modifier = Modifier.heightIn(min = 48.dp).testTag("food_today")) {
            Text(stringResource(DesignR.string.day_today))
        }
        TextButton(onClick = { onSelect(shiftFoodDay(state.selectedDay, 1)) }, enabled = state.selectedDay < state.today,
            modifier = Modifier.heightIn(min = 48.dp).testTag("food_next_day")
                .semantics { contentDescription = nextLabel }) {
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun FoodWeekStrip(day: String, today: String, days: List<FoodWeekDay>, onSelect: (String) -> Unit) {
    val start = foodWeekStart(day)
    val largest = days.maxOfOrNull { it.energyKcal.toDoubleOrNull() ?: 0.0 } ?: 0.0
    val weekLabel = stringResource(DesignR.string.food_week)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("food_week")
        .semantics { contentDescription = weekLabel }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(7) { index ->
            val date = shiftFoodDay(start, index)
            val data = days.find { it.date == date }
            val energy = data?.energyKcal?.toDoubleOrNull()
            val target = data?.target?.toDoubleOrNull()?.takeIf { it > 0 }
            val over = energy != null && target != null && energy > target
            val share = if (energy == null || energy <= 0 || (target ?: largest) <= 0) 0f
                else ((energy / (target ?: largest)) * if (target == null) 1.0 else .8).coerceIn(.06, 1.0).toFloat()
            val label = when {
                energy == null || energy == 0.0 -> stringResource(DesignR.string.food_week_empty, fullFoodDayLabel(date))
                data?.target != null -> stringResource(if (over) DesignR.string.food_week_over else DesignR.string.food_week_of,
                    fullFoodDayLabel(date), formatFoodAmount(data.energyKcal, 0), formatFoodAmount(data.target, 0))
                else -> stringResource(DesignR.string.food_week_kcal, fullFoodDayLabel(date), formatFoodAmount(data?.energyKcal, 0))
            } + if (data?.completed == true) ", " + stringResource(DesignR.string.food_completed) else ""
            Surface(shape = RoundedCornerShape(12.dp), color = if (date == day) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface, modifier = Modifier.width(48.dp).heightIn(min = 120.dp)
                    .clickable(enabled = date <= today) { onSelect(date) }.testTag("food_day_$date")
                    .semantics { contentDescription = label; selected = date == day }) {
                Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(foodWeekdayLabel(date), style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.width(12.dp).height(40.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp * share)
                            .background(if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                        if (target != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 31.dp).fillMaxWidth()
                            .height(2.dp).background(MaterialTheme.colorScheme.surface))
                    }
                    Text(date.takeLast(2).toInt().toString(), style = MaterialTheme.typography.labelLarge)
                    Text(if (data?.completed == true) "✓" else "", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun FoodDaySummary(day: FoodDay) {
    FoodSection(null, "food_summary") {
        FoodHeading(DesignR.string.food_eaten)
        FoodTotalValue(day.totals["energy_kcal"], "food_total_energy_kcal", 0, MaterialTheme.typography.headlineLarge)
        Text(day.goal?.calorieTarget?.let { stringResource(DesignR.string.food_of, formatFoodAmount(it, 0)) }
            ?: stringResource(DesignR.string.food_kcal))
        val energy = day.totals["energy_kcal"]?.known?.toDoubleOrNull()
        val target = day.goal?.calorieTarget?.toDoubleOrNull()?.takeIf { it > 0 }
        if (energy != null && target != null) ProgressBar(progress = { (energy / target).coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth())
        day.remainingKcal?.takeIf { day.remainingComplete }?.let { remaining ->
            Text(stringResource(if (remaining.startsWith("-")) DesignR.string.food_over else DesignR.string.food_remaining,
                formatFoodAmount(remaining.removePrefix("-"), 0)), modifier = Modifier.testTag("food_remaining"))
        }
        if (day.goal == null) Text(stringResource(DesignR.string.food_no_goal), style = MaterialTheme.typography.bodySmall)
        if (day.totals["energy_kcal"]?.complete == false) Text(stringResource(DesignR.string.food_incomplete),
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        foodMacroLabels.forEach { (key, label) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                FoodTotalValue(day.totals[key], "food_total_$key")
                day.goal?.macroTargets?.get(key)?.let { macroTarget ->
                    Text(stringResource(DesignR.string.personal_target,
                        stringResource(DesignR.string.food_grams, formatFoodAmount(macroTarget))),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FoodTotalValue(total: FoodTotal?, tag: String, fraction: Int = 1,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    val visible = total?.takeUnless { it.unknown }
    val value = if (visible == null) "—" else (if (!visible.complete) "≥ " else "") + formatFoodAmount(visible.known, fraction)
    val accessible = if (visible == null) stringResource(DesignR.string.food_no_data)
        else (if (!visible.complete) stringResource(DesignR.string.food_at_least) + " " else "") + formatFoodAmount(visible.known, fraction)
    Text(value, modifier = Modifier.testTag(tag).semantics { contentDescription = accessible }, style = style)
}

@Composable
private fun FoodItemCard(item: FoodItem, canWrite: Boolean, selecting: Boolean, selected: Boolean,
    onToggle: () -> Unit, onRepeat: () -> Unit, onOpen: () -> Unit) {
    val selectable = item is FoodEntry && item.dishId == null
    OutlinedCard(onClick = { if (selecting && selectable) onToggle() else onOpen() },
        modifier = Modifier.fillMaxWidth().testTag("food_edit_${item.kind}_${item.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selecting && selectable && canWrite) Checkbox(checked = selected, onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("food_select_${item.id}"))
            Text(shownFoodName(item.name), style = MaterialTheme.typography.titleMedium)
            Text(foodQuantityLabel(item.quantity, item.unit), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("food_quantity_${item.id}"))
            Text((if (item.estimated) "≈ " else "") + formatFoodAmount(item.nutrients["energy_kcal"], 0) +
                " " + stringResource(DesignR.string.food_kcal), modifier = Modifier.testTag("food_item_energy_${item.id}"))
            if (item is FoodDish) Text(localized(GeneratedStrings.Food.dishOf(item.components.size.toDouble(),
                item.components.size.toString())), style = MaterialTheme.typography.bodySmall)
            if (item is FoodDish && canWrite && !selecting) TextButton(onClick = onRepeat,
                modifier = Modifier.heightIn(min = 48.dp).testTag("food_repeat_${item.id}")) {
                Text(stringResource(DesignR.string.food_repeat_last, shownFoodName(item.name)))
            }
        }
    }
}

@Composable
private fun FoodPending(state: FoodState, controller: FoodController) {
    if (state.pending.isEmpty()) return
    val scope = rememberCoroutineScope()
    FoodSection(DesignR.string.outcome_unknown_title, "food_pending") {
        Text(stringResource(DesignR.string.outcome_unknown_text), style = MaterialTheme.typography.bodySmall)
        state.pending.forEach { pending ->
            TextButton(onClick = { scope.launch { controller.check(pending.key) } }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("food_pending_check_${pending.key}")) {
                Text(stringResource(DesignR.string.outcome_check))
            }
            if ((pending.state == SubmissionState.PREPARED && !pending.mayHaveBeenAdmitted) ||
                (pending.state == SubmissionState.UNCERTAIN && pending.failureStatus == 404)) {
                TextButton(onClick = { scope.launch { controller.retry(pending.key) } }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("food_pending_retry_${pending.key}")) {
                    Text(stringResource(DesignR.string.common_retry))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodEditSheet(state: FoodState, draft: FoodEditDraft, controller: FoodController) {
    val scope = rememberCoroutineScope()
    var confirmingDelete by remember(draft.id) { mutableStateOf(false) }
    var editComponents by remember(draft.id) { mutableStateOf(draft.components != draft.originalComponents) }
    val editable = !state.busy && state.pending.isEmpty()
    var remembering by remember(draft.id) { mutableStateOf(false) }
    var compositionName by remember(draft.id) { mutableStateOf(draft.originalName) }
    val item = state.day.data?.items?.find { it.id == draft.id && it.kind == draft.kind }
    val partOfDish = item is FoodEntry && item.dishId != null
    val valid = parseFoodAmount(draft.quantity) != null && validFoodDate(draft.date) &&
        (!editComponents || draft.components.values.all { parseFoodAmount(it) != null }) && draft.name.isNotBlank()
    val changed = !sameFoodAmount(parseFoodAmount(draft.quantity), draft.originalQuantity) ||
        draft.date != draft.originalDate || draft.name != draft.originalName || draft.mealKind != draft.originalMealKind ||
        draft.components.any { (id, amount) -> !sameFoodAmount(parseFoodAmount(amount), draft.originalComponents[id]) }
    val close = { scope.launch { controller.dismissEdit() }; Unit }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp)
            .testTag("food_edit_sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(shownFoodName(draft.name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            item?.let { FoodItemNutrients(it) }
            if (state.conflict) {
                StatusMessage(GeneratedStrings.Food.conflict, "food_edit_conflict")
                StatusMessage(GeneratedStrings.Food.draftPreserved, "food_edit_draft_preserved")
                item?.let { Text(foodQuantityLabel(it.quantity, it.unit), modifier = Modifier.testTag("food_edit_current_quantity")) }
            }
            state.error?.let { ErrorMessage(it, "food_edit_error") }
            FoodPending(state, controller)
            if (partOfDish) Text(stringResource(DesignR.string.food_ingredient_hint))
            if (state.canWrite && !partOfDish) {
                if (draft.kind == "dish") OutlinedTextField(value = draft.name,
                    onValueChange = { controller.updateDraft(draft.copy(name = it)) },
                    enabled = editable, label = { Text(stringResource(DesignR.string.food_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("food_edit_name"))
                if (!editComponents) {
                    OutlinedTextField(value = draft.quantity, onValueChange = { controller.updateDraft(draft.copy(quantity = it)) },
                        enabled = editable, label = { Text(stringResource(if (draft.kind == "dish")
                            DesignR.string.food_dish_quantity else DesignR.string.food_quantity) + ", " + foodUnitLabel(draft.unit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                        isError = parseFoodAmount(draft.quantity) == null,
                        modifier = Modifier.fillMaxWidth().testTag("food_edit_quantity"))
                    if (parseFoodAmount(draft.quantity) == null) Text(stringResource(DesignR.string.food_invalid_quantity),
                        color = MaterialTheme.colorScheme.error)
                    if (draft.kind == "dish") Text(stringResource(DesignR.string.food_scale_hint), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = draft.date, onValueChange = { controller.updateDraft(draft.copy(date = it)) },
                    enabled = editable, label = { Text(stringResource(DesignR.string.food_date)) }, singleLine = true,
                    isError = !validFoodDate(draft.date), modifier = Modifier.fillMaxWidth().testTag("food_edit_date"))
                FoodMealKindChoice(draft.mealKind, editable, "food_edit", DesignR.string.food_change_meal_kind,
                    allowNone = draft.originalMealKind == null) {
                    controller.updateDraft(draft.copy(mealKind = it))
                }
            }
            if (item is FoodDish) {
                FoodHeading(DesignR.string.food_components)
                item.components.forEach { component ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(shownFoodName(component.name), style = MaterialTheme.typography.titleSmall)
                        if (editComponents && state.canWrite) OutlinedTextField(
                            value = draft.components[component.id].orEmpty(),
                            onValueChange = { controller.updateDraft(draft.copy(components = draft.components + (component.id to it))) },
                            enabled = editable,
                            label = { Text(stringResource(DesignR.string.food_quantity) + ", " + foodUnitLabel(component.unit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                            isError = parseFoodAmount(draft.components[component.id].orEmpty()) == null,
                            modifier = Modifier.fillMaxWidth().testTag("food_component_quantity_${component.id}"),
                        ) else Text(foodQuantityLabel(component.quantity, component.unit))
                        FoodItemNutrients(component)
                    }
                }
                if (!editComponents && state.canWrite) TextButton(onClick = {
                    editComponents = true
                    controller.updateDraft(draft.copy(quantity = draft.originalQuantity))
                }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp).testTag("food_edit_components")) {
                    Text(stringResource(DesignR.string.food_edit_components))
                }
                if (state.canWrite && !changed) {
                    if (remembering) {
                        OutlinedTextField(value = compositionName, onValueChange = { compositionName = it },
                            enabled = editable, label = { Text(stringResource(DesignR.string.food_remember_name)) },
                            modifier = Modifier.fillMaxWidth().testTag("food_composition_name"))
                        Button(onClick = { scope.launch {
                            val previousReceipt = controller.state.value?.receipt?.operationId
                            controller.saveComposition(item, compositionName.trim())
                            if (controller.state.value?.receipt?.operationId != previousReceipt &&
                                controller.state.value?.error == null && controller.state.value?.pending?.isEmpty() == true) {
                                controller.dismissEdit()
                            }
                        } }, enabled = editable && compositionName.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("food_composition_save")) {
                            Text(stringResource(DesignR.string.food_remember))
                        }
                    } else TextButton(onClick = { remembering = true }, enabled = editable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("food_composition_remember")) {
                        Text(stringResource(DesignR.string.food_remember))
                    }
                }
            }
            if (state.canWrite && !partOfDish) {
                Button(onClick = { scope.launch { controller.submitEdit() } },
                    enabled = valid && changed && !state.busy && state.pending.isEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_edit_save")) {
                    Text(stringResource(if (editComponents) DesignR.string.food_save_components else DesignR.string.food_save))
                }
                if (confirmingDelete) {
                    Text(stringResource(DesignR.string.food_delete_confirm, shownFoodName(draft.name)))
                    Button(onClick = { item?.let { scope.launch {
                        val previousReceipt = controller.state.value?.receipt?.operationId
                        controller.deleteItem(it)
                        if (controller.state.value?.receipt?.operationId != previousReceipt &&
                            controller.state.value?.error == null && controller.state.value?.pending?.isEmpty() == true) {
                            controller.dismissEdit()
                        }
                    } } }, enabled = !state.busy && state.pending.isEmpty() && item != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_delete_confirm")) {
                        Text(stringResource(DesignR.string.food_delete))
                    }
                } else TextButton(onClick = { confirmingDelete = true }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("food_delete")) {
                    Text(stringResource(DesignR.string.food_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = close, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("food_edit_close")) {
                Text(stringResource(DesignR.string.common_close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodGroupSheet(state: FoodState, ids: List<String>, controller: FoodController, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(ids) { mutableStateOf("") }
    var quantity by remember(ids) { mutableStateOf("") }
    var unit by remember(ids) { mutableStateOf(state.day.data?.entries?.firstOrNull { it.id in ids }?.unit.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp)
            .testTag("food_group_sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FoodHeading(DesignR.string.food_group)
            state.day.data?.entries?.filter { it.id in ids }?.forEach { Text(shownFoodName(it.name)) }
            OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !state.busy,
                label = { Text(stringResource(DesignR.string.food_name)) },
                modifier = Modifier.fillMaxWidth().testTag("food_group_name"))
            OutlinedTextField(value = quantity, onValueChange = { quantity = it }, enabled = !state.busy,
                label = { Text(stringResource(DesignR.string.food_dish_quantity)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("food_group_quantity"))
            Text(stringResource(DesignR.string.food_missing_unit), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("g", "ml", "serving").forEach { choice ->
                    OutlinedButton(onClick = { unit = choice }, enabled = !state.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("food_group_unit_$choice")
                            .semantics { selected = unit == choice }) {
                        Text(foodUnitLabel(choice))
                    }
                }
            }
            state.error?.let { ErrorMessage(it, "food_group_error") }
            Button(onClick = { scope.launch {
                controller.groupEntries(ids, name.trim(), requireNotNull(parseFoodAmount(quantity)), unit.trim())
                if (controller.state.value?.error == null && controller.state.value?.pending?.isEmpty() == true) onDismiss()
            } }, enabled = !state.busy && name.isNotBlank() && parseFoodAmount(quantity) != null && unit.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_group_save")) {
                Text(stringResource(DesignR.string.food_group))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(DesignR.string.common_cancel))
            }
        }
    }
}

@Composable
private fun FoodItemNutrients(item: FoodItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatFoodAmount(item.nutrients["energy_kcal"], 0) + " " + stringResource(DesignR.string.food_kcal))
        foodMacroLabels.forEach { (key, label) ->
            val value = item.nutrients[key]
            Text(stringResource(label) + ": " + if (value == null) stringResource(DesignR.string.food_no_data)
                else stringResource(DesignR.string.food_grams, formatFoodAmount(value)),
                style = MaterialTheme.typography.bodySmall)
        }
        if (item.estimated) Text(stringResource(DesignR.string.food_estimated), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FoodMealKindChoice(value: String?, enabled: Boolean, tag: String, label: Int, allowNone: Boolean,
    onSelect: (String?) -> Unit) {
    var expanded by remember(tag) { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tag}_meal_kind")) {
        Text(stringResource(label) + ": " + foodMealKindLabel(value?.let(FoodMealKind::fromWire)))
    }
    if (expanded) Column(Modifier.fillMaxWidth()) {
        (FoodMealKind.entries.map { it } + if (allowNone) listOf(null) else emptyList()).forEach { kind ->
            TextButton(onClick = { onSelect(kind?.wire); expanded = false }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tag}_meal_kind_${kind?.wire ?: "none"}")) {
                Text(foodMealKindLabel(kind))
            }
        }
    }
}

@Composable
private fun <T> FoodQueryStatus(query: FoodQuery<T>, tag: String, retry: () -> Unit) {
    if (query.loading) {
        ProgressBar(modifier = Modifier.fillMaxWidth())
        StatusMessage(GeneratedStrings.State.loading, "food_loading_$tag")
    }
    query.error?.let {
        ErrorMessage(it, "food_error_$tag")
        TextButton(onClick = retry, enabled = !query.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("food_retry_$tag")) {
            Text(stringResource(DesignR.string.common_retry))
        }
    }
    if (query.stale) StatusMessage(if (query.error == null) GeneratedStrings.State.stale
        else GeneratedStrings.State.refreshFailed, "food_stale_$tag")
}

@Composable
private fun FoodSection(title: Int?, tag: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { FoodHeading(it) }
            content()
        }
    }
}

@Composable
private fun FoodHeading(title: Int) = Text(stringResource(title), modifier = Modifier.semantics { heading() },
    style = MaterialTheme.typography.titleMedium)

@Composable
private fun foodMealKindLabel(kind: FoodMealKind?): String = stringResource(when (kind) {
    FoodMealKind.Breakfast -> DesignR.string.food_meal_kinds_breakfast
    FoodMealKind.Lunch -> DesignR.string.food_meal_kinds_lunch
    FoodMealKind.Dinner -> DesignR.string.food_meal_kinds_dinner
    FoodMealKind.Snack -> DesignR.string.food_meal_kinds_snack
    null -> DesignR.string.food_no_meal_kind
})

@Composable
private fun foodQuantityLabel(quantity: String, unit: String): String = if (unit == "g")
    stringResource(DesignR.string.food_grams, formatFoodAmount(quantity))
else formatFoodAmount(quantity) + " " + foodUnitLabel(unit)

@Composable
private fun foodUnitLabel(unit: String): String = when (unit) {
    "g" -> stringResource(DesignR.string.food_grams, "").trim()
    "ml" -> stringResource(DesignR.string.food_millilitres, "").trim()
    "serving" -> stringResource(DesignR.string.food_servings)
    else -> unit
}

private val foodMacroLabels = listOf(
    "protein_g" to DesignR.string.food_macros_protein_g,
    "fat_g" to DesignR.string.food_macros_fat_g,
    "carbohydrate_g" to DesignR.string.food_macros_carbohydrate_g,
    "fiber_g" to DesignR.string.food_macros_fiber_g,
)
private val foodDateLocale = Locale.forLanguageTag("ru-RU")
private fun fullFoodDayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", foodDateLocale))
}.getOrDefault(day)
private fun foodWeekdayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EE", foodDateLocale))
}.getOrDefault(day)
private fun foodTime(at: String, timezone: String): String? = runCatching {
    Instant.parse(at).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("HH:mm", foodDateLocale))
}.getOrNull()
private fun validFoodDate(day: String): Boolean = runCatching { LocalDate.parse(day).toString() == day }.getOrDefault(false)
private fun shownFoodName(value: String): String = value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(foodDateLocale) else it.toString() }
