package assistant.mobile.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import assistant.mobile.android.ambient.AmbientRuntime
import assistant.mobile.core.recordings.RecordingsController
import assistant.mobile.core.AccountDeletionController
import assistant.mobile.core.AuthController
import assistant.mobile.core.AuthLink
import assistant.mobile.core.NativeAuthDestination
import assistant.mobile.core.NativeServerCheckResult
import assistant.mobile.core.NativeServerKind
import assistant.mobile.core.NativeServerProbe
import assistant.mobile.core.canonicalOrigin
import assistant.mobile.core.NativeServerSwitchOperation
import assistant.mobile.core.NativeServerSwitchPreparation
import assistant.mobile.core.NativeServerSwitchResult
import assistant.mobile.core.EventDomain
import assistant.mobile.core.EventInvalidation
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeClientCore
import assistant.mobile.core.NativeClientData
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SettingsController
import assistant.mobile.core.NativeUpdateController
import assistant.mobile.core.NativeUpdateHttpClient
import assistant.mobile.core.assistant.AssistantController
import assistant.mobile.core.today.TodayController
import assistant.mobile.core.today.TodayAttentionController
import assistant.mobile.core.today.TodayCard
import assistant.mobile.core.tasks.TasksController
import assistant.mobile.core.tasks.TaskAssistantController
import assistant.mobile.core.timers.TimersController
import assistant.mobile.core.notifications.NotificationsController
import assistant.mobile.core.notifications.NativeNotificationCoordinator
import assistant.mobile.core.notifications.NotificationDestination
import assistant.mobile.core.notifications.NotificationOpen
import assistant.mobile.android.notify.AndroidNotifications
import assistant.mobile.android.whoop.WhoopRepository
import assistant.mobile.android.whoop.WhoopStore
import assistant.mobile.core.food.FoodController
import assistant.mobile.core.food.FoodStatsController
import assistant.mobile.core.devices.DevicesController
import assistant.mobile.core.devices.DisplayController
import assistant.mobile.core.ring.RingCollectorsController
import assistant.mobile.platform.ring.RingRepository
import assistant.mobile.platform.ring.RingSession
import assistant.mobile.platform.ring.RingServerSwitchPreparation
import assistant.mobile.platform.AndroidNativeOrigins
import assistant.mobile.android.body.assets.AndroidBodyAssetRepository
import assistant.mobile.core.body.BodyPresentationController
import assistant.mobile.core.personal.PersonalController
import assistant.mobile.core.personal.PersonalHistoryController
import assistant.mobile.core.personal.PersonalSourcesController
import assistant.mobile.core.personal.PersonalDdxController
import assistant.mobile.core.circle.CircleController
import assistant.mobile.core.sharing.SharingController
import assistant.mobile.core.challenges.ChallengesController
import assistant.mobile.core.activity.ActivityController
import assistant.mobile.core.calls.CallMediaFactory
import assistant.mobile.core.calls.CallsController
import assistant.mobile.core.dictation.DictationProblem
import assistant.mobile.core.location.BackgroundRecording
import assistant.mobile.core.location.ForegroundLocationReporter
import assistant.mobile.core.location.LocationController
import assistant.mobile.core.location.LOCATION_REPORT_EVERY_MS
import assistant.mobile.core.location.LocationQueuePolicy
import assistant.mobile.core.location.NativeLocationApi
import assistant.mobile.core.location.PositionSource
import assistant.mobile.core.places.NativePlacesApi
import assistant.mobile.core.places.PlacesController
import assistant.mobile.platform.location.AndroidBackgroundRecording
import assistant.mobile.platform.location.AndroidPositionSource
import assistant.mobile.core.dictation.DictationAudioSource
import assistant.mobile.core.dictation.DictationController
import assistant.mobile.core.dictation.DictationDraftSnapshot
import assistant.mobile.core.dictation.DictationLatency
import assistant.mobile.core.dictation.NativeDictationReadiness
import assistant.mobile.android.update.AndroidUpdatePlatform
import assistant.mobile.android.update.androidUpdateOrigin
import assistant.mobile.core.journal.JournalDatabase
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.platform.KeystoreDeletionSecretStore
import assistant.mobile.platform.KeystoreSessionSecretStore
import assistant.mobile.platform.NativeCorePlatform
import assistant.mobile.platform.FileAssistantHistoryCache
import assistant.mobile.core.speech.SpeechAudioSink
import assistant.mobile.core.speech.SpeechPlaybackController
import assistant.mobile.core.speech.NativeSpeechPlaybackTransport
import assistant.mobile.core.speech.SpeechSettingsController
import assistant.mobile.platform.AndroidSpeechAudioSink
import assistant.mobile.platform.AndroidDictationAudioSource
import assistant.mobile.platform.AndroidLiveSocketFactory
import assistant.mobile.platform.AndroidCallMediaFactory
import assistant.mobile.platform.AndroidCallSignalFactory
import assistant.mobile.core.photos.ImageAcquisition
import assistant.mobile.core.photos.PhotoNormalizer
import assistant.mobile.core.photos.PhotoAttachmentController
import assistant.mobile.core.photos.PrivateConversationImages
import assistant.mobile.core.photos.PrivateImageRoute
import assistant.mobile.core.photos.PhotoAttachmentStatus
import assistant.mobile.platform.AndroidPhotoNormalizer
import assistant.mobile.design.GeneratedColors
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SectionNavigationRequest(val screen: FamilyScreen, val sequence: Long)
data class AssistantNavigationRequest(val messageId: String?, val sequence: Long)
data class TaskNavigationRequest(val taskId: String? = null, val listId: String? = null, val sequence: Long)
data class DeviceNavigationRequest(val deviceId: String?, val sequence: Long)
data class NotificationNavigationRequest(val target: NotificationDestination, val sequence: Long,
    val owner: SessionLease?)
data class PersonalNavigationRequest(val screen: FamilyScreen, val sourceId: String? = null,
    val rowKey: String? = null, val sequence: Long)
data class PluginNavigationRequest(val installation: String?, val screen: String = "home", val coin: String? = null, val sequence: Long)
data class CircleNavigationRequest(val destination: Destination, val sequence: Long)
data class ActivityNavigationRequest(val sessionId: String?, val sequence: Long)
data class PlacesNavigationRequest(val date: String?, val settings: Boolean, val sequence: Long)

/** Activity-retained graph: configuration changes never create a second session/event owner. */
class NativeRuntime(
    context: Context,
    origin: String = NativeOrigins.get(context).homeOrigin,
    instance: String = "native",
    private val clockMs: () -> Long = System::currentTimeMillis,
    dictationAudioSource: DictationAudioSource? = null,
    speechAudioSink: SpeechAudioSink? = null,
    callMediaFactory: CallMediaFactory? = null,
    val photoAcquisition: ImageAcquisition? = null,
    photoNormalizer: PhotoNormalizer? = null,
    positionSource: PositionSource? = null,
    backgroundRecording: BackgroundRecording? = null,
    private val updateHttp: NativeUpdateHttpClient = NativeUpdateHttpClient(
        NativeCorePlatform.httpEngine(), androidUpdateOrigin(context),
        allowInsecureLoopback = BuildConfig.DEBUG,
    ),
    dictationTelemetry: (DictationLatency) -> Unit = { timing ->
        Log.i("NativeDictation", "stop_to_final_ms=${timing.stopToFinalMillis} " +
            "stop_to_finish_ms=${timing.stopToFinishMillis} final_delivery_ms=${timing.finalDeliveryMillis} " +
            "client_added_ms=${timing.clientAddedMillis}")
    },
    private val restartApplication: (Context, String, AuthLink?) -> Unit = NativeServerRestart::restart,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    internal val defaultServerOrigin = canonicalOrigin(requireNotNull(context.packageManager.getApplicationInfo(
        context.packageName, PackageManager.GET_META_DATA,
    ).metaData?.getString("assistant.origin")), allowInsecureLoopback = BuildConfig.DEBUG)
    internal val selectedServerName = NativeOrigins.get(context).initialServerSelection
        ?.takeIf { it.kind == NativeServerKind.CUSTOM }?.name ?: "Bro"
    internal val selectedServerOrigin = NativeOrigins.get(context).initialServerSelection
        ?.takeIf { it.kind == NativeServerKind.CUSTOM }?.origin ?: defaultServerOrigin
    internal val serverProbe = NativeServerProbe(NativeCorePlatform.httpEngine(), allowInsecureLoopback = BuildConfig.DEBUG)
    private val mutableServerInvitation = MutableStateFlow<NativeAuthDestination?>(null)
    internal val serverInvitation = mutableServerInvitation.asStateFlow()

    internal fun openServerInvitation(destination: NativeAuthDestination) {
        mutableServerInvitation.value = destination
    }

    internal fun serverInvitationHandled(destination: NativeAuthDestination) {
        mutableServerInvitation.compareAndSet(destination, null)
    }
    @Volatile private var serverSwitchStarted = false
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var callAudioClaimed = false
    private val mutableKeepCallScreenAwake = MutableStateFlow(false)
    val keepCallScreenAwake: StateFlow<Boolean> = mutableKeepCallScreenAwake.asStateFlow()
    private val mutableAssistantNavigation = MutableStateFlow<AssistantNavigationRequest?>(null)
    internal val assistantNavigation: StateFlow<AssistantNavigationRequest?> = mutableAssistantNavigation.asStateFlow()
    private val mutableTaskNavigation = MutableStateFlow<TaskNavigationRequest?>(null)
    internal val taskNavigation: StateFlow<TaskNavigationRequest?> = mutableTaskNavigation.asStateFlow()
    private val mutableDeviceNavigation = MutableStateFlow<DeviceNavigationRequest?>(null)
    internal val deviceNavigation: StateFlow<DeviceNavigationRequest?> = mutableDeviceNavigation.asStateFlow()
    private val mutablePersonalNavigation = MutableStateFlow<PersonalNavigationRequest?>(null)
    internal val personalNavigation: StateFlow<PersonalNavigationRequest?> = mutablePersonalNavigation.asStateFlow()
    private val mutablePluginNavigation = MutableStateFlow<PluginNavigationRequest?>(null)
    internal val pluginNavigation: StateFlow<PluginNavigationRequest?> = mutablePluginNavigation.asStateFlow()
    private val mutableCircleNavigation = MutableStateFlow<CircleNavigationRequest?>(null)
    internal val circleNavigation: StateFlow<CircleNavigationRequest?> = mutableCircleNavigation.asStateFlow()
    private val mutableSectionNavigation = MutableStateFlow<SectionNavigationRequest?>(null)
    internal val sectionNavigation = mutableSectionNavigation.asStateFlow()
    internal fun openSection(screen: FamilyScreen) {
        mutableSectionNavigation.value = SectionNavigationRequest(screen, ++navigationSequence)
    }
    internal fun sectionNavigationHandled(request: SectionNavigationRequest) {
        mutableSectionNavigation.compareAndSet(request, null)
    }
    private var navigationSequence = 0L
    private val mutableActivityNavigation = MutableStateFlow<ActivityNavigationRequest?>(null)
    internal val activityNavigation: StateFlow<ActivityNavigationRequest?> = mutableActivityNavigation.asStateFlow()
    private val mutablePlacesNavigation = MutableStateFlow<PlacesNavigationRequest?>(null)
    internal val placesNavigation: StateFlow<PlacesNavigationRequest?> = mutablePlacesNavigation.asStateFlow()
    internal fun openPlaces(date: String? = null) {
        mutablePlacesNavigation.value = PlacesNavigationRequest(date, false, ++navigationSequence)
    }
    internal fun openLocationSettings() {
        mutablePlacesNavigation.value = PlacesNavigationRequest(null, true, ++navigationSequence)
    }
    internal fun placesNavigationHandled(request: PlacesNavigationRequest) {
        mutablePlacesNavigation.compareAndSet(request, null)
    }

    internal fun openActivity(sessionId: String? = null) {
        mutableActivityNavigation.value = ActivityNavigationRequest(sessionId, ++navigationSequence)
    }

    internal fun activityNavigationHandled(request: ActivityNavigationRequest) {
        mutableActivityNavigation.compareAndSet(request, null)
    }
    private val mutableNotificationNavigation = MutableStateFlow<NotificationNavigationRequest?>(null)
    internal val notificationNavigation = mutableNotificationNavigation.asStateFlow()
    internal fun openNotification(target: NotificationDestination) {
        mutableNotificationNavigation.value = NotificationNavigationRequest(target, ++navigationSequence, core.sessions.lease())
    }
    internal fun notificationNavigationHandled(request: NotificationNavigationRequest) {
        mutableNotificationNavigation.compareAndSet(request, null)
    }
    internal fun acceptNotificationOpen(open: NotificationOpen?) {
        if (open == null) return
        scope.launch {
            ready.first { it }
            val lease = core.sessions.lease() ?: return@launch
            val identity = core.sessions.identity.value ?: return@launch
            if (open.session.instance == lease.namespace.instance && open.session.origin == lease.namespace.origin &&
                open.session.userId == lease.namespace.userId && open.session.deviceId == identity.deviceId &&
                open.session.sessionId == lease.sessionId && open.session.active(clockMs())) {
                openNotification(open.destination)
            }
        }
    }
    private val mutableTimerNavigation = MutableStateFlow<Long?>(null)
    internal val timerNavigation: StateFlow<Long?> = mutableTimerNavigation.asStateFlow()

    internal fun openTimers() { mutableTimerNavigation.value = ++navigationSequence }
    internal fun timerNavigationHandled(sequence: Long) { mutableTimerNavigation.compareAndSet(sequence, null) }

    internal fun openPlugin(installation: String?, screen: String = "home", coin: String? = null) {
        mutablePluginNavigation.value = PluginNavigationRequest(installation, screen, coin, ++navigationSequence)
    }

    internal fun pluginNavigationHandled(request: PluginNavigationRequest) {
        mutablePluginNavigation.compareAndSet(request, null)
    }

    internal fun openTasks(taskId: String? = null, listId: String? = null) {
        mutableTaskNavigation.value = TaskNavigationRequest(taskId, listId, ++navigationSequence)
    }

    internal fun taskNavigationHandled(request: TaskNavigationRequest) {
        mutableTaskNavigation.compareAndSet(request, null)
    }

    internal fun openAssistant(messageId: String?) {
        mutableAssistantNavigation.value = AssistantNavigationRequest(messageId, ++navigationSequence)
    }

    internal fun assistantNavigationHandled(request: AssistantNavigationRequest) {
        mutableAssistantNavigation.compareAndSet(request, null)
    }

    internal fun openDevice(deviceId: String?) {
        mutableDeviceNavigation.value = DeviceNavigationRequest(deviceId, ++navigationSequence)
    }

    internal fun deviceNavigationHandled(request: DeviceNavigationRequest) {
        mutableDeviceNavigation.compareAndSet(request, null)
    }

    internal fun openPersonal(screen: FamilyScreen, sourceId: String? = null, rowKey: String? = null) {
        mutablePersonalNavigation.value = PersonalNavigationRequest(screen, sourceId, rowKey, ++navigationSequence)
    }

    internal fun personalNavigationHandled(request: PersonalNavigationRequest) {
        mutablePersonalNavigation.compareAndSet(request, null)
    }

    internal fun openCircle(destination: Destination) {
        mutableCircleNavigation.value = CircleNavigationRequest(destination, ++navigationSequence)
    }

    internal fun circleNavigationHandled(request: CircleNavigationRequest) {
        mutableCircleNavigation.compareAndSet(request, null)
    }

    private val database: JournalDatabase = NativeCorePlatform.journal(context)
    private val journal: RoomJournalStore = RoomJournalStore(database)
    val historyCache: FileAssistantHistoryCache = FileAssistantHistoryCache(context)
    private val mutableReady: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    val ambient = AmbientRuntime.get(context)
    private val liveSockets = AndroidLiveSocketFactory(context,
        allowInsecureLoopback = BuildConfig.DEBUG, serverOrigin = { core.http.serverOrigin })
    val core: NativeClientCore = NativeClientCore(
        scope, instance, origin, KeystoreSessionSecretStore(context), journal,
        NativeCorePlatform.httpEngine(),
        object : NativeClientData {
            override suspend fun refetchAll(lease: SessionLease) {
                if (core.sessions.isCurrent(lease)) {
                    calls.refresh()
                    circle.invalidate(EventInvalidation.All)
                    sharing.invalidate(EventInvalidation.All)
                    challenges.invalidate(EventInvalidation.All)
                    assistant.invalidateHistory(lease)
                    settings.refreshLimits()
                    plugins.refresh()
                    assistant.refresh(lease)
                    today.refresh()
                    food.refresh()
                    foodStats.refresh()
                    taskImages.clear()
                    tasks.refresh()
                    timers.refresh()
                    notifications.refresh()
                    notificationDelivery.sync()
                    taskAssistant.refresh()
                    devices.refresh()
                    ringCollectors?.refresh()
                    display.refresh()
                    personal.refresh()
                    bodyPresentation.refresh()
                    personalHistory.refresh()
                    personalSources.refresh()
                    personalDdx.refresh()
                    activity.refresh()
                    location.refresh()
                    places.refresh()
                    attention.refresh()
                }
            }
            override suspend fun invalidate(lease: SessionLease, invalidation: EventInvalidation) {
                if (core.sessions.isCurrent(lease)) {
                    calls.invalidate(invalidation)
                    circle.invalidate(invalidation)
                    sharing.invalidate(invalidation)
                    challenges.invalidate(invalidation)
                    if (invalidation == EventInvalidation.All) assistant.invalidateHistory(lease)
                    settings.refreshLimits()
                    today.invalidate(invalidation)
                    food.invalidate(invalidation)
                    foodStats.invalidate(invalidation)
                    if (invalidation == EventInvalidation.All || invalidation is EventInvalidation.Domains &&
                        invalidation.values.any { it in setOf(EventDomain.Tasks, EventDomain.Community, EventDomain.Shared) }) {
                        taskImages.clear()
                    }
                    tasks.invalidate(invalidation)
                    timers.invalidate(invalidation)
                    notifications.invalidate(invalidation)
                    if (invalidation == EventInvalidation.All || invalidation is EventInvalidation.Domains &&
                        invalidation.values.any { it in setOf(EventDomain.Notifications, EventDomain.Timers, EventDomain.Tasks) }) {
                        notificationDelivery.sync()
                    }
                    taskAssistant.invalidate(invalidation)
                    if (invalidation == EventInvalidation.All) {
                        plugins.refresh()
                        devices.refresh()
                        display.refresh()
                    }
                    personal.invalidate(invalidation)
                    bodyPresentation.invalidate(invalidation)
                    personalHistory.invalidate(invalidation)
                    personalSources.invalidate(invalidation)
                    if (invalidation == EventInvalidation.All || invalidation is EventInvalidation.Domains &&
                        EventDomain.Personal in invalidation.values) ringCollectors?.refresh()
                    personalDdx.invalidate(invalidation)
                    activity.invalidate(invalidation)
                    if (invalidation == EventInvalidation.All) {
                        location.refresh()
                        places.refresh()
                    }
                }
            }
        },
        liveSockets, clockMs, allowInsecureLoopback = BuildConfig.DEBUG,
        clearCachedAccount = ::clearPrivateAccount,
        serverOrigin = { NativeOrigins.get(context).serverOrigin(origin) },
    )
    val assistant: AssistantController = AssistantController(
        scope, core.sessions, core.http, core.submissions, core.drafts, historyCache, clockMs = clockMs,
    )
    val recordings = RecordingsController(scope, core.sessions, core.http)
    val today = TodayController(scope, core.sessions, core.http, clockMs)
    val food = FoodController(scope, core.sessions, core.http, core.submissions, core.drafts, clockMs)
    val tasks = TasksController(scope, core.sessions, core.http, core.submissions, core.drafts, clockMs)
    val timers = TimersController(scope, core.sessions, core.http, core.submissions, core.drafts, clockMs,
        onChanged = { today.refreshCard(TodayCard.Timers); notificationDelivery.sync() })
    val notificationPlatform = AndroidNotifications.get(context)
    val notificationDelivery = NativeNotificationCoordinator(scope, core.sessions, core.http, notificationPlatform, clockMs)
    val notifications = NotificationsController(scope, core.sessions, core.http,
        onChanged = { today.refreshCard(TodayCard.Notifications); notificationDelivery.sync() }, clockMs = clockMs)
    val taskAssistant = TaskAssistantController(scope, core.sessions, core.http, core.drafts, tasks)
    val bodyPresentation = BodyPresentationController(scope, core.sessions, core.http, core.submissions)
    val bodyAssets = AndroidBodyAssetRepository(context, core.http, core.sessions)
    val personal = PersonalController(scope, core.sessions, core.http, core.submissions, core.drafts, clockMs)
    val personalHistory = PersonalHistoryController(scope, core.sessions, core.http, core.submissions, clockMs = clockMs)
    val personalSources = PersonalSourcesController(scope, core.sessions, core.http, core.submissions, core.drafts, clockMs = clockMs)
    val personalDdx = PersonalDdxController(scope, core.sessions, core.http, core.drafts, clockMs = clockMs)
    val activity = ActivityController(scope, core.sessions, core.http, core.submissions, core.drafts, clockMs)
    val locationPermission = LocationPermissionBridge()
    private val position = positionSource ?: AndroidPositionSource(context,
        locationPermission::requestForeground, locationPermission::showRationale)
    private val locationApi = NativeLocationApi(core.http)
    private var locationEnrollmentLease: SessionLease? = null
    private val backgroundLocation: BackgroundRecording = backgroundRecording ?: AndroidBackgroundRecording(
        context, core.sessions.origin, position, locationPermission::requestBackground,
        enroll = { namespace, installation ->
            val lease = core.sessions.lease()
                ?.takeIf { it.namespace == namespace } ?: error("Location session changed")
            locationEnrollmentLease = lease
            val grant = locationApi.enroll(installation, lease).also {
                check(core.sessions.isCurrent(lease)) { "Location session changed" }
            }
            val sessionExpiry = checkNotNull(core.sessions.identity.value).sessionExpiresAt
            grant.copy(expiresAt = LocationQueuePolicy.effectiveCollectorExpiry(grant.expiresAt, sessionExpiry))
        },
        isCurrentAccount = { namespace -> core.sessions.lease()?.namespace == namespace },
        isCurrentEnrollment = { namespace -> locationEnrollmentLease?.let { lease ->
            lease.namespace == namespace && core.sessions.isCurrent(lease)
        } == true },
    )
    val location = LocationController(scope, core.sessions, locationApi, position, backgroundLocation)
    val places = PlacesController(scope, core.sessions, NativePlacesApi(core.http))
    private val locationReporter = ForegroundLocationReporter(core.sessions, locationApi, position, clockMs)
    private var locationVisible = false
    private var locationTick: Job? = null

    fun setLocationForeground(visible: Boolean) {
        locationVisible = visible
        locationTick?.cancel()
        locationTick = null
        if (!visible) { locationReporter.onHidden(); return }
        locationTick = scope.launch {
            locationReporter.onVisible()
            while (locationVisible) {
                delay(LOCATION_REPORT_EVERY_MS)
                locationReporter.tick()
            }
        }
    }
    val attention = TodayAttentionController(scope, core.sessions, journal::observeSubmissions)
    val images = PrivateConversationImages(scope, core.sessions, core.http)
    val taskImages = PrivateConversationImages(scope, core.sessions, core.http, imageRoute = PrivateImageRoute.Tasks)
    val photos = PhotoAttachmentController(scope, core.sessions, images)
    val photoNormalizer: PhotoNormalizer = photoNormalizer ?: AndroidPhotoNormalizer(
        context, GeneratedColors.light.getValue("photo-background").toArgb(),
    )
    val fileExport = FileExportBridge(context)
    val foodStats = FoodStatsController(scope, core.sessions, core.http, fileExport, clockMs)
    val plugins = NativePluginsController(scope, core.sessions, core.http, core.submissions,
        navigate = { openPlugin(it.installation.value, it.screen) }, closeScreen = { openPlugin(null) })
    val devices = DevicesController(scope, core.sessions, core.http)
    val ring = if (BuildConfig.PLAY_STORE) null else RingRepository.get(context, core.sessions.origin)
    val ringCollectors = if (BuildConfig.PLAY_STORE) null else RingCollectorsController(scope, core.sessions, core.http)
    val display = DisplayController(scope, core.sessions, core.http, onDevicesChanged = devices::refresh)
    internal val whoop = WhoopRepository.get(context, core.sessions.origin).also { it.bind(core.sessions) }
    private val serverSwitch = NativeServerSwitchOperation(
        currentOrigin = { core.http.serverOrigin },
        prepare = {
            when (val prepared = ring?.prepareServerSwitch()) {
                RingServerSwitchPreparation.FirmwareUpdateActive -> NativeServerSwitchPreparation.FirmwareUpdateActive
                null, is RingServerSwitchPreparation.Ready -> {
                    serverSwitchStarted = true
                    NativeServerSwitchPreparation.Ready { clearForServerSwitch(prepared as? RingServerSwitchPreparation.Ready) }
                }
            }
        },
        select = { AndroidNativeOrigins.select(applicationContext, it) },
        restart = { target, invite -> restartApplication(applicationContext, target.origin, invite) },
    )

    /** Address forms call this only with a successfully probed server. */
    suspend fun switchServer(target: NativeServerCheckResult.Compatible, invite: AuthLink? = null): NativeServerSwitchResult {
        ready.first { it }
        return serverSwitch.switchServer(target, invite)
    }

    internal suspend fun hasCurrentServerData(): Boolean {
        val ringState = ring?.states?.value
        val whoopState = whoop.states.value
        // The home namespace remains authoritative when a default-server move reroutes HTTP.
        val home = core.sessions.origin
        return core.sessions.lease() != null ||
            KeystoreSessionSecretStore(applicationContext).exists(core.sessions.instance, home) ||
            journal.hasOriginData(core.sessions.instance, home) ||
            deletion.state.value.hasRecovery || deletion.state.value.status != null ||
            ringState?.selected != null || (ringState?.queueCount ?: 0) > 0 ||
            whoopState.selected != null || whoopState.queueCount > 0 || ambient.state.value.queuedBatches > 0 ||
            ring?.hasServerData() == true ||
            withContext(Dispatchers.IO) {
                WhoopStore(applicationContext, home).use { it.hasData() } ||
                    (backgroundLocation as? AndroidBackgroundRecording)?.collector?.hasServerData() == true ||
                File(applicationContext.noBackupFilesDir, "assistant-history").listFiles()?.isNotEmpty() == true
            }
    }
    val circle = CircleController(scope, core.sessions, core.http, core.submissions, core.drafts, clockMs)
    val sharing = SharingController(scope, core.sessions, core.http, core.submissions)
    val challenges = ChallengesController(scope, core.sessions, core.http, core.submissions)
    private val speechSink = speechAudioSink ?: AndroidSpeechAudioSink(context) { speech.interrupt() }
    val speech: SpeechPlaybackController = SpeechPlaybackController(
        scope, core.sessions, NativeSpeechPlaybackTransport(core.http, core.sessions), speechSink,
        isAudioAvailable = { !callAudioClaimed },
    )
    val speechSettings = SpeechSettingsController(scope, core.sessions, core.http, speech::invalidateConnection)
    val dictationPermission = DictationPermissionBridge()
    private val dictationSource = NativeDictationAudioGate(
        dictationAudioSource ?: AndroidDictationAudioSource(context, dictationPermission::requestResult),
        callOwnsMicrophone = { callAudioClaimed },
        claim = { ambient.setMicrophoneOwner("dictation", it) },
        stopPlayback = speech::stop,
    )
    init { liveSockets.observeNetwork(core.live::networkAvailable) }
    val dictation = DictationController(
        scope, NativeDictationReadiness(core.http, core.sessions), dictationSource, core.live,
        currentDraft = { DictationDraftSnapshot(assistant.state.value.draftText, assistant.draftRevision) },
        applyDraft = assistant::setDraft, telemetry = dictationTelemetry, nowMillis = SystemClock::elapsedRealtime,
        isAudioAvailable = { !callAudioClaimed },
        createSpeechDetector = { SileroDictationSpeechDetector(context) },
    )
    val callPermission = DictationPermissionBridge()
    val bluetoothPermission = DictationPermissionBridge()
    private val callMedia = callMediaFactory ?: AndroidCallMediaFactory(context, callPermission::request)
    private val callSignals = AndroidCallSignalFactory(origin, core.sessions,
        allowInsecureLoopback = BuildConfig.DEBUG, serverOrigin = { core.http.serverOrigin })
    val calls = CallsController(scope, core.sessions, core.http, callMedia, callSignals,
        onClaimAudio = {
            callAudioClaimed = true
            ambient.setMicrophoneOwner("call", true)
            dictation.interrupt(DictationProblem.AUDIO_FOCUS)
            speech.stop()
        },
        onReleaseAudio = {
            callAudioClaimed = false
            ambient.setMicrophoneOwner("call", false)
        },
        onKeepAwake = { mutableKeepCallScreenAwake.value = it },
        onCallFinished = { scope.launch { circle.refresh() } },
        clockMs = clockMs,
    )
    fun callAudioRouteNames() = (callMedia as? AndroidCallMediaFactory)?.audioRouteNames().orEmpty()
    val auth: AuthController = AuthController(scope, core.sessions, clockMs)
    val settings: SettingsController = SettingsController(scope, core.sessions, core.http, clockMs)
    val updatePlatform: AndroidUpdatePlatform = AndroidUpdatePlatform.get(context)
    val updates: NativeUpdateController = NativeUpdateController(
        scope, updateHttp, updatePlatform,
    )
    val deletion: AccountDeletionController = AccountDeletionController(
        core.sessions, core.http, KeystoreDeletionSecretStore(context), ::clearAccount,
    )

    init {
        scope.launch {
            // A missing foreground session preserves the separately authorized background grant.
            // Explicit logout/account deletion clears it through clearPrivateAccount below.
            core.sessions.leases.collect { lease ->
                ring?.setSession(lease?.let { RingSession(it.namespace.userId, it.credential, it.generation,
                    instance = it.namespace.instance, origin = it.namespace.origin, nativeDeviceId = core.sessions.identity.value?.deviceId) },
                    if (lease == null) "lapsed" else "switch", { lease == null || core.sessions.isCurrent(lease) })
            }
        }
        scope.launch {
            var previous: SessionLease? = null
            core.sessions.leases.collect { lease ->
                if (previous != null && previous !== lease) {
                    clearLocalRecording(previous?.namespace)
                }
                previous = lease
                if (lease != null) {
                    runCatching { backgroundLocation.status(lease.namespace) }
                    if (locationVisible) locationReporter.onVisible()
                }
            }
        }
        scope.launch { core.sessions.leases.collect { dictation.reset(); speech.reset(); fileExport.clear() } }
        scope.launch {
            photos.state.collect { attachment ->
                if (attachment.status == PhotoAttachmentStatus.Ready &&
                    attachment.namespace == core.sessions.lease()?.namespace) {
                    assistant.setDraftImage(attachment.imageId)
                }
            }
        }
        scope.launch { dictation.state.collect { if (it.active) speech.stop() } }
        scope.launch { core.events.changes.collect { event -> assistant.onChange(event) } }
        scope.launch {
            try {
                auth.restored(core.restore())
                if (core.sessions.lease() == null) clearLocalRecording()
                deletion.restore()
            } finally {
                ambient.bind(core.sessions, core.http)
                notificationDelivery.start()
                mutableReady.value = true
            }
            core.sessions.leases.collectLatest { deletion.restore() }
        }
    }

    private suspend fun clearAccount(namespace: AccountNamespace) {
        try { journal.clearAccount(namespace) } finally { clearPrivateAccount(namespace) }
    }

    private suspend fun clearPrivateAccount(namespace: AccountNamespace) {
        try {
            ring?.setSession(null, "logout")
        } finally {
            try { whoop.logout(namespace) } finally {
                try { clearLocalRecording(namespace) } finally {
                    try { historyCache.clear(namespace) } finally { ambient.clearAccount(namespace) }
                }
            }
        }
    }

    private suspend fun clearForServerSwitch(ring: RingServerSwitchPreparation.Ready?): List<suspend () -> Unit> {
        // Capture independent collector authority before null-lease observers erase its grant.
        val revokeLocation = (backgroundLocation as? AndroidBackgroundRecording)?.collector?.clearForServerSwitch()
        whoop.clearForServerSwitch()
        val session = core.clearForServerSwitch()
        notificationDelivery.clearForServerSwitch()
        setLocationForeground(false)
        ring?.clear()
        historyCache.clearForServerSwitch()
        ambient.clearForServerSwitch()
        KeystoreDeletionSecretStore(applicationContext).delete(core.sessions.instance, core.sessions.origin)
        dictation.reset()
        speech.reset()
        fileExport.clear()
        withContext(Dispatchers.IO) {
            val camera = File(applicationContext.cacheDir, "photo-camera")
            check(!camera.exists() || camera.deleteRecursively()) { "Camera cache could not be cleared" }
        }
        return buildList {
            session?.let { add { it.revoke(); Unit } }
            ring?.let { add(it::revoke) }
            revokeLocation?.let { add(it) }
            add { whoop.revokePending() }
        }
    }

    private suspend fun clearLocalRecording(namespace: AccountNamespace? = null) {
        (backgroundLocation as? AndroidBackgroundRecording)?.collector?.disableLocally(namespace)
            ?: backgroundLocation.disable()
    }

    override fun onCleared() {
        calls.close()
        recordings.close()
        callSignals.close()
        plugins.close()
        location.close()
        places.close()
        locationReporter.onHidden()
        devices.close()
        ringCollectors?.close()
        ring?.setForegroundVisible(false)
        display.close()
        circle.close()
        sharing.close()
        challenges.close()
        photos.close()
        images.close()
        taskImages.close()
        fileExport.close()
        speech.close()
        (speechSink as? AutoCloseable)?.close()
        dictation.close()
        (dictationSource as? AutoCloseable)?.close()
        core.live.close()
        liveSockets.close()
        // Cancel UI/reads before closing the database, then join the stream and its transport.
        scope.cancel()
        updateHttp.close()
        serverProbe.close()
        // A disappearing Activity cannot close the database midway through mandatory erasure.
        // The restart process releases the departing graph after select has committed.
        if (serverSwitchStarted) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { core.close() } finally { database.close() }
        }
    }
}
