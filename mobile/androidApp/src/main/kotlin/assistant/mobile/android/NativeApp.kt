package assistant.mobile.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import assistant.mobile.android.ambient.AmbientRuntime
import assistant.mobile.core.recordings.RecordingsController
import assistant.mobile.core.AccountDeletionController
import assistant.mobile.core.AccountDeletionState
import assistant.mobile.core.AuthController
import assistant.mobile.core.AuthLink
import assistant.mobile.core.NativeAuthDestination
import assistant.mobile.core.NativeServerCheckResult
import assistant.mobile.core.NativeServerSwitchResult
import assistant.mobile.core.AuthMode
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.SettingsController
import assistant.mobile.core.NativeUpdateController
import assistant.mobile.core.ThemePreference
import assistant.mobile.core.assistant.AssistantController
import assistant.mobile.core.today.TodayController
import assistant.mobile.core.today.TodayAttentionController
import assistant.mobile.core.tasks.TasksController
import assistant.mobile.core.tasks.TaskAssistantController
import assistant.mobile.core.tasks.TaskState
import assistant.mobile.core.timers.TimersController
import assistant.mobile.core.notifications.NotificationsController
import assistant.mobile.core.notifications.NativeNotificationCoordinator
import assistant.mobile.core.notifications.NotificationDestination
import assistant.mobile.core.notifications.notificationDestination
import assistant.mobile.android.notify.AndroidNotifications
import assistant.mobile.android.whoop.WhoopRepository
import assistant.mobile.android.glasses.EvenG2Route
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import kotlinx.coroutines.launch
import assistant.mobile.core.food.FoodController
import assistant.mobile.core.food.FoodStatsController
import assistant.mobile.core.devices.DevicesController
import assistant.mobile.core.devices.DisplayController
import assistant.mobile.core.ring.RingCollectorsController
import assistant.mobile.platform.ring.RingController
import assistant.mobile.android.body.assets.AndroidBodyAssetRepository
import assistant.mobile.core.body.BodyPresentationController
import assistant.mobile.core.personal.PersonalController
import assistant.mobile.core.personal.PersonalHistoryController
import assistant.mobile.core.personal.PersonalSourcesController
import assistant.mobile.core.personal.PersonalDdxController
import assistant.mobile.core.personal.PersonalDerivedScore
import assistant.mobile.core.personal.PersonalRecoveryOwner
import assistant.mobile.core.sharing.SharingController
import assistant.mobile.core.challenges.ChallengesController
import assistant.mobile.core.circle.CircleController
import assistant.mobile.core.activity.ActivityController
import assistant.mobile.core.calls.CallsController
import assistant.mobile.core.calls.CallsState
import assistant.mobile.core.calls.CallAudioRoute
import assistant.mobile.core.location.LocationController
import assistant.mobile.core.places.PlacesController
import assistant.mobile.core.speech.SpeechPlaybackController
import assistant.mobile.core.speech.SpeechSettingsController
import assistant.mobile.core.dictation.DictationController
import assistant.mobile.core.photos.ImageAcquisition
import assistant.mobile.core.photos.PhotoNormalizer
import assistant.mobile.core.photos.PhotoAttachmentController
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.design.GeneratedColors
import assistant.mobile.design.R as DesignR

val NativeBackgroundKey = SemanticsPropertyKey<Int>("NativeBackground")
const val ASSISTANT_DRAFT_TARGET = "assistant.compose"

/** Account authority controls the entire private navigation tree, including remembered entries. */
@Composable
internal fun NativeApp(
    theme: ThemePreference,
    onThemeChanged: (ThemePreference) -> Unit,
    auth: AuthController,
    settings: SettingsController,
    deletion: AccountDeletionController,
    sessions: SessionManager,
    assistant: AssistantController,
    today: TodayController,
    food: FoodController,
    foodStats: FoodStatsController,
    tasks: TasksController,
    timers: TimersController,
    notifications: NotificationsController,
    notificationDelivery: NativeNotificationCoordinator,
    notificationPlatform: AndroidNotifications,
    taskAssistant: TaskAssistantController,
    taskImages: PrivateConversationImages,
    devices: DevicesController,
    display: DisplayController,
    whoop: WhoopRepository,
    personal: PersonalController,
    personalHistory: PersonalHistoryController,
    personalSources: PersonalSourcesController,
    personalDdx: PersonalDdxController,
    bodyPresentation: BodyPresentationController,
    bodyAssets: AndroidBodyAssetRepository,
    plugins: NativePluginsController,
    circle: CircleController,
    sharing: SharingController,
    challenges: ChallengesController,
    activity: ActivityController,
    location: LocationController,
    places: PlacesController,
    attention: TodayAttentionController,
    dictation: DictationController,
    speech: SpeechPlaybackController,
    speechSettings: SpeechSettingsController,
    dictationPermission: DictationPermissionBridge,
    photos: PhotoAttachmentController,
    images: PrivateConversationImages,
    photoNormalizer: PhotoNormalizer,
    photoAcquisition: ImageAcquisition?,
    fileExport: FileExportBridge,
    onOpenManagement: () -> Unit,
    onAssistantAccess: ((String) -> Unit)? = null,
    appVersion: String,
    updates: NativeUpdateController,
    onInstallPermission: () -> Unit,
    assistantNavigation: AssistantNavigationRequest? = null,
    onAssistantNavigationHandled: (AssistantNavigationRequest) -> Unit = {},
    taskNavigation: TaskNavigationRequest? = null,
    onTaskNavigationHandled: (TaskNavigationRequest) -> Unit = {},
    deviceNavigation: DeviceNavigationRequest? = null,
    onDeviceNavigationHandled: (DeviceNavigationRequest) -> Unit = {},
    timerNavigation: Long? = null,
    onTimerNavigationHandled: (Long) -> Unit = {},
    personalNavigation: PersonalNavigationRequest? = null,
    onPersonalNavigationHandled: (PersonalNavigationRequest) -> Unit = {},
    pluginNavigation: PluginNavigationRequest? = null,
    onPluginNavigationHandled: (PluginNavigationRequest) -> Unit = {},
    circleNavigation: CircleNavigationRequest? = null,
    onCircleNavigationHandled: (CircleNavigationRequest) -> Unit = {},
    activityNavigation: ActivityNavigationRequest? = null,
    onActivityNavigationHandled: (ActivityNavigationRequest) -> Unit = {},
    notificationNavigation: NotificationNavigationRequest? = null,
    onNotificationNavigationHandled: (NotificationNavigationRequest) -> Unit = {},
    calls: CallsController? = null,
    callPermission: DictationPermissionBridge? = null,
    bluetoothPermission: DictationPermissionBridge? = null,
    callAudioRouteNames: () -> Map<CallAudioRoute, String> = { emptyMap() },
    placesNavigation: PlacesNavigationRequest? = null,
    onPlacesNavigationHandled: (PlacesNavigationRequest) -> Unit = {},
    ambient: AmbientRuntime? = null,
    recordings: RecordingsController? = null,
    sectionNavigation: SectionNavigationRequest? = null,
    onSectionNavigationHandled: (SectionNavigationRequest) -> Unit = {},
    ring: RingController? = null,
    ringCollectors: RingCollectorsController? = null,
    serverName: String,
    serverOrigin: String,
    defaultServerOrigin: String,
    checkServer: suspend (String) -> NativeServerCheckResult,
    switchServer: suspend (NativeServerCheckResult.Compatible, AuthLink?) -> NativeServerSwitchResult,
    hasServerData: suspend () -> Boolean,
    serverInvitation: NativeAuthDestination?,
    onServerInvitationHandled: (NativeAuthDestination) -> Unit,
) {
    val upgradeContext = LocalContext.current
    var acknowledgeUpgrade by remember { mutableStateOf(LegacyShellUpgrade.needsAcknowledgement(upgradeContext)) }
    if (acknowledgeUpgrade) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.upgrade_native_title)) },
        text = { Text(stringResource(R.string.upgrade_native_message)) },
        confirmButton = { TextButton(onClick = {
            LegacyShellUpgrade.acknowledge(upgradeContext)
            acknowledgeUpgrade = false
        }, modifier = Modifier.testTag("upgrade_acknowledge")) { Text(stringResource(R.string.upgrade_native_continue)) } },
    )
    FileExportHost(fileExport)
    if (callPermission != null && bluetoothPermission != null) CallPermissions(callPermission, bluetoothPermission)
    val callState = calls?.state?.collectAsStateWithLifecycle()?.value ?: CallsState()
    val callScope = rememberCoroutineScope()
    val lease by sessions.leases.collectAsStateWithLifecycle()
    val identity by sessions.identity.collectAsStateWithLifecycle()
    val authState by auth.state.collectAsStateWithLifecycle()
    // Keep the account's routes while the transient server form replaces their presentation.
    val backStack = key(lease?.namespace, lease?.generation) {
        rememberNavBackStack(Destination.Section(
            if (authState.welcome) FamilyScreen.Welcome else FamilyScreen.Today,
        ))
    }
    val observedDeletion by deletion.state.collectAsStateWithLifecycle()
    val deletionState = observedDeletion.takeIf { lease == null || it.namespace == lease?.namespace }
        ?: AccountDeletionState()
    var showDeletion by rememberSaveable { mutableStateOf(false) }
    // Server invitations and form values stay outside saved navigation and Activity state.
    var serverEntry by remember { mutableStateOf<NativeServerEntry?>(null) }
    var currentServerHasData by remember { mutableStateOf(true) }
    LaunchedEffect(serverEntry, serverInvitation) {
        if (serverEntry != null || serverInvitation != null) {
            currentServerHasData = true
            currentServerHasData = try { hasServerData() } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { true }
        }
    }
    val closeServerSelection = {
        serverEntry = null
        serverInvitation?.let(onServerInvitationHandled)
        Unit
    }
    val background = MaterialTheme.colorScheme.background
    Surface(
        modifier = Modifier.fillMaxSize().imePadding().testTag("native_root")
            .semantics { this[NativeBackgroundKey] = background.toArgb() },
        color = background,
    ) {
        val activeLease = lease
        val activeIdentity = identity
        when {
            serverEntry != null || serverInvitation != null -> {
                Scaffold(topBar = { NativeAuthHeader() }) { padding ->
                    ServerSelectionScreen(
                        entry = serverEntry ?: NativeServerEntry.Invite,
                        currentName = serverName,
                        currentOrigin = serverOrigin,
                        defaultOrigin = defaultServerOrigin,
                        hasSession = activeLease != null,
                        hasCurrentData = currentServerHasData,
                        check = checkServer,
                        switchServer = switchServer,
                        onRedeem = { link -> auth.acceptLink(link); closeServerSelection() },
                        onClose = closeServerSelection,
                        destination = serverInvitation,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
            authState.signingOut -> {
                Scaffold(topBar = { NativeAuthHeader() }) { padding ->
                    ScreenColumn("screen_SigningOut", Modifier.padding(padding)) {
                        Text(stringResource(R.string.auth_runtime_signing_out), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            (activeLease == null || activeIdentity == null) && (showDeletion || deletionState.status != null) -> {
                Scaffold(topBar = { NativeAuthHeader() }) { padding ->
                    AccountDeletionScreen(deletion, Modifier.padding(padding), statusOnly = true,
                        onFinished = { deletion.acknowledgeCompleted(); showDeletion = false })
                }
            }
            activeLease == null || activeIdentity == null || authState.mode in setOf(AuthMode.Redeem, AuthMode.LinkExpired) -> {
                Scaffold(topBar = { NativeAuthHeader() }) { padding ->
                    AuthenticationScreen(
                        auth = auth,
                        modifier = Modifier.padding(padding),
                        pendingDeletion = deletionState.hasRecovery,
                        onOpenDeletion = { showDeletion = true },
                        serverName = serverName,
                        serverHost = android.net.Uri.parse(serverOrigin).let { uri ->
                            uri.host.orEmpty() + if (uri.port != -1) ":${uri.port}" else ""
                        },
                        onChangeServer = { serverEntry = NativeServerEntry.Custom },
                        onInvite = { serverEntry = NativeServerEntry.Invite },
                        onOwnServer = { serverEntry = NativeServerEntry.Custom },
                    )
                }
            }
            else -> key(activeLease.namespace, activeLease.generation) {
                // Expiry removes this composition; a different account cannot recover its saved UI.
                SignedInApp(
                    theme, onThemeChanged, auth, settings, deletion, sessions, assistant, today, food, foodStats, tasks, timers, taskAssistant, taskImages, devices, display, whoop, personal, plugins, circle, sharing, challenges, activity, attention, dictation, speech, speechSettings, dictationPermission,
                    photos, images, photoNormalizer, photoAcquisition, fileExport,
                    activeLease, onOpenManagement, appVersion, updates, onInstallPermission,
                    assistantNavigation, onAssistantNavigationHandled, taskNavigation, onTaskNavigationHandled, onAssistantAccess,
                    deviceNavigation, onDeviceNavigationHandled,
                    timerNavigation, onTimerNavigationHandled,
                    personalHistory, personalSources, personalDdx, bodyPresentation, bodyAssets, personalNavigation, onPersonalNavigationHandled,
                    pluginNavigation, onPluginNavigationHandled,
                    circleNavigation, onCircleNavigationHandled,
                    activityNavigation, onActivityNavigationHandled,
                    notifications, notificationDelivery, notificationPlatform,
                    notificationNavigation, onNotificationNavigationHandled,
                    calls,
                    location, places, placesNavigation, onPlacesNavigationHandled,
                    ambient, recordings, sectionNavigation, onSectionNavigationHandled,
                    ring, ringCollectors,
                    serverName, serverOrigin, { serverEntry = NativeServerEntry.Custom },
                    backStack,
                )
            }
        }
    }
    if (lease != null && calls != null) NativeCallOverlay(callState,
        onAnswer = calls::answer, onDecline = calls::decline, onHangUp = calls::hangUp,
        onToggleMute = calls::toggleMute,
        onRouteChange = { route ->
            callScope.launch {
                if (route != CallAudioRoute.BLUETOOTH || bluetoothPermission?.request() == true) calls.setRoute(route)
            }
        }, routeNames = callAudioRouteNames(), onClearError = calls::clearError)
    if (lease != null && calls != null) NativeCallNotice(callState, onDismiss = calls::clearError)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignedInApp(
    theme: ThemePreference,
    onThemeChanged: (ThemePreference) -> Unit,
    auth: AuthController,
    settings: SettingsController,
    deletion: AccountDeletionController,
    sessions: SessionManager,
    assistant: AssistantController,
    today: TodayController,
    food: FoodController,
    foodStats: FoodStatsController,
    tasks: TasksController,
    timers: TimersController,
    taskAssistant: TaskAssistantController,
    taskImages: PrivateConversationImages,
    devices: DevicesController,
    display: DisplayController,
    whoop: WhoopRepository,
    personal: PersonalController,
    plugins: NativePluginsController,
    circle: CircleController,
    sharing: SharingController,
    challenges: ChallengesController,
    activity: ActivityController,
    attention: TodayAttentionController,
    dictation: DictationController,
    speech: SpeechPlaybackController,
    speechSettings: SpeechSettingsController,
    dictationPermission: DictationPermissionBridge,
    photos: PhotoAttachmentController,
    images: PrivateConversationImages,
    photoNormalizer: PhotoNormalizer,
    photoAcquisition: ImageAcquisition?,
    fileExport: FileExportBridge,
    lease: SessionLease,
    onOpenManagement: () -> Unit,
    appVersion: String,
    updates: NativeUpdateController,
    onInstallPermission: () -> Unit,
    assistantNavigation: AssistantNavigationRequest?,
    onAssistantNavigationHandled: (AssistantNavigationRequest) -> Unit,
    taskNavigation: TaskNavigationRequest?,
    onTaskNavigationHandled: (TaskNavigationRequest) -> Unit,
    onAssistantAccess: ((String) -> Unit)?,
    deviceNavigation: DeviceNavigationRequest?,
    onDeviceNavigationHandled: (DeviceNavigationRequest) -> Unit,
    timerNavigation: Long?,
    onTimerNavigationHandled: (Long) -> Unit,
    personalHistory: PersonalHistoryController,
    personalSources: PersonalSourcesController,
    personalDdx: PersonalDdxController,
    bodyPresentation: BodyPresentationController,
    bodyAssets: AndroidBodyAssetRepository,
    personalNavigation: PersonalNavigationRequest?,
    onPersonalNavigationHandled: (PersonalNavigationRequest) -> Unit,
    pluginNavigation: PluginNavigationRequest?,
    onPluginNavigationHandled: (PluginNavigationRequest) -> Unit,
    circleNavigation: CircleNavigationRequest?,
    onCircleNavigationHandled: (CircleNavigationRequest) -> Unit,
    activityNavigation: ActivityNavigationRequest?,
    onActivityNavigationHandled: (ActivityNavigationRequest) -> Unit,
    notifications: NotificationsController,
    notificationDelivery: NativeNotificationCoordinator,
    notificationPlatform: AndroidNotifications,
    notificationNavigation: NotificationNavigationRequest?,
    onNotificationNavigationHandled: (NotificationNavigationRequest) -> Unit,
    calls: CallsController?,
    location: LocationController,
    places: PlacesController,
    placesNavigation: PlacesNavigationRequest?,
    onPlacesNavigationHandled: (PlacesNavigationRequest) -> Unit,
    ambient: AmbientRuntime?,
    recordings: RecordingsController?,
    sectionNavigation: SectionNavigationRequest?,
    onSectionNavigationHandled: (SectionNavigationRequest) -> Unit,
    ring: RingController?,
    ringCollectors: RingCollectorsController?,
    serverName: String,
    serverOrigin: String,
    onChangeServer: () -> Unit,
    backStack: NavBackStack<NavKey>,
) {
    NotificationsLifecycle(notifications, lease)
    val observedNotifications by notifications.state.collectAsStateWithLifecycle()
    val notificationState = observedNotifications?.takeIf { it.namespace == lease.namespace }
    val notificationDevice by notificationDelivery.state.collectAsStateWithLifecycle()
    val notificationScope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPlatform.asked = true
        notificationDelivery.refreshState()
        if (granted && sessions.isCurrent(lease)) notificationScope.launch { notificationDelivery.enable() }
    }
    val enableNotifications = {
        if (sessions.isCurrent(lease)) {
            if (Build.VERSION.SDK_INT >= 33 && !notificationPlatform.granted()) {
                notificationPlatform.asked = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else notificationScope.launch { notificationDelivery.enable() }
        }
        Unit
    }
    val callState = calls?.state?.collectAsStateWithLifecycle()?.value ?: CallsState()
    val speechState by speech.state.collectAsStateWithLifecycle()
    DevicesLifecycle(devices)
    RingCollectorsLifecycle(ringCollectors)
    val dictationState by dictation.state.collectAsStateWithLifecycle()
    val authState by auth.state.collectAsStateWithLifecycle()
    val identity by sessions.identity.collectAsStateWithLifecycle()
    val ambientState = ambient?.state?.collectAsStateWithLifecycle()?.value
    var ambientControlsOpen by rememberSaveable { mutableStateOf(false) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    val observedPlugins by plugins.state.collectAsStateWithLifecycle()
    val pluginState = observedPlugins.takeIf { it.namespace == lease.namespace } ?: NativePluginsState()
    // Voice is an in-memory action: restored route keys never restart the microphone.
    var voiceSequence by remember { mutableStateOf(0L) }
    var dictationRequest by remember { mutableStateOf<Long?>(null) }
    val consumeDictationRequest: (Long) -> Boolean = { request ->
        if (dictationRequest == request) { dictationRequest = null; true } else false
    }
    val openTopLevel: (Destination) -> Unit = { destination ->
        showDevices = false
        showProfile = false
        dictationRequest = null
        backStack.clear()
        backStack.add(Destination.Section(FamilyScreen.Today))
        if (destination != Destination.Section(FamilyScreen.Today)) backStack.add(destination)
    }
    val currentDestination = backStack.lastOrNull() as? Destination
    val taskScreenVisible = when (currentDestination) {
        is Destination.Section -> currentDestination.screen == FamilyScreen.Tasks
        is Destination.TaskList, is Destination.TaskNew -> true
        is Destination.Detail -> currentDestination.screen in setOf(FamilyDetail.Task, FamilyDetail.SharedList)
        else -> false
    }
    val tasksLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(tasks, lease, tasksLifecycle, taskScreenVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if (taskScreenVisible) when (event) {
                Lifecycle.Event.ON_START -> tasks.activate()
                Lifecycle.Event.ON_STOP -> tasks.deactivate()
                else -> Unit
            }
        }
        tasksLifecycle.addObserver(observer)
        if (taskScreenVisible && tasksLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) tasks.activate()
        onDispose {
            tasksLifecycle.removeObserver(observer)
            if (taskScreenVisible) tasks.deactivate()
        }
    }
    LaunchedEffect(currentDestination) {
        if (currentDestination?.primarySection() != FamilyScreen.Assistant) dictationRequest = null
    }
    val dictate = {
        if ((backStack.lastOrNull() as? Destination)?.primarySection() != FamilyScreen.Assistant) {
            openTopLevel(Destination.Section(FamilyScreen.Assistant))
        }
        voiceSequence += 1
        dictationRequest = voiceSequence
    }
    LaunchedEffect(sectionNavigation) {
        sectionNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val destination = Destination.Section(request.screen)
            if (backStack.lastOrNull() != destination) {
                if (request.screen == FamilyScreen.Assistant) openTopLevel(destination)
                else backStack.add(destination)
            }
            onSectionNavigationHandled(request)
        }
    }
    if (ambient != null) AmbientControlHost(ambient, ambientControlsOpen,
        onDismiss = { ambientControlsOpen = false }, onSignIn = { ambientControlsOpen = false })
    val writableRing = ring.takeIf { identity?.scopes?.contains("personal.write") == true }
    RingScreenLifecycle(writableRing, showDevices || backStack.lastOrNull() == Destination.Section(FamilyScreen.Devices))
    val fixAssistantAccess = onAssistantAccess.takeIf { identity?.systemAdmin == true }
    val currentIdentity = rememberUpdatedState(identity)
    val currentUnread = rememberUpdatedState(notificationState?.unread ?: 0)
    val currentOpenManagement = rememberUpdatedState(onOpenManagement)
    LaunchedEffect(authState.welcome) {
        if (authState.welcome && backStack.last() != Destination.Section(FamilyScreen.Welcome)) {
            backStack.clear()
            backStack.add(Destination.Section(FamilyScreen.Welcome))
        }
    }
    LaunchedEffect(assistantNavigation) {
        assistantNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val destination = request.messageId?.let { Destination.Detail(FamilyDetail.AssistantMessage, it) }
                ?: Destination.Section(FamilyScreen.Assistant)
            if (backStack.lastOrNull() != destination) openTopLevel(destination)
            else request.messageId?.let { assistant.openMessage(it) }
            onAssistantNavigationHandled(request)
        }
    }
    LaunchedEffect(taskNavigation) {
        taskNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val destination = when {
                request.taskId != null -> Destination.Detail(FamilyDetail.Task, request.taskId)
                request.listId != null -> Destination.TaskList(request.listId)
                else -> Destination.Section(FamilyScreen.Tasks)
            }
            if (backStack.lastOrNull() != destination) backStack.add(destination)
            else if (request.taskId != null) tasks.openTask(request.taskId)
            else request.listId?.let { tasks.activate(it); tasks.refresh() }
            onTaskNavigationHandled(request)
        }
    }
    LaunchedEffect(deviceNavigation) {
        deviceNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val list = Destination.Section(FamilyScreen.Devices)
            val destination = request.deviceId?.let { Destination.Detail(FamilyDetail.Device, it) } ?: list
            if (backStack.lastOrNull() != destination) {
                if (request.deviceId != null && backStack.lastOrNull() != list) backStack.add(list)
                backStack.add(destination)
            }
            onDeviceNavigationHandled(request)
        }
    }
    LaunchedEffect(pluginNavigation) {
        pluginNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val destination = request.installation?.let { Destination.Plugin(it, request.screen, request.coin) }
            if (destination == null) showProfile = true
            else if (backStack.lastOrNull() != destination) backStack.add(destination)
            onPluginNavigationHandled(request)
        }
    }
    val openPlugin = { id: String ->
        openTopLevel(Destination.Plugin(id))
    }
    LaunchedEffect(circleNavigation) {
        circleNavigation?.let { request ->
            showDevices = false
            showProfile = false
            if (backStack.lastOrNull() != request.destination) backStack.add(request.destination)
            onCircleNavigationHandled(request)
        }
    }
    val openDisplay = { id: String ->
        showDevices = false
        val destination = Destination.Detail(FamilyDetail.Device, id)
        if (backStack.lastOrNull() != destination) backStack.add(destination)
        Unit
    }
    val deviceRevoked = {
        if (backStack.lastOrNull() is Destination.Detail) backStack.removeAt(backStack.lastIndex)
        val list = Destination.Section(FamilyScreen.Devices)
        if (backStack.lastOrNull() != list) backStack.add(list)
        Unit
    }
    LaunchedEffect(timerNavigation) {
        timerNavigation?.let { sequence ->
            showDevices = false
            showProfile = false
            val destination = Destination.Section(FamilyScreen.Timers)
            if (backStack.lastOrNull() != destination) backStack.add(destination)
            onTimerNavigationHandled(sequence)
        }
    }
    LaunchedEffect(notificationNavigation) {
        notificationNavigation?.let { request ->
            if (request.owner != null && !sessions.isCurrent(request.owner)) {
                onNotificationNavigationHandled(request)
                return@LaunchedEffect
            }
            showDevices = false
            showProfile = false
            val destination = request.target.nativeDestination()
            if (backStack.lastOrNull() != destination) {
                if (destination.primarySection() == FamilyScreen.Assistant) openTopLevel(destination)
                else backStack.add(destination)
            }
            else when (val target = request.target) {
                is NotificationDestination.Task -> tasks.openTask(target.id)
                is NotificationDestination.Message -> assistant.openMessage(target.id)
                else -> Unit
            }
            onNotificationNavigationHandled(request)
        }
    }
    val goBack = {
        if (backStack.size > 1) {
            when (val destination = backStack.lastOrNull()) {
                is Destination.TaskNew -> tasks.closeTask()
                is Destination.Detail -> if (destination.screen == FamilyDetail.Task) tasks.closeTask()
                else -> Unit
            }
            backStack.removeAt(backStack.lastIndex)
        }
        Unit
    }
    val openTask: (String, String) -> Unit = { id, tab ->
        backStack.add(Destination.Detail(FamilyDetail.Task, id, taskTab = tab))
    }
    val replaceTask: (String, String) -> Unit = { id, tab ->
        val destination = backStack.lastOrNull() as? Destination.Detail
        if (destination?.screen == FamilyDetail.Task) {
            backStack[backStack.lastIndex] = destination.copy(id = id, taskTab = tab)
        }
    }
    val createTask: (String?, TaskState) -> Unit = { listId, initialState ->
        backStack.add(Destination.TaskNew(listId, initialState))
    }
    LaunchedEffect(personalNavigation) {
        personalNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val destination = when {
                request.sourceId != null && request.rowKey != null -> Destination.Detail(
                    FamilyDetail.PersonalSourceRow, request.rowKey, request.sourceId)
                request.sourceId != null -> Destination.Detail(FamilyDetail.PersonalSource, request.sourceId)
                else -> Destination.Section(request.screen)
            }
            if (backStack.lastOrNull() != destination) backStack.add(destination)
            onPersonalNavigationHandled(request)
        }
    }
    val openPersonalSource = { id: String ->
        backStack.add(Destination.Detail(FamilyDetail.PersonalSource, id)); Unit
    }
    val openPersonalSourceRow = { source: String, row: String ->
        backStack.add(Destination.Detail(FamilyDetail.PersonalSourceRow, row, source)); Unit
    }
    val openPersonalHistory = {
        backStack.add(Destination.Section(FamilyScreen.PersonalHistory)); Unit
    }
    val openPersonalDerived = { score: PersonalDerivedScore ->
        backStack.add(Destination.PersonalHistory("derived_scores", score.resultId)); Unit
    }
    val openSourceHistory = { metric: String, period: Int ->
        backStack.add(Destination.PersonalHistory(metric = metric, period = period)); Unit
    }
    val toCircle = { openTopLevel(Destination.Section(FamilyScreen.Circle)) }
    val toAssistant = { openTopLevel(Destination.Section(FamilyScreen.Assistant)) }
    val openChannel = { id: String -> backStack.add(Destination.Detail(FamilyDetail.Channel, id)); Unit }
    val openSpace = { id: String -> backStack.add(Destination.Detail(FamilyDetail.Space, id)); Unit }
    LaunchedEffect(activityNavigation) {
        activityNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val destination = request.sessionId?.let { Destination.Detail(FamilyDetail.ActivitySession, it) }
                ?: Destination.Section(FamilyScreen.Activity)
            if (backStack.lastOrNull() != destination) backStack.add(destination)
            onActivityNavigationHandled(request)
        }
    }
    LaunchedEffect(placesNavigation) {
        placesNavigation?.let { request ->
            showDevices = false
            showProfile = false
            val destination = when {
                request.settings -> Destination.Section(FamilyScreen.LocationSettings)
                request.date != null -> Destination.Detail(FamilyDetail.PlacesDay, request.date)
                else -> Destination.Section(FamilyScreen.Places)
            }
            if (backStack.lastOrNull() != destination) backStack.add(destination)
            onPlacesNavigationHandled(request)
        }
    }
    fun shellEntry(destination: Destination, content: @Composable () -> Unit): NavEntry<NavKey> {
        val topLevel = destination.isTopLevel() && destination.primarySection() != FamilyScreen.Assistant
        val chrome = NativeChrome(
            onBack = goBack.takeIf { !topLevel && backStack.size > 1 },
            actions = {
                if (topLevel) NativeShellActions(currentIdentity.value, currentUnread.value,
                    onDevices = { showDevices = true },
                    onNotifications = { backStack.add(Destination.Section(FamilyScreen.Notifications)) },
                    onProfile = { showProfile = true })
            },
        )
        return NavEntry(destination) { CompositionLocalProvider(LocalNativeChrome provides chrome) { content() } }
    }
    val navigatePlugin: (String, String, String?) -> Unit = { installation, screen, coin ->
        val target = Destination.Plugin(installation, screen, coin)
        if (backStack.lastOrNull() != target) {
            if (coin == null && screen in pluginTabScreens) {
                // Choosing a tab leaves any position drilldown and replaces this installation's root.
                while (backStack.size > 1 &&
                    (backStack[backStack.lastIndex - 1] as? Destination)?.pluginInstallation() == installation) {
                    backStack.removeAt(backStack.lastIndex)
                }
                backStack[backStack.lastIndex] = target
            } else backStack.add(target)
        }
    }
    val tabBarVisible = currentDestination != Destination.Glasses &&
        currentDestination?.primarySection() != FamilyScreen.Assistant && !WindowInsets.isImeVisible
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = {
            Column {
                NativeCallElsewhere(callState, onHangUp = { calls?.hangUp() })
                SpeechPlayerPanel(speech, onOpenSettings = { backStack.add(Destination.Section(FamilyScreen.SpeechSettings)) },
                    navigationBarInset = !tabBarVisible)
                if (tabBarVisible) NativeTabBar(identity?.scopes.orEmpty(), currentDestination?.primarySection(),
                    onOpen = { openTopLevel(Destination.Section(it)) }, onVoice = dictate)
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            onBack = goBack,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = { key ->
                when (val destination = key as Destination) {
                    Destination.Glasses -> shellEntry(destination) { EvenG2Route(onBack = goBack) }
                    is Destination.Section -> shellEntry(destination) {
                        val navigate = { screen: FamilyScreen ->
                            val target = Destination.Section(screen)
                            if (screen == FamilyScreen.Assistant) openTopLevel(target) else backStack.add(target)
                            Unit
                        }
                        when (destination.screen) {
                            FamilyScreen.Today -> {
                                TodayAttentionLifecycle(attention)
                                val attentionState by attention.state.collectAsStateWithLifecycle()
                                TodayScreen(today, lease, attentionState, navigate,
                                    onRecover = { key ->
                                        when (attentionState.entries.firstOrNull { it.key == key }?.requiredScope) {
                                            "nutrition.read" -> backStack.add(Destination.FoodDay())
                                            "tasks.read" -> backStack.add(Destination.Section(FamilyScreen.Tasks))
                                            "timers.read" -> backStack.add(Destination.Section(FamilyScreen.Timers))
                                            "personal.read" -> backStack.add(Destination.PersonalEntry(recoveryKey = key))
                                            "activity.read" -> backStack.add(Destination.ActivityEntry(recoveryKey = key))
                                            else -> openTopLevel(Destination.AssistantEntry(recoveryKey = key))
                                        }
                                    },
                                    onOpenFood = { date -> backStack.add(Destination.FoodDay(date)) },
                                    onOpenTask = { id -> backStack.add(Destination.Detail(FamilyDetail.Task, id)) },
                                    onAddWeight = { backStack.add(Destination.PersonalEntry(openWeight = true)) },
                                    onRefreshAttention = attention::refresh,
                                    ambientCard = { ambientState?.let { state ->
                                        TodayAmbientCard(state, onOpenControls = { ambientControlsOpen = true },
                                            onRecordings = { navigate(FamilyScreen.Recordings) })
                                    } },
                                )
                            }
                            FamilyScreen.Settings -> SettingsScreen(
                                settings, sessions, auth, navigate,
                                { currentOpenManagement.value() }, appVersion, updates, onInstallPermission,
                                serverName, serverOrigin, onChangeServer,
                            )
                            FamilyScreen.Notifications -> NotificationsScreen(notifications, lease,
                                onOpen = { notification -> notificationDestination(notification.link)?.let { target ->
                                    val route = target.nativeDestination()
                                    if (route.primarySection() == FamilyScreen.Assistant) openTopLevel(route)
                                    else backStack.add(route)
                                } })
                            FamilyScreen.NotificationSettings -> NotificationSettingsScreen(notifications, lease,
                                notificationDevice, onEnable = enableNotifications,
                                onDisable = { notificationScope.launch { notificationDelivery.disable() } })
                            FamilyScreen.SpeechSettings -> SpeechSettingsScreen(
                                speechSettings,
                                onPreview = { connection, voice, model -> if (!dictationState.active) speech.preview(connection, voice, model) },
                                onStopPreview = { if (speech.state.value.key == "preview") speech.stop() },
                                previewPlaying = speechState.active && speechState.key == "preview",
                                previewProblem = speechState.problem?.let(::speechProblemLabel).takeIf { speechState.key == "preview" },
                                ambientControls = { ambientState?.let { state ->
                                    AmbientSettingsCard(state, onOpenControls = { ambientControlsOpen = true })
                                } },
                            )
                            FamilyScreen.Recordings -> if (recordings != null) RecordingsScreen(recordings, lease,
                                playbackAllowed = !callState.busy && !dictationState.active, onBeforePlayback = speech::stop)
                                else SectionScreen(FamilyScreen.Recordings, navigate)
                            FamilyScreen.Capabilities -> CapabilitiesScreen(settings, sessions)
                            FamilyScreen.Devices -> DevicesScreen(devices, lease, openDisplay,
                                timezone = identity?.timezone ?: "UTC",
                                whoop = whoop.takeIf { "personal.write" in identity?.scopes.orEmpty() },
                                onOpenGlasses = { backStack.add(Destination.Glasses) },
                                ring = writableRing, ringCollectors = ringCollectors,
                                onOpenRingOriginals = { metric -> openSourceHistory(metric, 7) })
                            FamilyScreen.Food -> FoodScreen(food, lease,
                                onOpenStats = { navigate(FamilyScreen.FoodStats) },
                                onOpenAssistant = { navigate(FamilyScreen.Assistant) })
                            FamilyScreen.FoodStats -> FoodStatsScreen(foodStats, lease, onBack = goBack)
                            FamilyScreen.Tasks -> TasksScreen(tasks, lease, taskImages, openTask, createTask,
                                onListAccess = { id -> backStack.add(Destination.Detail(FamilyDetail.ListAccess, id)) })
                            FamilyScreen.Timers -> TimersScreen(timers, lease,
                                onOpenTask = { id -> backStack.add(Destination.Detail(FamilyDetail.Task, id)) })
                            FamilyScreen.Personal -> PersonalScreen(personal, lease,
                                onOpenProfile = { navigate(FamilyScreen.Profile) },
                                onOpenGoals = { navigate(FamilyScreen.Goals) },
                                onOpenHistory = openPersonalHistory,
                                onOpenSources = { navigate(FamilyScreen.Sources) },
                                onOpenDerived = openPersonalDerived, bodyPresentation = bodyPresentation, bodyAssets = bodyAssets)
                            FamilyScreen.Activity -> ActivityScreen(activity, lease,
                                onOpenSession = { id -> backStack.add(Destination.Detail(FamilyDetail.ActivitySession, id)) },
                                onNewSession = { backStack.add(Destination.ActivityEntry(newSession = true)) },
                                ringController = ring.takeIf { identity?.scopes?.contains("personal.read") == true })
                            FamilyScreen.Places -> PlacesScreen(places, lease,
                                onDay = { date -> backStack.add(Destination.Detail(FamilyDetail.PlacesDay, date)) })
                            FamilyScreen.LocationSettings -> LocationSettingsScreen(location, lease,
                                timezone = identity?.timezone ?: "UTC")
                            FamilyScreen.Profile -> PersonalProfileScreen(personal, lease)
                            FamilyScreen.Goals -> PersonalGoalsScreen(personal, lease)
                            FamilyScreen.PersonalHistory -> PersonalHistoryScreen(personalHistory, lease, onBack = goBack,
                                bodyPresentation = bodyPresentation, bodyAssets = bodyAssets, onOpenProfile = { navigate(FamilyScreen.Profile) })
                            FamilyScreen.Sources -> PersonalSourcesScreen(personalSources, lease, openPersonalSource, openSourceHistory)
                            FamilyScreen.Circle, FamilyScreen.Spaces -> CircleScreen(circle, lease,
                                onFriends = { navigate(FamilyScreen.Friends) }, onSpace = openSpace, onChannel = openChannel,
                                onShared = { navigate(FamilyScreen.Shared) })
                            FamilyScreen.Friends -> CircleFriendsScreen(circle, lease, onChannel = openChannel)
                            FamilyScreen.SharingSettings -> SharingScreen(sharing, lease,
                                onMembers = { id -> backStack.add(Destination.Detail(FamilyDetail.SpaceMembers, id)) },
                                onList = { id -> backStack.add(Destination.TaskList(id)) },
                                onFriends = { navigate(FamilyScreen.Friends) })
                            FamilyScreen.Shared -> SharedScreen(sharing, lease,
                                onOpen = { id -> if (id == null) navigate(FamilyScreen.Shared)
                                    else backStack.add(Destination.SharedProjection(id)) })
                            FamilyScreen.Assistant -> AssistantScreen(assistant, circle, lease, dictation, dictationPermission, speech = speech,
                                timezone = identity?.timezone ?: "UTC",
                                photos = photos, images = images, photoNormalizer = photoNormalizer, photoAcquisition = photoAcquisition, fileExport = fileExport,
                                onSpeechSettings = { backStack.add(Destination.Section(FamilyScreen.SpeechSettings)) },
                                dictationRequest = dictationRequest.takeIf { currentDestination == destination },
                                onDictationRequestHandled = { request ->
                                    backStack.lastOrNull() == destination && consumeDictationRequest(request)
                                },
                                onFixAccess = fixAssistantAccess)
                            FamilyScreen.AccountDeletion -> AccountDeletionScreen(deletion, account = lease.namespace)
                            FamilyScreen.Welcome -> WelcomeScreen(sessions) {
                                auth.dismissWelcome()
                                openTopLevel(Destination.Section(it))
                            }
                            FamilyScreen.NoAccess -> NoAccessScreen { navigate(FamilyScreen.Today) }
                            else -> SectionScreen(destination.screen, navigate)
                        }
                    }
                    is Destination.AssistantEntry -> shellEntry(destination) {
                        AssistantScreen(assistant, circle, lease, dictation, dictationPermission, speech = speech,
                            timezone = identity?.timezone ?: "UTC",
                            photos = photos, images = images, photoNormalizer = photoNormalizer,
                            photoAcquisition = photoAcquisition, fileExport = fileExport,
                            onSpeechSettings = { backStack.add(Destination.Section(FamilyScreen.SpeechSettings)) },
                            initialRecoveryKey = destination.recoveryKey,
                            dictationRequest = dictationRequest.takeIf { currentDestination == destination },
                            onDictationRequestHandled = { request ->
                                backStack.lastOrNull() == destination && consumeDictationRequest(request)
                            },
                            onFixAccess = fixAssistantAccess)
                    }
                    is Destination.FoodDay -> shellEntry(destination) {
                        FoodScreen(food, lease, initialDate = destination.date,
                            onOpenStats = { backStack.add(Destination.Section(FamilyScreen.FoodStats)) },
                            onOpenAssistant = { openTopLevel(Destination.Section(FamilyScreen.Assistant)) })
                    }
                    is Destination.TaskList -> shellEntry(destination) {
                        TasksScreen(tasks, lease, taskImages, openTask, createTask, initialListId = destination.id,
                            onListAccess = { id -> backStack.add(Destination.Detail(FamilyDetail.ListAccess, id)) })
                    }
                    is Destination.TaskNew -> shellEntry(destination) {
                        TaskDetailScreen(tasks, taskAssistant, timers, lease, taskImages, photoNormalizer,
                            photoAcquisition, fileExport, initialListId = destination.listId,
                            initialState = destination.initialState, onOpenTask = replaceTask, onClose = goBack)
                    }
                    is Destination.SharedProjection -> shellEntry(destination) {
                        SharedScreen(sharing, lease, sharedId = destination.id, initialDay = destination.day,
                            onOpen = { id -> if (id == null) goBack() else backStack.add(Destination.SharedProjection(id)) })
                    }
                    is Destination.Timer -> shellEntry(destination) {
                        TimersScreen(timers, lease, onOpenTask = { id -> backStack.add(Destination.Detail(FamilyDetail.Task, id)) },
                            initialTimerId = destination.id)
                    }
                    is Destination.Reminder -> shellEntry(destination) {
                        TimersScreen(timers, lease, onOpenTask = { id -> backStack.add(Destination.Detail(FamilyDetail.Task, id)) },
                            initialReminderId = destination.id)
                    }
                    is Destination.PersonalEntry -> shellEntry(destination) {
                        LaunchedEffect(destination.recoveryKey, lease) {
                            destination.recoveryKey?.let { recoveryKey ->
                                val target = runCatching { personal.recoveryOwner(recoveryKey) }.getOrNull()
                                val screen = when (target) {
                                    PersonalRecoveryOwner.History -> FamilyScreen.PersonalHistory
                                    PersonalRecoveryOwner.Sources -> FamilyScreen.Sources
                                    else -> null
                                }
                                if (screen != null && sessions.isCurrent(lease) && backStack.lastOrNull() == destination) {
                                    backStack[backStack.lastIndex] = Destination.Section(screen)
                                }
                            }
                        }
                        PersonalScreen(personal, lease,
                            onOpenProfile = { backStack.add(Destination.Section(FamilyScreen.Profile)) },
                            onOpenGoals = { backStack.add(Destination.Section(FamilyScreen.Goals)) },
                            onOpenHistory = openPersonalHistory,
                            onOpenSources = { backStack.add(Destination.Section(FamilyScreen.Sources)) },
                            onOpenDerived = openPersonalDerived,
                            openWeight = destination.openWeight, recoveryKey = destination.recoveryKey,
                            bodyPresentation = bodyPresentation, bodyAssets = bodyAssets)
                    }
                    is Destination.PersonalHistory -> shellEntry(destination) {
                        PersonalHistoryScreen(personalHistory, lease, onBack = goBack,
                            initialMetric = destination.metric, initialResultId = destination.resultId,
                            initialPeriod = destination.period, bodyPresentation = bodyPresentation, bodyAssets = bodyAssets,
                            onOpenProfile = { backStack.add(Destination.Section(FamilyScreen.Profile)) })
                    }
                    is Destination.Plugin -> shellEntry(destination) {
                        NativePluginScreen(plugins, destination.installation, destination.screen, destination.coin) { screen, coin ->
                            navigatePlugin(destination.installation, screen, coin)
                        }
                    }
                    is Destination.ActivityEntry -> shellEntry(destination) {
                        if (destination.newSession) ActivitySessionScreen(activity, lease, null, goBack)
                        else ActivityScreen(activity, lease,
                            onOpenSession = { id -> backStack.add(Destination.Detail(FamilyDetail.ActivitySession, id)) },
                            onNewSession = { backStack.add(Destination.ActivityEntry(newSession = true)) },
                            recoveryKey = destination.recoveryKey,
                            ringController = ring.takeIf { identity?.scopes?.contains("personal.read") == true })
                    }
                    is Destination.Detail -> shellEntry(destination) {
                        if (destination.screen == FamilyDetail.AssistantMessage) {
                            AssistantScreen(assistant, circle, lease, dictation, dictationPermission, speech = speech,
                                timezone = identity?.timezone ?: "UTC",
                                photos = photos, images = images, photoNormalizer = photoNormalizer, photoAcquisition = photoAcquisition, fileExport = fileExport,
                                onSpeechSettings = { backStack.add(Destination.Section(FamilyScreen.SpeechSettings)) }, initialMessageId = destination.id,
                                dictationRequest = dictationRequest.takeIf { currentDestination == destination },
                                onDictationRequestHandled = { request ->
                                    backStack.lastOrNull() == destination && consumeDictationRequest(request)
                                },
                                onFixAccess = fixAssistantAccess)
                        } else if (destination.screen == FamilyDetail.ActivitySession) {
                            ActivitySessionScreen(activity, lease, destination.id, goBack)
                        } else if (destination.screen == FamilyDetail.PlacesDay) {
                            PlaceDayScreen(places, lease, destination.id, goBack)
                        } else if (destination.screen == FamilyDetail.Task) {
                            TaskDetailScreen(tasks, taskAssistant, timers, lease, taskImages, photoNormalizer,
                                photoAcquisition, fileExport, taskId = destination.id,
                                initialTab = destination.taskTab, onOpenTask = replaceTask, onClose = goBack)
                        } else if (destination.screen == FamilyDetail.SharedList) {
                            TasksScreen(tasks, lease, taskImages, openTask, createTask, initialListId = destination.id,
                                onListAccess = { id -> backStack.add(Destination.Detail(FamilyDetail.ListAccess, id)) })
                        } else if (destination.screen == FamilyDetail.Device) {
                            DisplayScreen(display, lease, destination.id, deviceRevoked,
                                timezone = identity?.timezone ?: "UTC")
                        } else if (destination.screen == FamilyDetail.PersonalSource) {
                            PersonalSourceScreen(personalSources, personalDdx, lease, destination.id,
                                openPersonalSourceRow, openSourceHistory)
                        } else if (destination.screen == FamilyDetail.PersonalSourceRow) {
                            PersonalSourceDataScreen(personalSources, lease, requireNotNull(destination.parentId),
                                destination.id, openSourceHistory)
                        } else if (destination.screen == FamilyDetail.PluginScreen) {
                            NativePluginScreen(plugins, destination.id, destination.parentId ?: "home", null) { screen, coin ->
                                navigatePlugin(destination.id, screen, coin)
                            }
                        } else if (destination.screen == FamilyDetail.Channel) {
                            CircleChatScreen(circle, lease, destination.id, toCircle, toAssistant,
                                timezone = identity?.timezone ?: "UTC",
                                onCall = calls?.takeIf { identity?.scopes?.containsAll(setOf("calls.read", "calls.write")) == true }
                                    ?.let { it::dial }, callsBusy = callState.busy)
                        } else if (destination.screen == FamilyDetail.Space) {
                            CircleSpaceScreen(circle, lease, destination.id,
                                onMembers = { backStack.add(Destination.Detail(FamilyDetail.SpaceMembers, destination.id)) },
                                onChannel = openChannel,
                                onList = { id -> backStack.add(Destination.TaskList(id)) },
                                onToCircle = toCircle, onToAssistant = toAssistant,
                                onChallenges = { backStack.add(Destination.Detail(FamilyDetail.SpaceChallenges, destination.id)) },
                                canReadChallenges = "challenges.read" in identity?.scopes.orEmpty())
                        } else if (destination.screen == FamilyDetail.SpaceChallenges) {
                            ChallengesScreen(challenges, lease, destination.id,
                                onChallenge = { id -> backStack.add(Destination.Detail(FamilyDetail.Challenge, id)) },
                                onToCircle = toCircle, onToAssistant = toAssistant)
                        } else if (destination.screen == FamilyDetail.Challenge) {
                            ChallengeScreen(challenges, lease, destination.id,
                                onFood = { backStack.add(Destination.Section(FamilyScreen.Food)) },
                                onToCircle = toCircle, onToAssistant = toAssistant)
                        } else if (destination.screen == FamilyDetail.ListAccess) {
                            ListGrantsScreen(sharing, lease, destination.id)
                        } else if (destination.screen == FamilyDetail.SpaceMembers) {
                            CircleMembersScreen(circle, lease, destination.id, toCircle, toAssistant)
                        } else PlaceholderScreen(destination.screen.title)
                    }
                }
            },
        )
    }
    if (showProfile) identity?.let { viewer ->
        NativeProfileSheet(viewer, plugins, pluginState, theme, onThemeChanged,
            onDismiss = { showProfile = false },
            onOpen = { openTopLevel(Destination.Section(it)) }, onPlugin = openPlugin,
            onLogout = { notificationScope.launch { auth.signOut() } })
    }
    if (showDevices) DevicesSheet(devices, lease, onDismiss = { showDevices = false },
        onOpenDisplay = openDisplay, timezone = identity?.timezone ?: "UTC", whoop = whoop,
        onOpenGlasses = { showDevices = false; backStack.add(Destination.Glasses) },
        onOpenSource = { source ->
            showDevices = false
            showProfile = false
            if (source == null) backStack.add(Destination.Section(FamilyScreen.Sources))
            else openPersonalSource(source)
        }, ring = writableRing, ringCollectors = ringCollectors,
        onOpenRingOriginals = { metric -> showDevices = false; openSourceHistory(metric, 7) })
}

/** Radio commands belong to a visible ring screen and its current native session. */
@Composable
private fun RingScreenLifecycle(controller: RingController?, visible: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle, visible) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller?.setForegroundVisible(visible)
                Lifecycle.Event.ON_STOP -> controller?.setForegroundVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        controller?.setForegroundVisible(visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); controller?.setForegroundVisible(false) }
    }
}

@Composable
private fun RingCollectorsLifecycle(controller: RingCollectorsController?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller?.activate()
                Lifecycle.Event.ON_STOP -> controller?.deactivate()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller?.activate()
        onDispose { lifecycle.removeObserver(observer); controller?.deactivate() }
    }
}

@Composable
private fun DevicesLifecycle(controller: DevicesController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.activate()
                Lifecycle.Event.ON_STOP -> controller.deactivate()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate()
        onDispose { lifecycle.removeObserver(observer); controller.deactivate() }
    }
}

@Composable
private fun TodayAttentionLifecycle(controller: TodayAttentionController) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.activate()
                Lifecycle.Event.ON_STOP -> controller.deactivate()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.activate()
        onDispose { lifecycle.removeObserver(observer); controller.deactivate() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeAuthHeader() {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
        title = { Text(stringResource(DesignR.string.app_title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            Surface(color = GeneratedColors.light.getValue("icon-background"), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(start = 16.dp).size(40.dp)) {
                Image(painterResource(R.drawable.ic_bro_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        },
    )
}

@Composable
private fun SectionScreen(screen: FamilyScreen, onNavigate: (FamilyScreen) -> Unit) {
    ScreenColumn("screen_${screen.name}", title = screen.title) {
        UnavailableCard()
        screen.children().forEach { child ->
            Button(onClick = { onNavigate(child) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open_${child.name}")) {
                Text(stringResource(child.title))
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: Int) {
    ScreenColumn("screen_Placeholder", title = title) {
        UnavailableCard()
    }
}
