package assistant.mobile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.SessionLease
import assistant.mobile.core.challenges.*
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.today.formatTodayAmount
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ChallengesScreen(controller: ChallengesController, lease: SessionLease, spaceId: String,
    onChallenge: (String) -> Unit, onToCircle: () -> Unit, onToAssistant: () -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.spaceId == spaceId }
    val scope = rememberCoroutineScope()
    var creating by remember(lease.namespace, spaceId) { mutableStateOf(false) }
    var draft by remember(lease.namespace, spaceId) { mutableStateOf(ChallengeDraft()) }
    ChallengesLifecycle(controller, lease, spaceId = spaceId)
    LaunchedEffect(state?.gone) { if (state?.gone == true) creating = false }
    fun startCreating() { state?.let { draft = challengeDraft(it.today); controller.clearError(); creating = true } }
    ScreenColumn("screen_Challenges", title = localized(GeneratedStrings.Circle.challenges)) {
        if (state?.gone == true) { CircleGone(onToCircle, onToAssistant); return@ScreenColumn }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "challenges_loading")
        state?.let { current ->
            CircleQueryStatus(current.challenges, "challenges") { scope.launch { controller.refresh() } }
            CircleError(current.error, current.serverError, "challenges_error")
            if (current.canCreate) CircleAction(GeneratedStrings.Circle.newChallenge, "challenges_new", !current.writeBlocked) { startCreating() }
            current.challenges.data?.let { challenges ->
                if (challenges.isEmpty()) {
                    CircleHeading(GeneratedStrings.Circle.noChallenges)
                    Text(localized(GeneratedStrings.Circle.noChallengesText))
                }
                challenges.forEach { challenge ->
                    CircleRow(challenge.name, challengePeriod(challenge, current.today) + " · " + localized(challengeStateLabel(challenge, current.today)),
                        "challenge_row_${challenge.id}", onClick = { onChallenge(challenge.id) })
                }
                CircleNotice(GeneratedStrings.Circle.rankingShows, GeneratedStrings.Circle.rankingShowsText, "challenges_ranking_notice")
            }
            ChallengePending(controller, current)
        }
    }
    if (creating && state?.gone != true) CircleSheet(localized(GeneratedStrings.Circle.newChallenge), "challenge_create_dialog", onDismiss = { creating = false }) {
        OutlinedTextField(value = draft.name, onValueChange = { draft = draft.copy(name = it.take(100)) }, singleLine = true,
            label = { Text(localized(GeneratedStrings.Circle.challengeName)) }, placeholder = { Text(localized(GeneratedStrings.Circle.challengeExample)) },
            enabled = state?.busy != true, modifier = Modifier.fillMaxWidth().testTag("challenge_name"))
        OutlinedTextField(value = draft.start, onValueChange = { draft = draft.copy(start = it) }, singleLine = true,
            label = { Text(localized(GeneratedStrings.Circle.challengeStart)) }, enabled = state?.busy != true,
            modifier = Modifier.fillMaxWidth().testTag("challenge_start"))
        OutlinedTextField(value = draft.end, onValueChange = { draft = draft.copy(end = it) }, singleLine = true,
            label = { Text(localized(GeneratedStrings.Circle.challengeEnd)) }, enabled = state?.busy != true,
            modifier = Modifier.fillMaxWidth().testTag("challenge_end"))
        OutlinedTextField(value = draft.tolerance, onValueChange = { draft = draft.copy(tolerance = it) }, singleLine = true,
            label = { Text(localized(GeneratedStrings.Circle.tolerance)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            enabled = state?.busy != true, modifier = Modifier.fillMaxWidth().testTag("challenge_tolerance"))
        CircleError(state?.error, state?.serverError, "challenge_create_error")
        CircleNotice(GeneratedStrings.Circle.startFuture, GeneratedStrings.Circle.startFutureText, "challenge_start_notice")
        CircleAction(GeneratedStrings.Circle.createChallenge, "challenge_create", state?.writeBlocked == false) {
            scope.launch { controller.create(draft)?.let { creating = false; onChallenge(it) } }
        }
        state?.let { ChallengePending(controller, it) }
    }
}

@Composable
internal fun ChallengeScreen(controller: ChallengesController, lease: SessionLease, challengeId: String,
    onFood: () -> Unit, onToCircle: () -> Unit, onToAssistant: () -> Unit) {
    val observed by controller.state.collectAsStateWithLifecycle()
    val state = observed?.takeIf { it.namespace == lease.namespace && it.challengeId == challengeId }
    val scope = rememberCoroutineScope()
    var leaving by remember(lease.namespace, challengeId) { mutableStateOf(false) }
    ChallengesLifecycle(controller, lease, challengeId = challengeId)
    LaunchedEffect(state?.gone) { if (state?.gone == true) leaving = false }
    val board = state?.board?.data
    ScreenColumn("screen_Challenge", title = board?.challenge?.name ?: localized(GeneratedStrings.Circle.challenge)) {
        if (state?.gone == true) { CircleGone(onToCircle, onToAssistant); return@ScreenColumn }
        board?.challenge?.let { challenge ->
            Text(challengePeriod(challenge, state!!.today) + " · " + localized(challengeStateLabel(challenge, state.today)))
        }
        if (state == null) StatusMessage(GeneratedStrings.State.loading, "challenge_loading")
        state?.let { current ->
            CircleQueryStatus(current.board, "challenge") { scope.launch { controller.refresh() } }
            CircleError(current.error, current.serverError, "challenge_error")
            board?.let { data ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("challenge_rules")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(localized(GeneratedStrings.Circle.rulesText))
                        ChallengeValue(localized(GeneratedStrings.Circle.tolerance), localized(GeneratedStrings.Circle.toleranceValue(formatTodayAmount(data.challenge.tolerance))))
                        ChallengeValue(localized(GeneratedStrings.Circle.othersSee), localized(GeneratedStrings.Circle.daysAndStreak))
                    }
                }
                Column(Modifier.fillMaxWidth().testTag("challenge_participation"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (data.participation) {
                        Participation.NoGoal -> {
                            CircleNotice(GeneratedStrings.Circle.noGoalTitle, GeneratedStrings.Circle.noGoalText, "challenge_no_goal")
                            CircleAction(GeneratedStrings.Circle.goalForLater, "challenge_goal", onClick = onFood)
                        }
                        Participation.Pending -> CircleNotice(GeneratedStrings.Circle.pendingTitle, GeneratedStrings.Circle.pendingText, "challenge_pending")
                        Participation.Active -> data.terms?.let { terms ->
                            OutlinedCard(Modifier.fillMaxWidth().testTag("challenge_own_terms")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircleHeading(GeneratedStrings.Circle.activeTitle)
                                    ChallengeValue(localized(GeneratedStrings.Circle.goalAtStart), "${formatTodayAmount(terms.calorieTarget, 0)} ${localized(GeneratedStrings.Food.kcal)}")
                                    challengeBand(terms.calorieTarget, data.challenge.tolerance)?.let { band ->
                                        ChallengeValue(localized(GeneratedStrings.Circle.range(formatTodayAmount(data.challenge.tolerance))), "$band ${localized(GeneratedStrings.Food.kcal)}")
                                    }
                                    ChallengeValue(localized(GeneratedStrings.Settings.timezone), terms.timezone)
                                    Text(localized(GeneratedStrings.Circle.termsFixed), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        null -> if (!current.joinOpen) Text(localized(GeneratedStrings.Circle.joinClosed), modifier = Modifier.testTag("challenge_join_closed"))
                    }
                    if (data.participation == null && current.joinOpen)
                        CircleAction(GeneratedStrings.Circle.participate, "challenge_join", !current.writeBlocked) { scope.launch { controller.join() } }
                    if (data.participation != null) CircleAction(GeneratedStrings.Circle.leaveRanking, "challenge_leave", !current.writeBlocked,
                        destructive = true) { leaving = true }
                }
                CircleHeading(GeneratedStrings.Circle.ranking)
                if (data.ranking.isEmpty()) Text(localized(GeneratedStrings.Circle.rankingEmpty), modifier = Modifier.testTag("challenge_ranking_empty"))
                else ChallengeRanking(data.ranking, lease.namespace.userId)
                Text(localized(GeneratedStrings.Circle.todayNotCounted))
            }
            ChallengePending(controller, current)
        }
    }
    if (leaving && state?.gone != true) AlertDialog(onDismissRequest = { leaving = false },
        title = { Text(localized(GeneratedStrings.Circle.leaveRankingTitle)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized(GeneratedStrings.Circle.leaveRankingText) + if (state?.joinOpen == false) " ${localized(GeneratedStrings.Circle.noRejoin)}" else "")
                CircleError(state?.error, state?.serverError)
            }
        }, confirmButton = {
            TextButton(onClick = { scope.launch { if (controller.leave()) leaving = false } }, enabled = state?.writeBlocked == false,
                modifier = Modifier.heightIn(min = 48.dp).testTag("challenge_leave_confirm")) {
                Text(localized(GeneratedStrings.Circle.leaveRanking), color = MaterialTheme.colorScheme.error)
            }
        }, dismissButton = {
            TextButton(onClick = { leaving = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("challenge_stay")) { Text(localized(GeneratedStrings.Circle.stay)) }
        })
}

@Composable
private fun ChallengeRanking(ranking: List<ChallengeRank>, me: String) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("challenge_ranking")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(GeneratedStrings.Circle.rankingPlace))
                Text(localized(GeneratedStrings.Circle.participant), modifier = Modifier.weight(1f))
                Text(localized(GeneratedStrings.Circle.days))
                Text(localized(GeneratedStrings.Circle.streak))
            }
            ranking.forEach { row ->
                Row(Modifier.fillMaxWidth().testTag("challenge_ranking_${row.userId}"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.place.toString())
                    Text(if (row.userId == me) localized(GeneratedStrings.Circle.you) else row.name,
                        modifier = Modifier.weight(1f), fontWeight = if (row.userId == me) FontWeight.SemiBold else FontWeight.Normal)
                    Text(row.days.toString())
                    Text(row.streak.toString())
                }
            }
        }
    }
}

@Composable
private fun ChallengeValue(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value)
    }
}

@Composable
private fun ChallengePending(controller: ChallengesController, state: ChallengesState) {
    val scope = rememberCoroutineScope()
    state.pending.forEach { entry ->
        Column(Modifier.fillMaxWidth().testTag("challenge_delivery_${entry.key}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusMessage(GeneratedStrings.Outcome.unknownText, "challenge_delivery_unknown")
            CircleAction(GeneratedStrings.Outcome.check, "challenge_delivery_check_${entry.key}", !state.busy) { scope.launch { controller.check(entry.key) } }
            if (entry.state != SubmissionState.SENDING) CircleAction(GeneratedStrings.Outcome.abandon, "challenge_delivery_forget_${entry.key}", !state.busy) {
                scope.launch { controller.forget(entry.key) }
            }
        }
    }
}

@Composable
private fun ChallengesLifecycle(controller: ChallengesController, lease: SessionLease, spaceId: String? = null, challengeId: String? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visibilityOwner = remember(controller, lease, spaceId, challengeId) { Any() }
    DisposableEffect(controller, lease, lifecycle, spaceId, challengeId, visibilityOwner) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.activate(spaceId, challengeId, visibilityOwner)
            Lifecycle.Event.ON_STOP -> controller.deactivate(visibilityOwner)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate(spaceId, challengeId, visibilityOwner)
        onDispose { lifecycle.removeObserver(observer); controller.deactivate(visibilityOwner) }
    }
    LaunchedEffect(controller, lease, spaceId, challengeId) { while (true) { delay(1_000); controller.tick() } }
}

private fun challengeStateLabel(challenge: Challenge, today: String): LocalizedString = when (challengeState(challenge, today)) {
    ChallengeState.Upcoming -> GeneratedStrings.Circle.ChallengeState.upcoming
    ChallengeState.Running -> GeneratedStrings.Circle.ChallengeState.running
    ChallengeState.Finished -> GeneratedStrings.Circle.ChallengeState.finished
}

@Composable
private fun challengePeriod(challenge: Challenge, today: String) = "${challengeDay(challenge.startDate, today)} – ${challengeDay(challenge.endDate, today)}"

@Composable
private fun challengeDay(day: String, today: String): String {
    val date = LocalDate.parse(day); val current = LocalDate.parse(today)
    return when (date) {
        current -> localized(GeneratedStrings.Day.today)
        current.minusDays(1) -> localized(GeneratedStrings.Day.yesterday)
        current.plusDays(1) -> localized(GeneratedStrings.Day.tomorrow)
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == current.year) "d MMMM" else "d MMMM yyyy 'г'.", Locale.forLanguageTag("ru-RU")))
    }
}
