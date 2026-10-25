package assistant.mobile.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.food.FoodAddState
import assistant.mobile.core.food.FoodCatalogItem
import assistant.mobile.core.food.FoodComposition
import assistant.mobile.core.food.FoodController
import assistant.mobile.core.food.FoodMealKind
import assistant.mobile.core.food.FoodPortionDraft
import assistant.mobile.core.food.FoodQuery
import assistant.mobile.core.food.FoodRemembered
import assistant.mobile.core.food.FoodState
import assistant.mobile.core.food.FoodTotal
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.core.food.parseFoodAmount
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.design.R as DesignR
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Catalog selection and typed estimates stay separate from the explicit ledger write. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodAddScreen(
    controller: FoodController,
    state: FoodState,
    onOpenAssistant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val observed by controller.additions.collectAsStateWithLifecycle()
    val add = observed?.takeIf { it.open } ?: return
    val scope = rememberCoroutineScope()
    var label by remember(add.open) { mutableStateOf(false) }
    var labelInitial by remember(add.open) { mutableStateOf("") }
    val editable = state.canWrite && !state.busy && state.pending.isEmpty()
    val close = { if (!state.busy) controller.closeAdd(); Unit }
    val nested = label || add.selection != null || add.chosenComposition != null
    val back = {
        if (!state.busy) {
            label = false
            controller.clearAddSelection()
        }
        Unit
    }
    ModalBottomSheet(onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BackHandler(enabled = nested, onBack = back)
        NativeTopBar(when {
            label -> DesignR.string.food_label_title
            add.selection != null -> DesignR.string.food_portion_title
            add.chosenComposition != null -> DesignR.string.food_dishes_title
            else -> DesignR.string.food_add_title
        }, onBack = back.takeIf { nested }, includeSharedActions = false)
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(20.dp)
            .testTag("food_add_sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { ErrorMessage(it, "food_add_error") }
            if (state.conflict) StatusMessage(GeneratedStrings.Food.conflict, "food_add_conflict")
            if (state.pending.isNotEmpty()) {
                StatusMessage(GeneratedStrings.Outcome.unknownTitle, "food_add_pending")
                state.pending.forEach { pending ->
                    TextButton(onClick = { scope.launch { controller.check(pending.key) } }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("food_add_check_${pending.key}")) {
                        Text(stringResource(DesignR.string.outcome_check))
                    }
                    val retryable = (pending.state == SubmissionState.PREPARED && !pending.mayHaveBeenAdmitted) ||
                        (pending.state == SubmissionState.UNCERTAIN && pending.failureStatus == 404)
                    if (retryable) TextButton(onClick = { scope.launch { controller.retry(pending.key) } }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("food_add_retry_${pending.key}")) {
                        Text(stringResource(DesignR.string.common_retry))
                    }
                }
            }
            when {
                label -> FoodLabelForm(labelInitial, editable, onSubmit = { name, brand, basis, unit, nutrients ->
                    scope.launch {
                        controller.createFood(name, brand, basis, unit, nutrients)
                        if (controller.additions.value?.selection != null) label = false
                    }
                })
                add.selection != null -> FoodAddPortion(add, requireNotNull(add.selection), controller, editable)
                add.chosenComposition != null -> FoodAddComposition(add, requireNotNull(add.chosenComposition), controller, editable)
                else -> FoodAddBrowser(add, controller, editable, onLabel = { initial ->
                    labelInitial = initial
                    label = true
                }, onOpenAssistant = {
                    controller.closeAdd()
                    onOpenAssistant()
                })
            }
            TextButton(onClick = close, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_close")) {
                Text(stringResource(DesignR.string.common_close))
            }
        }
    }
}

private enum class FoodAddTab(val label: Int, val tag: String) {
    Recent(DesignR.string.food_recent, "recent"),
    Favorites(DesignR.string.food_favorites, "favorites"),
    Dishes(DesignR.string.food_dishes_tab, "dishes"),
    Catalog(DesignR.string.food_search_personal, "catalog"),
}

@Composable
private fun FoodAddBrowser(add: FoodAddState, controller: FoodController, editable: Boolean,
    onLabel: (String) -> Unit, onOpenAssistant: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(add.query) }
    var tab by remember { mutableStateOf(FoodAddTab.Recent) }
    LaunchedEffect(text) {
        delay(250)
        val query = text.trim()
        if (controller.additions.value?.query != query) controller.searchFoods(query)
    }
    OutlinedTextField(value = text, onValueChange = { if (it.length <= 200) text = it },
        label = { Text(stringResource(DesignR.string.food_search_placeholder)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("food_add_search"))
    OutlinedButton(onClick = { onLabel(text.trim()) }, enabled = editable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_label")) {
        Text(stringResource(DesignR.string.food_add_label))
    }
    if (text.isBlank()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FoodAddTab.entries.forEach { choice ->
            OutlinedButton(onClick = { tab = choice }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("food_add_tab_${choice.tag}").semantics { selected = tab == choice }) {
                Text(stringResource(choice.label))
            }
        }
    }
    val showCatalog = text.isNotBlank() || tab == FoodAddTab.Catalog
    val showDishes = text.isNotBlank() || tab == FoodAddTab.Dishes
    if (showDishes) {
        if (text.isNotBlank() && !add.compositions.data.isNullOrEmpty()) FoodAddHeading(DesignR.string.food_dishes_tab)
        FoodAddQueryStatus(add.compositions, "compositions") { scope.launch { controller.refreshAdd() } }
        if (!add.compositions.loading && add.compositions.error == null && add.compositions.data.isNullOrEmpty() &&
            tab == FoodAddTab.Dishes && text.isBlank()) Text(stringResource(DesignR.string.food_dishes_empty))
        add.compositions.data.orEmpty().forEach { composition ->
            FoodCompositionCard(composition, editable, onSelect = { controller.selectComposition(composition) }, onRepeat = {
                controller.selectComposition(composition)
                scope.launch { controller.logComposition() }
            })
        }
    }
    if (showCatalog) {
        FoodAddQueryStatus(add.foods, "search") { scope.launch { controller.refreshAdd() } }
        if (!add.foods.loading && add.foods.error == null && add.foods.data.isNullOrEmpty() && text.isNotBlank() &&
            add.compositions.data.isNullOrEmpty()) {
            FoodAddHeading(DesignR.string.food_search_nothing)
            Text(stringResource(DesignR.string.food_search_nothing_text))
            TextButton(onClick = onOpenAssistant, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("food_add_assistant")) { Text(stringResource(DesignR.string.food_ask_assistant)) }
        } else if (text.isBlank()) FoodAddHeading(DesignR.string.food_search_personal)
        add.foods.data.orEmpty().forEach { food ->
            FoodCatalogCard(food, editable) { controller.selectFood(food) }
        }
    } else if (!showDishes) {
        FoodAddQueryStatus(add.memory, "memory") { scope.launch { controller.refreshAdd() } }
        val remembered = if (tab == FoodAddTab.Favorites) add.favorites else add.recent
        if (!add.memory.loading && add.memory.error == null && remembered.isEmpty()) {
            Text(stringResource(if (tab == FoodAddTab.Favorites) DesignR.string.food_favorites_empty else DesignR.string.food_memory_empty))
        }
        remembered.forEach { food ->
            FoodRememberedCard(food, editable, onSelect = { scope.launch {
                controller.selectRemembered(food)
                val current = controller.additions.value
                if (current?.open == true && current.selection == null) text = current.query
            } },
                onFavorite = { scope.launch { controller.toggleFavorite(food) } },
                onRepeat = { scope.launch { controller.repeatRemembered(food) } })
        }
    }
}

@Composable
private fun FoodCatalogCard(food: FoodCatalogItem, enabled: Boolean, onSelect: () -> Unit) {
    OutlinedCard(onClick = onSelect, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("food_add_result_${food.snapshotId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(food.name, style = MaterialTheme.typography.titleMedium)
            if (food.personal) Text(stringResource(DesignR.string.food_personal), style = MaterialTheme.typography.labelSmall)
            food.brand?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(DesignR.string.food_per, formatFoodAmount(food.nutrients["energy_kcal"], 0),
                formatFoodAmount(food.basisQuantity), foodAddUnitLabel(food.basisUnit)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FoodRememberedCard(food: FoodRemembered, enabled: Boolean,
    onSelect: () -> Unit, onFavorite: () -> Unit, onRepeat: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSelect, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_memory_${food.foodId}")) {
                Text(food.name, style = MaterialTheme.typography.titleMedium)
            }
            if (food.lastQuantity != null && food.lastUnit != null) Text(stringResource(DesignR.string.food_last_time,
                foodAddQuantityLabel(requireNotNull(food.lastQuantity), requireNotNull(food.lastUnit))), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onFavorite, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_favorite_${food.foodId}")
                    .semantics { selected = food.favorite }) {
                Text(stringResource(if (food.favorite) DesignR.string.food_unfavorite else DesignR.string.food_favorite))
            }
            if (food.snapshotId != null && food.lastQuantity != null && food.lastUnit != null) TextButton(onClick = onRepeat,
                enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_repeat_${food.foodId}")) {
                Text(stringResource(DesignR.string.food_repeat_last, food.name))
            }
        }
    }
}

@Composable
private fun FoodCompositionCard(composition: FoodComposition, enabled: Boolean, onSelect: () -> Unit, onRepeat: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSelect, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_composition_${composition.id}")) {
                Text(composition.name, style = MaterialTheme.typography.titleMedium)
            }
            composition.components.forEach { component ->
                Text(component.label + " · " + foodAddQuantityLabel(component.quantity, component.unit),
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRepeat, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("food_add_composition_log_${composition.id}")) {
                Text(stringResource(DesignR.string.food_log_one_serving, composition.name))
            }
        }
    }
}

@Composable
private fun FoodAddPortion(add: FoodAddState, draft: FoodPortionDraft, controller: FoodController, editable: Boolean) {
    val scope = rememberCoroutineScope()
    val valid = parseFoodAmount(if (draft.portion == null) draft.quantity else draft.count) != null
    LaunchedEffect(draft) {
        delay(300)
        if (valid) controller.estimatePortion()
    }
    Column(Modifier.fillMaxWidth().testTag("food_add_portion"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(draft.food.name, style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = draft.quantity, onValueChange = {
            controller.choosePortion(null)
            controller.updatePortion(it, draft.food.basisUnit)
        }, enabled = editable, label = {
            Text(stringResource(DesignR.string.food_quantity) + ", " + foodAddUnitLabel(draft.food.basisUnit))
        }, singleLine = true, isError = draft.portion == null && !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("food_add_quantity"))
        if (!valid) Text(stringResource(DesignR.string.food_invalid_quantity), color = MaterialTheme.colorScheme.error)
        val portions = draft.food.savedPortions + draft.food.portions
        if (portions.isNotEmpty()) {
            FoodAddHeading(DesignR.string.food_portions)
            portions.forEach { portion ->
                OutlinedButton(onClick = { controller.choosePortion(portion) }, enabled = editable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_portion_${portion.id}")
                        .semantics { selected = draft.portion == portion }) {
                    Text(portion.name + " · " + foodAddQuantityLabel(portion.quantity, portion.unit))
                }
            }
        }
        if (draft.portion != null) OutlinedTextField(value = draft.count,
            onValueChange = controller::updatePortionCount, enabled = editable,
            label = { Text(stringResource(DesignR.string.food_servings)) }, singleLine = true, isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("food_add_count"))
        FoodAddQueryStatus(add.estimate, "estimate") { scope.launch { controller.estimatePortion() } }
        add.estimate.data?.let { estimate ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("food_add_preview").semantics { liveRegion = LiveRegionMode.Polite }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(DesignR.string.food_estimate), style = MaterialTheme.typography.labelLarge)
                    FoodAddTotal(estimate.totals["energy_kcal"], 0, "food_add_preview_energy")
                    Text(stringResource(DesignR.string.food_kcal), style = MaterialTheme.typography.bodySmall)
                    foodAddMacros.forEach { (nutrient, label) ->
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                        FoodAddTotal(estimate.totals[nutrient], 1, "food_add_preview_$nutrient",
                            unit = foodAddUnitLabel("g"), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (draft.portion?.revision == null && draft.portion != null) Text(stringResource(DesignR.string.food_estimated))
                }
            }
        }
        FoodAddMealChoice(add.mealKind, editable, controller::updateAddMealKind)
        Button(onClick = { scope.launch { controller.recordPortion() } }, enabled = editable && valid,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_record")) {
            Text(stringResource(DesignR.string.food_record))
        }
    }
}

@Composable
private fun FoodAddComposition(add: FoodAddState, composition: FoodComposition, controller: FoodController, editable: Boolean) {
    val scope = rememberCoroutineScope()
    Text(composition.name, style = MaterialTheme.typography.titleLarge)
    composition.components.forEach { component ->
        Text(component.label + " · " + foodAddQuantityLabel(component.quantity, component.unit))
    }
    OutlinedTextField(value = add.compositionCount, onValueChange = controller::updateCompositionCount,
        enabled = editable, label = { Text(stringResource(DesignR.string.food_servings)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = parseFoodAmount(add.compositionCount) == null,
        modifier = Modifier.fillMaxWidth().testTag("food_add_composition_count"))
    FoodAddMealChoice(add.mealKind, editable, controller::updateAddMealKind)
    Button(onClick = { scope.launch { controller.logComposition() } }, enabled = editable && parseFoodAmount(add.compositionCount) != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_log_composition")) {
        Text(stringResource(DesignR.string.food_log_dish))
    }
}

@Composable
private fun FoodLabelForm(initial: String, editable: Boolean,
    onSubmit: (String, String, String, String, Map<String, String?>) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var brand by remember { mutableStateOf("") }
    var basis by remember { mutableStateOf("100") }
    var unit by remember { mutableStateOf("g") }
    var values by remember { mutableStateOf(emptyMap<String, String>()) }
    Text(stringResource(DesignR.string.food_label_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = name, onValueChange = { if (it.length <= 200) name = it }, enabled = editable,
        label = { Text(stringResource(DesignR.string.food_name)) },
        modifier = Modifier.fillMaxWidth().testTag("food_label_name"))
    OutlinedTextField(value = brand, onValueChange = { if (it.length <= 200) brand = it }, enabled = editable,
        label = { Text(stringResource(DesignR.string.food_brand)) },
        modifier = Modifier.fillMaxWidth().testTag("food_label_brand"))
    OutlinedTextField(value = basis, onValueChange = { basis = it }, enabled = editable,
        label = { Text(stringResource(DesignR.string.food_basis)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = parseFoodAmount(basis) == null,
        modifier = Modifier.fillMaxWidth().testTag("food_label_basis"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("g", "ml").forEach { choice ->
            OutlinedButton(onClick = { unit = choice }, enabled = editable,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("food_label_unit_$choice")
                    .semantics { selected = unit == choice }) { Text(foodAddUnitLabel(choice)) }
        }
    }
    foodLabelNutrients.forEach { (nutrient, label) ->
        val value = values[nutrient].orEmpty()
        OutlinedTextField(value = value, onValueChange = { values = values + (nutrient to it) }, enabled = editable,
            label = { Text(stringResource(label)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = value.isNotBlank() && foodLabelAmount(value) == null,
            modifier = Modifier.fillMaxWidth().testTag("food_label_$nutrient"))
    }
    Button(onClick = {
        onSubmit(name.trim(), brand.trim(), requireNotNull(parseFoodAmount(basis)), unit,
            foodLabelNutrients.associate { (nutrient, _) -> nutrient to values[nutrient]?.let(::foodLabelAmount) })
    }, enabled = editable && name.isNotBlank() && parseFoodAmount(basis) != null &&
        values.values.all { it.isBlank() || foodLabelAmount(it) != null },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_label_create")) {
        Text(stringResource(DesignR.string.food_create_product))
    }
}

@Composable
private fun FoodAddMealChoice(value: FoodMealKind?, enabled: Boolean, onSelect: (FoodMealKind?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_meal_kind")) {
        Text(stringResource(DesignR.string.food_meal_kind) + ": " + foodAddMealLabel(value))
    }
    if (expanded) (FoodMealKind.entries.map { it } + listOf(null)).forEach { kind ->
        TextButton(onClick = { onSelect(kind); expanded = false }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_meal_kind_${kind?.wire ?: "none"}")) {
            Text(foodAddMealLabel(kind))
        }
    }
}

@Composable
private fun FoodAddTotal(total: FoodTotal?, fraction: Int, tag: String, unit: String = "",
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineMedium) {
    val visible = total?.takeUnless { it.unknown }
    val amount = formatFoodAmount(visible?.known, fraction) + if (visible != null && unit.isNotEmpty()) " $unit" else ""
    val label = if (visible == null) stringResource(DesignR.string.food_no_data)
        else (if (!visible.complete) stringResource(DesignR.string.food_at_least) + " " else "") + amount
    Text((if (visible != null && !visible.complete) "≥ " else "") + amount,
        style = style,
        modifier = Modifier.testTag(tag).semantics { contentDescription = label })
}

@Composable
private fun <T> FoodAddQueryStatus(query: FoodQuery<T>, tag: String, retry: () -> Unit) {
    if (query.loading) {
        ProgressBar(modifier = Modifier.fillMaxWidth())
        StatusMessage(GeneratedStrings.State.loading, "food_add_loading_$tag")
    }
    query.error?.let { error ->
        ErrorMessage(error, "food_add_error_$tag")
        TextButton(onClick = retry, enabled = !query.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_add_retry_$tag")) {
            Text(stringResource(DesignR.string.common_retry))
        }
    }
    if (query.stale) StatusMessage(if (query.error == null) GeneratedStrings.State.stale
        else GeneratedStrings.State.refreshFailed, "food_add_stale_$tag")
}

@Composable
private fun FoodAddHeading(title: Int) = Text(stringResource(title), style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.semantics { heading() })

@Composable
private fun foodAddMealLabel(value: FoodMealKind?): String = stringResource(when (value) {
    FoodMealKind.Breakfast -> DesignR.string.food_meal_kinds_breakfast
    FoodMealKind.Lunch -> DesignR.string.food_meal_kinds_lunch
    FoodMealKind.Dinner -> DesignR.string.food_meal_kinds_dinner
    FoodMealKind.Snack -> DesignR.string.food_meal_kinds_snack
    null -> DesignR.string.food_no_meal_kind
})

@Composable
private fun foodAddUnitLabel(unit: String): String = when (unit) {
    "g" -> stringResource(DesignR.string.food_grams, "").trim()
    "ml" -> stringResource(DesignR.string.food_millilitres, "").trim()
    "serving" -> stringResource(DesignR.string.food_servings)
    else -> unit
}

@Composable
private fun foodAddQuantityLabel(quantity: String, unit: String): String = if (unit == "g")
    stringResource(DesignR.string.food_grams, formatFoodAmount(quantity))
else if (unit == "ml") stringResource(DesignR.string.food_millilitres, formatFoodAmount(quantity))
else formatFoodAmount(quantity) + " " + foodAddUnitLabel(unit)

// The label accepts zero nutrient values; the positive quantity parser deliberately does not.
private fun foodLabelAmount(value: String): String? {
    val normalized = value.trim().replace(',', '.')
    if (normalized.isEmpty() || normalized.count { it == '.' } > 1 || normalized.any { it !in '0'..'9' && it != '.' } ||
        normalized.startsWith('.') || normalized.endsWith('.')) return null
    return normalized
}

private val foodAddMacros = listOf(
    "protein_g" to DesignR.string.food_macros_protein_g,
    "fat_g" to DesignR.string.food_macros_fat_g,
    "carbohydrate_g" to DesignR.string.food_macros_carbohydrate_g,
    "fiber_g" to DesignR.string.food_macros_fiber_g,
)
private val foodLabelNutrients = listOf(
    "energy_kcal" to DesignR.string.food_energy,
    "protein_g" to DesignR.string.food_protein,
    "fat_g" to DesignR.string.food_fat,
    "carbohydrate_g" to DesignR.string.food_carbs,
    "fiber_g" to DesignR.string.food_fiber,
)
