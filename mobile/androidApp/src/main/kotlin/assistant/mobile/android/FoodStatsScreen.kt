package assistant.mobile.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.files.FileExportDestination
import assistant.mobile.core.food.FoodStatsController
import assistant.mobile.core.food.FoodStatsDay
import assistant.mobile.core.food.FoodStatsExportFormat
import assistant.mobile.core.food.FoodStatsKind
import assistant.mobile.core.food.FoodStatsPeriod
import assistant.mobile.core.food.FoodStatsPeriodKind
import assistant.mobile.core.food.FoodStatsState
import assistant.mobile.core.food.FoodStatsSummary
import assistant.mobile.core.food.FoodTotal
import assistant.mobile.core.food.formatFoodAmount
import assistant.mobile.design.GeneratedColors
import assistant.mobile.design.R as DesignR
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** Calendar periods, daily server totals, their coverage and explicit system file export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodStatsScreen(
    controller: FoodStatsController,
    lease: SessionLease,
    onBack: () -> Unit,
    initialDay: String? = null,
    modifier: Modifier = Modifier,
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(controller, lease, owner, initialDay) {
        var firstActivation = true
        var started = false
        fun activate() {
            if (started) return
            started = true
            controller.activate(if (firstActivation) initialDay else null)
            firstActivation = false
        }
        fun deactivate() { started = false; controller.deactivate() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> activate()
                Lifecycle.Event.ON_STOP -> deactivate()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) activate()
        onDispose { owner.lifecycle.removeObserver(observer); deactivate() }
    }
    Column(modifier.fillMaxSize()) {
        NativeTopBar(stringResource(DesignR.string.stats_title), onBack = onBack)
        PullToRefreshBox(isRefreshing = state?.range?.let { it.loading && it.data != null } == true,
            onRefresh = { scope.launch { controller.refresh() } }, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("screen_FoodStats"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state == null) StatusMessage(GeneratedStrings.State.loading, "food_stats_loading")
                state?.let { current ->
                    if (!current.canRead) UnavailableCard() else {
                        FoodStatsNavigation(current, controller)
                        if (current.range.loading) {
                            ProgressBar(modifier = Modifier.fillMaxWidth())
                            StatusMessage(GeneratedStrings.State.loading, "food_stats_loading")
                        }
                        current.range.error?.let { error ->
                            ErrorMessage(error, "food_stats_error")
                            TextButton(onClick = { scope.launch { controller.refresh() } }, enabled = !current.range.loading,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("food_stats_retry")) {
                                Text(stringResource(DesignR.string.common_retry))
                            }
                        }
                        if (current.range.stale) StatusMessage(if (current.range.error == null) GeneratedStrings.State.stale
                            else GeneratedStrings.State.refreshFailed, "food_stats_stale")
                        current.range.data?.let { range ->
                            val summary = current.summary
                            if (summary.logged == 0) {
                                Text(stringResource(DesignR.string.stats_empty), modifier = Modifier.testTag("food_stats_empty"),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(DesignR.string.stats_empty_text))
                            } else {
                                FoodStatsChart(range.days, current.period, current.locale)
                                FoodStatsAverages(summary, current.locale)
                                if (summary.kinds.isNotEmpty()) FoodStatsKinds(summary.kinds, current.locale)
                            }
                        }
                        FoodStatsExport(current, controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodStatsNavigation(state: FoodStatsState, controller: FoodStatsController) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FoodStatsPeriodKind.entries.forEach { kind ->
            OutlinedButton(onClick = { scope.launch { controller.selectKind(kind) } },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("food_stats_${kind.wire}")
                    .semantics { selected = state.period.kind == kind }) {
                Text(stringResource(if (kind == FoodStatsPeriodKind.Week) DesignR.string.stats_week else DesignR.string.stats_month))
            }
        }
    }
    Text(foodStatsTitle(state.period, state.locale), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("food_stats_period").semantics { heading() })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { scope.launch { controller.shiftPeriod(-1) } },
            modifier = Modifier.heightIn(min = 48.dp).testTag("food_stats_previous")) {
            Text(stringResource(DesignR.string.stats_earlier))
        }
        TextButton(onClick = { scope.launch { controller.shiftPeriod(1) } }, enabled = state.canGoLater,
            modifier = Modifier.heightIn(min = 48.dp).testTag("food_stats_next")) {
            Text(stringResource(DesignR.string.stats_later))
        }
    }
}

@Composable
private fun FoodStatsChart(days: List<FoodStatsDay>, period: FoodStatsPeriod, locale: String) {
    val palette = foodStatsPalette()
    val scale = maxOf(1.0, days.maxOfOrNull { maxOf(it.energy?.known?.chartNumber() ?: 0.0, it.target?.chartNumber() ?: 0.0) } ?: 1.0)
    FoodStatsSection(DesignR.string.stats_chart, "food_stats_chart") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("food_stats_bars"),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { day ->
                val amount = statsTotalLabel(day.energy, 0, locale)
                val date = foodStatsDayLabel(day.date, locale)
                val incomplete = stringResource(DesignR.string.personal_incomplete)
                val description = (if (day.logged) localized(GeneratedStrings.Stats.dayBar(date, amount,
                    formatFoodAmount(day.target, 0, locale))) else localized(GeneratedStrings.Stats.dayEmpty(date))) +
                    if (day.incomplete) ". $incomplete" else ""
                val energy = day.energy?.known?.chartNumber() ?: 0.0
                val target = day.target?.chartNumber()
                val over = target != null && energy > target
                Column(Modifier.width(48.dp).semantics(mergeDescendants = true) { contentDescription = description }
                    .testTag("food_stats_day_${day.date}"), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(144.dp)) {
                        val left = 6.dp.toPx()
                        val width = size.width - left * 2
                        val height = (energy / scale).toFloat().coerceIn(0f, 1f) * size.height
                        if (day.logged && height > 0) {
                            drawRect(if (over) palette.getValue("warning-fill") else palette.getValue("chart-1"),
                                Offset(left, size.height - height), Size(width, height))
                            if (day.energy?.complete != true) {
                                var y = size.height - height
                                while (y < size.height) {
                                    drawLine(palette.getValue("foreground"), Offset(left, y), Offset(left + width, y), 1.dp.toPx())
                                    y += 6.dp.toPx()
                                }
                            }
                        }
                        if (target != null && target > 0) {
                            val y = size.height - (target / scale).toFloat().coerceIn(0f, 1f) * size.height
                            drawLine(palette.getValue("foreground"), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                        }
                    }
                    Text(if (period.kind == FoodStatsPeriodKind.Week) foodStatsWeekday(day.date, locale)
                        else day.date.takeLast(2).toInt().toString(), style = MaterialTheme.typography.labelSmall)
                    if (day.incomplete) Text("•", modifier = Modifier.testTag("food_stats_incomplete_${day.date}"))
                }
            }
        }
        if (days.any { it.target != null }) FoodStatsLegend(palette.getValue("foreground"), DesignR.string.stats_legend_goal)
        FoodStatsLegend(palette.getValue("chart-1"), DesignR.string.stats_legend_within)
        if (days.any { (it.energy?.known?.chartNumber() ?: 0.0) > (it.target?.chartNumber() ?: Double.MAX_VALUE) })
            FoodStatsLegend(palette.getValue("warning-fill"), DesignR.string.stats_legend_over)
        if (days.any { it.logged && it.energy?.complete != true }) Text(stringResource(DesignR.string.food_incomplete),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("food_stats_nutrients_incomplete"))
        if (days.any { it.incomplete }) Text(stringResource(DesignR.string.personal_incomplete), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FoodStatsAverages(summary: FoodStatsSummary, locale: String) {
    val energy = summary.energy
    FoodStatsSection(DesignR.string.stats_per_day, "food_stats_summary") {
        Text(localized(GeneratedStrings.Stats.loggedDays(summary.logged.toString(), summary.days.toString())))
        Text(statsTotalLabel(energy, 0, locale) + " " + stringResource(DesignR.string.food_kcal),
            modifier = Modifier.testTag("food_stats_kcal"), style = MaterialTheme.typography.headlineMedium)
        summary.target?.let { target ->
            Text(localized(GeneratedStrings.Stats.goal(formatFoodAmount(target, 0, locale))))
            if (energy?.complete == true) FoodStatsProgress(energy.known, target)
        }
        listOf("protein_g" to DesignR.string.food_macros_protein_g, "fat_g" to DesignR.string.food_macros_fat_g,
            "carbohydrate_g" to DesignR.string.food_macros_carbohydrate_g).forEach { (key, label) ->
            val value = summary.macros[key]
            val average = value?.average
            val target = value?.target
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            val amount = statsTotalLabel(average, 1, locale)
            Text((if (average == null || average.unknown) amount else stringResource(DesignR.string.food_grams, amount)) +
                (target?.let { " / " + formatFoodAmount(it, 0, locale) } ?: ""), modifier = Modifier.testTag("food_stats_$key"))
            if (average?.complete == true && target != null) FoodStatsProgress(average.known, target)
        }
        if (summary.macros.values.any { it.average?.complete != true }) Text(stringResource(DesignR.string.food_incomplete),
            style = MaterialTheme.typography.bodySmall)
        if (summary.withGoal > 0 && summary.energyComplete) {
            Text(localized(GeneratedStrings.Stats.nearGoal(summary.withGoal.toDouble(), summary.nearGoal.toString(), summary.withGoal.toString())))
            if (summary.overGoal > 0) Text(localized(GeneratedStrings.Stats.overGoal(summary.overGoal.toString())))
            if (summary.underGoal > 0) Text(localized(GeneratedStrings.Stats.underGoal(summary.underGoal.toString())))
        }
        if (summary.completed > 0) Text(localized(GeneratedStrings.Stats.completedDays(summary.completed.toDouble(),
            summary.completed.toString(), summary.days.toString())))
    }
}

@Composable
private fun FoodStatsProgress(known: String, target: String) {
    val goal = target.chartNumber()
    if (goal != null && goal > 0) ProgressBar(
        progress = { ((known.chartNumber() ?: 0.0) / goal).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FoodStatsKinds(kinds: List<FoodStatsKind>, locale: String) {
    val palette = foodStatsPalette()
    val kindKeys = listOf("breakfast", "lunch", "dinner", "snack", "none")
    FoodStatsSection(DesignR.string.stats_by_kind, "food_stats_kinds") {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            var offset = 0f
            kinds.forEach { item ->
                val width = (size.width * item.share).toFloat()
                drawRect(palette.getValue("chart-${kindKeys.indexOf(item.kind) + 1}"), Offset(offset, 0f), Size(width, size.height))
                offset += width
            }
        }
        kinds.forEach { item ->
            val label = stringResource(when (item.kind) {
                "breakfast" -> DesignR.string.food_meal_kinds_breakfast
                "lunch" -> DesignR.string.food_meal_kinds_lunch
                "dinner" -> DesignR.string.food_meal_kinds_dinner
                "snack" -> DesignR.string.food_meal_kinds_snack
                else -> DesignR.string.food_no_meal_kind
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(palette.getValue("chart-${kindKeys.indexOf(item.kind) + 1}")))
                Text(label, modifier = Modifier.weight(1f))
                Text(statsTotalLabel(item.kcal, 0, locale) + " " + stringResource(DesignR.string.food_kcal) +
                    " · " + formatFoodAmount((item.share * 100).toString(), 0, locale) + "%")
            }
        }
        if (kinds.any { !it.kcal.complete }) Text(stringResource(DesignR.string.food_incomplete), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FoodStatsExport(state: FoodStatsState, controller: FoodStatsController) {
    val scope = rememberCoroutineScope()
    var format by remember(state.namespace) { mutableStateOf<FoodStatsExportFormat?>(null) }
    FoodStatsSection(DesignR.string.food_export_title, "food_stats_export") {
        Text(stringResource(DesignR.string.food_export_hint), style = MaterialTheme.typography.bodySmall)
        FoodStatsExportFormat.entries.forEach { choice ->
            OutlinedButton(onClick = { format = choice }, enabled = !state.exporting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_stats_export_${choice.wire}")
                    .semantics { selected = format == choice }) {
                Text(stringResource(if (choice == FoodStatsExportFormat.Csv) DesignR.string.food_export_csv else DesignR.string.food_export_json))
            }
        }
        format?.let { selectedFormat ->
            TextButton(onClick = { scope.launch { controller.export(selectedFormat, FileExportDestination.SAVE) } },
                enabled = !state.exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_stats_export_save")) {
                Text(stringResource(DesignR.string.common_save))
            }
            TextButton(onClick = { scope.launch { controller.export(selectedFormat, FileExportDestination.SHARE) } },
                enabled = !state.exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_stats_export_share")) {
                Text(localized(GeneratedStrings.NativePhotos.share))
            }
        }
        if (state.exporting) ProgressBar(modifier = Modifier.fillMaxWidth())
        state.error?.let { ErrorMessage(it, "food_stats_export_error") }
    }
}

@Composable
private fun FoodStatsLegend(color: Color, label: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(20.dp).height(3.dp).background(color))
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FoodStatsSection(title: Int, tag: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun statsTotalLabel(total: FoodTotal?, fraction: Int, locale: String): String = when {
    total == null || total.unknown -> stringResource(DesignR.string.food_no_data)
    !total.complete -> stringResource(DesignR.string.food_at_least) + " " + formatFoodAmount(total.known, fraction, locale)
    else -> formatFoodAmount(total.known, fraction, locale)
}

@Composable
private fun foodStatsPalette(): Map<String, Color> = if (MaterialTheme.colorScheme.background == GeneratedColors.dark.getValue("background"))
    GeneratedColors.dark else GeneratedColors.light

private fun String.chartNumber(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
private fun foodStatsDayLabel(day: String, locale: String): String = LocalDate.parse(day)
    .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag(locale)))
private fun foodStatsWeekday(day: String, locale: String): String = LocalDate.parse(day)
    .format(DateTimeFormatter.ofPattern("EE", Locale.forLanguageTag(locale)))
private fun foodStatsTitle(period: FoodStatsPeriod, locale: String): String {
    val language = Locale.forLanguageTag(locale)
    val first = LocalDate.parse(period.start)
    val last = LocalDate.parse(period.end)
    return if (period.kind == FoodStatsPeriodKind.Month) first.format(DateTimeFormatter.ofPattern("LLLL yyyy", language))
    else first.format(DateTimeFormatter.ofPattern("d MMMM", language)) + " — " + last.format(DateTimeFormatter.ofPattern("d MMMM yyyy", language))
}
