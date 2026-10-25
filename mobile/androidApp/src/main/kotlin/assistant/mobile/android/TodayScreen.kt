package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.SessionLease
import assistant.mobile.core.today.TodayAttentionState
import assistant.mobile.core.today.TodayCard
import assistant.mobile.core.today.TodayController
import assistant.mobile.core.today.TodayFood
import assistant.mobile.core.today.TodayMetric
import assistant.mobile.core.today.TodayPersonal
import assistant.mobile.core.today.TodayQuery
import assistant.mobile.core.today.TodayState
import assistant.mobile.core.today.formatTodayAmount
import assistant.mobile.core.today.formatTodayWeightChange
import assistant.mobile.design.ui.AppCard
import assistant.mobile.design.ui.AppCardSurface
import assistant.mobile.design.ui.CardTitle
import assistant.mobile.design.ui.NavigationRow
import assistant.mobile.design.ui.ProgressBar
import assistant.mobile.design.ui.QuickActionRow
import assistant.mobile.design.ui.QuickActionTile
import assistant.mobile.design.ui.Spacing
import assistant.mobile.design.R as DesignR
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** The cards share visibility and refresh, but retain the state of each independent read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayScreen(
    controller: TodayController,
    lease: SessionLease,
    attention: TodayAttentionState,
    onNavigate: (FamilyScreen) -> Unit,
    onRecover: (String) -> Unit,
    onOpenFood: (String) -> Unit,
    onOpenTask: (String) -> Unit = {},
    onAddWeight: () -> Unit = {},
    onRefreshAttention: () -> Unit = {},
    modifier: Modifier = Modifier,
    ambientCard: @Composable () -> Unit = {},
) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace }
    val ownAttention = attention.takeIf { it.namespace == lease.namespace }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var refreshing by remember(controller, lease.namespace) { mutableStateOf(false) }
    DisposableEffect(controller, lease, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.activate()
                Lifecycle.Event.ON_STOP -> controller.deactivate()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.deactivate()
        }
    }
    val refresh = {
        if (!refreshing) {
            refreshing = true
            onRefreshAttention()
            scope.launch {
                try { controller.refresh() } finally { refreshing = false }
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        NativeTopBar(stringResource(DesignR.string.today_title))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = refresh,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("today_pull_refresh"),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.l).testTag("screen_Today"),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                state?.day?.let { Text(fullTodayDayLabel(it), modifier = Modifier.testTag("today_date"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ambientCard()
                state?.let { TodayQuickActions(it, onNavigate, onAddWeight) }
                ownAttention?.let { TodayAttention(it, onRecover, onRefreshAttention) }
                if (state == null) StatusMessage(GeneratedStrings.State.loading, "today_loading")
                state?.let { day ->
                    val retry = { card: TodayCard -> scope.launch { controller.refreshCard(card) }; Unit }
                    day.notifications?.takeIf { it.data?.items?.isEmpty() != true || it.loading || it.error != null }
                        ?.let { query ->
                            TodayCard(DesignR.string.today_notifications, "notifications", { onNavigate(FamilyScreen.Notifications) }) {
                                TodayQueryStatus(query, "notifications") { retry(TodayCard.Notifications) }
                                query.data?.let { data ->
                                    data.items.take(3).forEach { Text(it.title, style = MaterialTheme.typography.bodyLarge) }
                                    val more = data.items.size - 3
                                    if (more > 0) Text(localized(GeneratedStrings.Notifications.more(more.toDouble(), more.toString())),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    day.food?.let { query ->
                        TodayCard(DesignR.string.today_food, "food", { onOpenFood(query.data?.date ?: day.day) }) {
                            TodayQueryStatus(query, "food") { retry(TodayCard.Food) }
                            query.data?.let { TodayFoodSummary(it) }
                        }
                    }
                    day.tasks?.let { query ->
                        val tasks = query.data
                        val hasTasks = tasks?.items?.isNotEmpty() == true
                        AppCardSurface(
                            modifier = Modifier.fillMaxWidth().testTag("today_card_tasks"),
                            onClick = { onNavigate(FamilyScreen.Tasks) },
                        ) {
                            Column(Modifier.padding(start = Spacing.l, top = Spacing.l, end = Spacing.l,
                                bottom = if (hasTasks) 0.dp else Spacing.l),
                                verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                                CardTitle(stringResource(DesignR.string.today_tasks))
                                TodayQueryStatus(query, "tasks") { retry(TodayCard.Tasks) }
                                if (tasks?.items?.isEmpty() == true) Text(stringResource(DesignR.string.today_tasks_empty),
                                    modifier = Modifier.testTag("today_empty_tasks"), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (tasks != null && tasks.items.isNotEmpty()) Column(Modifier.padding(bottom = Spacing.l)) {
                                tasks.items.forEach { task ->
                                    val dueOn = task.dueOn
                                    NavigationRow(
                                        title = task.title,
                                        supporting = if (task.overdue && dueOn != null) {
                                            stringResource(DesignR.string.tasks_overdue, todayDayLabel(dueOn, tasks.today))
                                        } else null,
                                        onClick = { onOpenTask(task.id) },
                                        testTag = "today_task_${task.id}",
                                        supportingColor = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    day.timers?.takeIf { it.data?.items?.isEmpty() != true || it.loading || it.error != null }
                        ?.let { query ->
                            TodayCard(DesignR.string.today_timers, "timers", { onNavigate(FamilyScreen.Timers) }) {
                                TodayQueryStatus(query, "timers") { retry(TodayCard.Timers) }
                                query.data?.let { timers ->
                                    timers.items.forEach { timer ->
                                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                            Text(timer.clock(timers.readAtMillis, day.timerNowMillis),
                                                style = MaterialTheme.typography.headlineSmall,
                                                modifier = Modifier.testTag("today_timer_${timer.id}"))
                                            timer.label?.takeIf { it.isNotEmpty() }?.let {
                                                Text(it, style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    day.personal?.let { query ->
                        TodayCard(DesignR.string.activity_steps_today, "steps", { onNavigate(FamilyScreen.Activity) }) {
                            TodayQueryStatus(query, "movement") { retry(TodayCard.Personal) }
                            query.data?.let { TodayStepsSummary(it, day) }
                        }
                    }
                    day.personal?.let { query ->
                        TodayCard(DesignR.string.nav_personal, "personal", { onNavigate(FamilyScreen.Personal) }) {
                            TodayQueryStatus(query, "personal") { retry(TodayCard.Personal) }
                            val weightChange = day.weightHistory?.data?.changeKg
                            if (query.data != null || weightChange != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    query.data?.let { TodayPersonalSummary(it, day) }
                                    weightChange?.let { change ->
                                        Text(stringResource(DesignR.string.personal_weight_change,
                                            formatTodayWeightChange(change) + " " + stringResource(DesignR.string.personal_kg)),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.testTag("today_weight_change"))
                                    }
                                }
                            }
                            if (query.data?.weight?.value == null && "personal.write" in day.scopes) {
                                OutlinedButton(onClick = onAddWeight,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("today_start_weight")) {
                                    Text(stringResource(DesignR.string.today_start_weight))
                                }
                            }
                            day.steps?.let { TodayQueryStatus(it, "steps") { retry(TodayCard.Steps) } }
                            day.weightHistory?.let { history ->
                                TodayQueryStatus(history, "weight_history") { retry(TodayCard.WeightHistory) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayQuickActions(state: TodayState, onNavigate: (FamilyScreen) -> Unit, onAddWeight: () -> Unit) {
    val actions = listOf(
        Triple("nutrition.write", FamilyScreen.Food, DesignR.string.today_quick_food to DesignR.drawable.lucide_utensils),
        Triple("tasks.write", FamilyScreen.Tasks, DesignR.string.today_quick_task to DesignR.drawable.lucide_list_todo),
        Triple("personal.write", FamilyScreen.Personal, DesignR.string.today_quick_weight to DesignR.drawable.lucide_scale),
        Triple("timers.write", FamilyScreen.Timers, DesignR.string.today_quick_timer to DesignR.drawable.lucide_timer),
    ).filter { (scope, _, _) -> scope in state.scopes }
    if (actions.isEmpty()) return
    val description = stringResource(DesignR.string.today_quick)
    QuickActionRow(modifier = Modifier.semantics { contentDescription = description }) {
        actions.forEach { (_, destination, presentation) ->
            val (label, icon) = presentation
            QuickActionTile(
                icon = icon,
                label = stringResource(label),
                onClick = { if (destination == FamilyScreen.Personal) onAddWeight() else onNavigate(destination) },
                testTag = "today_quick_${destination.name}",
            )
        }
    }
}

@Composable
private fun TodayAttention(state: TodayAttentionState, onRecover: (String) -> Unit, onRefresh: () -> Unit) {
    if (state.entries.isEmpty() && !state.loading && state.error == null) return
    AppCardSurface(modifier = Modifier.fillMaxWidth().testTag("today_card_attention")) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            CardTitle(stringResource(DesignR.string.today_attention))
            if (state.loading) Text(stringResource(DesignR.string.state_loading), Modifier.testTag("today_loading_attention"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let {
                ErrorMessage(it, "today_error_attention")
                FilledTonalButton(onClick = onRefresh, enabled = !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("today_retry_attention")) {
                    Text(stringResource(DesignR.string.common_retry))
                }
            }
            if (state.stale) Text(stringResource(if (state.error != null) DesignR.string.state_refresh_failed else DesignR.string.state_stale),
                modifier = Modifier.testTag("today_stale_attention"), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.entries.isNotEmpty()) Text(
                localized(GeneratedStrings.Today.attentionText(state.entries.size.toDouble(), state.entries.size.toString())),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.entries.isNotEmpty()) Column(Modifier.padding(bottom = Spacing.l)) {
            state.entries.forEach { entry ->
                NavigationRow(
                    title = localized(entry.label),
                    supporting = stringResource(DesignR.string.outcome_check),
                    onClick = { onRecover(entry.key) },
                    testTag = "today_attention_${entry.key}",
                )
            }
        }
    }
}

@Composable
private fun TodayCard(title: Int, tag: String, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppCard(onClick = onOpen, testTag = "today_card_$tag") {
        CardTitle(stringResource(title))
        content()
    }
}

@Composable
private fun <T> TodayQueryStatus(query: TodayQuery<T>, tag: String, retry: () -> Unit) {
    if (query.loading) {
        ProgressBar(modifier = Modifier.fillMaxWidth())
        Text(stringResource(DesignR.string.state_loading), modifier = Modifier.testTag("today_loading_$tag"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    query.error?.let { error ->
        ErrorMessage(error, "today_error_$tag")
        FilledTonalButton(onClick = retry, enabled = !query.loading,
            modifier = Modifier.heightIn(min = 48.dp).testTag("today_retry_$tag")) {
            Text(stringResource(DesignR.string.common_retry))
        }
    }
    if (query.stale) Text(
        stringResource(if (query.error == null) DesignR.string.state_stale else DesignR.string.state_refresh_failed),
        modifier = Modifier.testTag("today_stale_$tag").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TodayFoodSummary(food: TodayFood) {
    if (food.empty) {
        Text(stringResource(DesignR.string.today_food_empty), modifier = Modifier.testTag("today_empty_food"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val energyDescription = if (!food.energyComplete && food.energyKcal != null)
        stringResource(DesignR.string.food_at_least) + " " + formatTodayAmount(food.energyKcal, 0) else food.energyText
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(food.energyText, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("today_food_energy").semantics { contentDescription = energyDescription })
        Text(food.targetKcal?.let { stringResource(DesignR.string.food_of, formatTodayAmount(it, 0)) }
            ?: stringResource(DesignR.string.food_kcal),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (food.progress != null || food.balanceKcal != null) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            food.progress?.let { progress ->
                val eatenLabel = stringResource(DesignR.string.food_eaten)
                ProgressBar(progress = { progress }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = eatenLabel },
                    color = if (food.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            food.balanceKcal?.let { balance ->
                Text(stringResource(if (food.over) DesignR.string.food_over else DesignR.string.food_remaining,
                    formatTodayAmount(balance.removePrefix("-"), 0)),
                    color = if (food.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("today_food_balance"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TodayPersonalSummary(personal: TodayPersonal, state: TodayState) {
    val weight = personal.weight
    Text(stringResource(DesignR.string.today_weight), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (weight.status == "available" && weight.value != null) {
        Text(stringResource(DesignR.string.today_weight_value, formatTodayAmount(weight.value)),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("today_weight_value"))
        weight.measuredAt?.let { measured ->
            todayInstantDay(measured, state.timezone)?.let { day ->
                Text(stringResource(DesignR.string.today_measured, todayDayLabel(day, state.day).lowercase(Locale.forLanguageTag("ru"))),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("today_weight_measured"))
            }
        }
    } else Text(stringResource(DesignR.string.today_no_weight), modifier = Modifier.testTag("today_empty_personal"),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TodayStepsSummary(personal: TodayPersonal, state: TodayState) {
    // The manual-steps response describes an edit; only overview is the resolved total.
    val steps = personal.steps.takeIf { personal.date == state.day }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (steps?.status == "available" && steps.value != null) {
            Text(formatTodayAmount(steps.value, 0) + " " + stringResource(DesignR.string.sources_unit_steps),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("today_steps_value"))
        } else {
            Text(stringResource(metricStatus(steps)), modifier = Modifier.testTag("today_steps_status"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        steps?.target?.takeIf { it.toDoubleOrNull()?.let { target -> target > 0 } == true }?.let { target ->
            Text(stringResource(DesignR.string.personal_target,
                formatTodayAmount(target, 0) + " " + stringResource(DesignR.string.sources_unit_steps)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("today_steps_goal"))
        }
        if (steps?.status == "available" && steps.value != null && steps.partial) Text(stringResource(DesignR.string.activity_day_partial),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("today_steps_partial"))
        steps?.measuredAt?.let { at ->
            todayMomentPhrase(at, state.timezone, state.day)?.let { whenMeasured ->
                Text(stringResource(DesignR.string.resolution_measured_at, whenMeasured),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("today_steps_measured"))
            }
        }
        val sourceNames = steps?.sources.orEmpty().map { source ->
            source.name ?: stringResource(if (source.id == null) DesignR.string.resolution_manual
                else DesignR.string.sources_device_custom)
        }.distinct()
        if (sourceNames.isNotEmpty()) Text(stringResource(DesignR.string.sources_line, sourceNames.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("today_steps_source"))
    }
}

private fun metricStatus(metric: TodayMetric?): Int = when (metric?.status) {
    null, "no_data", "available" -> DesignR.string.resolution_status_no_data
    "conflict" -> DesignR.string.resolution_status_conflict
    "unavailable" -> DesignR.string.resolution_status_unavailable
    else -> DesignR.string.resolution_status_unknown
}

private val todayDateLocale = Locale.forLanguageTag("ru-RU")

private fun fullTodayDayLabel(day: String): String = runCatching {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", todayDateLocale))
}.getOrDefault(day)

private fun todayDayLabel(day: String, today: String): String = runCatching {
    val date = LocalDate.parse(day)
    val current = LocalDate.parse(today)
    when (date) {
        current -> "Сегодня"
        current.minusDays(1) -> "Вчера"
        current.plusDays(1) -> "Завтра"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == current.year) "d MMMM" else "d MMMM yyyy 'г'.", todayDateLocale))
    }
}.getOrDefault(day)

private fun todayInstantDay(at: String, timezone: String): String? = runCatching {
    Instant.parse(at).atZone(ZoneId.of(timezone)).toLocalDate().toString()
}.getOrNull()

private fun todayMomentPhrase(at: String, timezone: String, today: String): String? = runCatching {
    val instant = Instant.parse(at).atZone(ZoneId.of(timezone))
    todayDayLabel(instant.toLocalDate().toString(), today).lowercase(todayDateLocale) + ", " +
        instant.format(DateTimeFormatter.ofPattern("HH:mm", todayDateLocale))
}.getOrNull()
