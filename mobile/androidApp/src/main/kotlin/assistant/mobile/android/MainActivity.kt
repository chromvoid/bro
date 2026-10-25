package assistant.mobile.android

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Browser
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import assistant.mobile.core.ThemePreference
import assistant.mobile.core.AuthLink
import assistant.mobile.core.NativeAuthDestination
import assistant.mobile.core.NativeServerKind
import assistant.mobile.core.canonicalOrigin
import assistant.mobile.design.GeneratedColors
import assistant.mobile.design.NativeTheme
import assistant.mobile.android.notify.AndroidNotifications
import assistant.mobile.core.notifications.notificationDestination
import assistant.mobile.platform.ThemePreferences
import assistant.mobile.platform.AndroidNativeOrigins
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
    private lateinit var runtime: NativeRuntime
    private var restartingSelectedServer = false
    private var restartAuthLink: Pair<Uri, AuthLink?>? = null
    internal var firstScreenComposed = false
        private set
    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (::runtime.isInitialized) runtime.locationPermission.foregroundResult(grants.values.any { it })
    }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (::runtime.isInitialized) runtime.locationPermission.backgroundResult(granted)
    }
    private val backgroundSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (::runtime.isInitialized) runtime.locationPermission.backgroundResult(backgroundLocationGranted())
    }

    private fun backgroundLocationGranted(): Boolean = Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragment material is consumed once, before Compose or saved navigation can see it.
        val assistantUri = intent?.data
        val notificationOpen = AndroidNotifications.get(this).opened(intent)
        sanitizeIntent(intent)
        val incoming = PendingAuthLink.value.getAndSet(null)
        setTheme(R.style.Theme_Native)
        super.onCreate(savedInstanceState)
        if (selectedServerNeedsRestart(incoming)) return
        runtime = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == NativeRuntime::class.java)
                @Suppress("UNCHECKED_CAST")
                return NativeRuntimeFactory.create(applicationContext) as T
            }
        })[NativeRuntime::class.java]
        runtime.acceptNotificationOpen(notificationOpen)
        runtime.locationPermission.attach(this,
            foreground = { locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION)) },
            background = {
                when {
                    Build.VERSION.SDK_INT >= 30 -> backgroundSettings.launch(Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    Build.VERSION.SDK_INT == 29 -> backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    else -> runtime.locationPermission.backgroundResult(true)
                }
            },
            rationale = { shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) },
        )
        acceptAuthLink(incoming)
        acceptApplicationLink(assistantUri)
        val preferences = ThemePreferences(this)
        val initialTheme = ThemePreference.entries.find { it.name == preferences.read() } ?: ThemePreference.System
        val initialDark = when (initialTheme) {
            ThemePreference.System -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            ThemePreference.Light -> false
            ThemePreference.Dark -> true
        }
        val initialBar = systemBarStyle(initialDark)
        enableEdgeToEdge(statusBarStyle = initialBar, navigationBarStyle = initialBar)
        lifecycleScope.launch {
            // Wait for session restore so releasing the splash does not depend on a recomposition.
            runtime.ready.first { it }
            setContent {
                var theme by remember { mutableStateOf(initialTheme) }
                NativeTheme(
                    mode = theme,
                    onThemeApplied = { dark, _ ->
                        val bar = systemBarStyle(dark)
                        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
                    },
                ) {
                    val assistantNavigation by runtime.assistantNavigation.collectAsState()
                    val taskNavigation by runtime.taskNavigation.collectAsState()
                    val deviceNavigation by runtime.deviceNavigation.collectAsState()
                    val timerNavigation by runtime.timerNavigation.collectAsState()
                    val personalNavigation by runtime.personalNavigation.collectAsState()
                    val pluginNavigation by runtime.pluginNavigation.collectAsState()
                    val circleNavigation by runtime.circleNavigation.collectAsState()
                    val activityNavigation by runtime.activityNavigation.collectAsState()
                    val notificationNavigation by runtime.notificationNavigation.collectAsState()
                    val sectionNavigation by runtime.sectionNavigation.collectAsState()
                    val keepCallScreenAwake by runtime.keepCallScreenAwake.collectAsState()
                    DisposableEffect(keepCallScreenAwake) {
                        if (keepCallScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    }
                    val placesNavigation by runtime.placesNavigation.collectAsState()
                    val serverInvitation by runtime.serverInvitation.collectAsState()
                    NativeApp(
                        theme = theme,
                        onThemeChanged = { theme = it; preferences.write(it.name) },
                        auth = runtime.auth,
                        settings = runtime.settings,
                        deletion = runtime.deletion,
                        sessions = runtime.core.sessions,
                        assistant = runtime.assistant,
                        today = runtime.today,
                        food = runtime.food,
                        foodStats = runtime.foodStats,
                        tasks = runtime.tasks,
                        timers = runtime.timers,
                        notifications = runtime.notifications,
                        notificationDelivery = runtime.notificationDelivery,
                        notificationPlatform = runtime.notificationPlatform,
                        taskAssistant = runtime.taskAssistant,
                        taskImages = runtime.taskImages,
                        devices = runtime.devices,
                        display = runtime.display,
                        whoop = runtime.whoop,
                        personal = runtime.personal,
                        personalHistory = runtime.personalHistory,
                        personalSources = runtime.personalSources,
                        personalDdx = runtime.personalDdx,
                        bodyPresentation = runtime.bodyPresentation,
                        bodyAssets = runtime.bodyAssets,
                        plugins = runtime.plugins,
                        circle = runtime.circle,
                        sharing = runtime.sharing,
                        challenges = runtime.challenges,
                        activity = runtime.activity,
                        calls = runtime.calls,
                        callPermission = runtime.callPermission,
                        bluetoothPermission = runtime.bluetoothPermission,
                        callAudioRouteNames = runtime::callAudioRouteNames,
                        location = runtime.location,
                        places = runtime.places,
                        ring = runtime.ring,
                        ringCollectors = runtime.ringCollectors,
                        attention = runtime.attention,
                        dictation = runtime.dictation,
                        speech = runtime.speech,
                        speechSettings = runtime.speechSettings,
                        ambient = runtime.ambient,
                        recordings = runtime.recordings,
                        sectionNavigation = sectionNavigation,
                        onSectionNavigationHandled = runtime::sectionNavigationHandled,
                        dictationPermission = runtime.dictationPermission,
                        photos = runtime.photos,
                        images = runtime.images,
                        photoNormalizer = runtime.photoNormalizer,
                        photoAcquisition = runtime.photoAcquisition,
                        fileExport = runtime.fileExport,
                        onOpenManagement = ::openManagement,
                        onAssistantAccess = ::openAssistantAccess,
                        appVersion = BuildConfig.VERSION_NAME,
                        updates = runtime.updates,
                        onInstallPermission = { runtime.updatePlatform.requestInstallPermission(this@MainActivity) },
                        assistantNavigation = assistantNavigation,
                        onAssistantNavigationHandled = runtime::assistantNavigationHandled,
                        taskNavigation = taskNavigation,
                        onTaskNavigationHandled = runtime::taskNavigationHandled,
                        deviceNavigation = deviceNavigation,
                        onDeviceNavigationHandled = runtime::deviceNavigationHandled,
                        timerNavigation = timerNavigation,
                        onTimerNavigationHandled = runtime::timerNavigationHandled,
                        personalNavigation = personalNavigation,
                        onPersonalNavigationHandled = runtime::personalNavigationHandled,
                        pluginNavigation = pluginNavigation,
                        onPluginNavigationHandled = runtime::pluginNavigationHandled,
                        circleNavigation = circleNavigation,
                        onCircleNavigationHandled = runtime::circleNavigationHandled,
                        activityNavigation = activityNavigation,
                        onActivityNavigationHandled = runtime::activityNavigationHandled,
                        notificationNavigation = notificationNavigation,
                        onNotificationNavigationHandled = runtime::notificationNavigationHandled,
                        placesNavigation = placesNavigation,
                        onPlacesNavigationHandled = runtime::placesNavigationHandled,
                        serverName = runtime.selectedServerName,
                        serverOrigin = runtime.selectedServerOrigin,
                        defaultServerOrigin = runtime.defaultServerOrigin,
                        checkServer = runtime.serverProbe::check,
                        switchServer = runtime::switchServer,
                        hasServerData = runtime::hasCurrentServerData,
                        serverInvitation = serverInvitation,
                        onServerInvitationHandled = runtime::serverInvitationHandled,
                    )
                    SideEffect { firstScreenComposed = true }
                }
            }
        }
        val content = findViewById<View>(android.R.id.content)
        // Keep the starting window until Compose has applied the first real screen.
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!firstScreenComposed) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (selectedServerNeedsRestart() || !::runtime.isInitialized) return
        NativeOrigins.checkPending(this)
        if (!BuildConfig.PLAY_STORE) {
            runtime.updatePlatform.visible(this)
            runtime.updates.resumeInstall()
        }
        runtime.core.setForeground(true)
        runtime.notificationDelivery.refreshState()
        runtime.calls.activate()
        runtime.setLocationForeground(true)
    }

    override fun onResume() {
        super.onResume()
        if (restartingSelectedServer) {
            // This entry is now foreground, so Android can admit the trampoline activity.
            NativeServerRestart.resumeSelectedServer(this, restartAuthLink) { context, origin, invite ->
                NativeServerRestart.restart(context, origin, invite, javaClass)
            }
            restartAuthLink = null
        }
    }

    override fun onStop() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::runtime.isInitialized) {
            if (!isChangingConfigurations) runtime.calls.deactivate()
            runtime.core.setForeground(false)
            runtime.setLocationForeground(false)
            runtime.ring?.setForegroundVisible(false)
            runtime.updatePlatform.visible(null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::runtime.isInitialized) runtime.locationPermission.detach(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        val assistantUri = intent.data
        val notificationOpen = AndroidNotifications.get(this).opened(intent)
        sanitizeIntent(intent)
        val incoming = PendingAuthLink.value.getAndSet(null)
        super.onNewIntent(intent)
        setIntent(intent)
        if (selectedServerNeedsRestart(incoming) || !::runtime.isInitialized) return
        runtime.acceptNotificationOpen(notificationOpen)
        acceptAuthLink(incoming)
        acceptApplicationLink(assistantUri)
    }

    private fun selectedServerNeedsRestart(incoming: Pair<Uri, AuthLink?>? = null): Boolean {
        restartingSelectedServer = AndroidNativeOrigins.pendingRestartOrigin(this) != null
        if (restartingSelectedServer && incoming != null) {
            restartAuthLink = incoming.first.buildUpon().path("/login").build() to incoming.second
        }
        return restartingSelectedServer
    }

    private fun sanitizeIntent(source: Intent?) {
        if (source == null) return
        source.data = null
        source.clipData = null
        source.selector = null
        // No token reaches Activity.intent, saved state, navigation keys, logs or browser URLs.
    }

    private fun acceptAuthLink(incoming: Pair<Uri, AuthLink?>?) {
        if (incoming == null) return
        val (uri, link) = incoming
        if (link == null || uri.userInfo != null || uri.query != null || uri.fragment != null) return
        val origin = runCatching {
            canonicalOrigin("${uri.scheme}://${uri.encodedAuthority}", allowInsecureLoopback = BuildConfig.DEBUG)
        }.getOrNull() ?: return
        if (uri.encodedPath !in setOf("/login", "/join")) return
        if (acceptsOrigin(origin)) {
            runtime.openServerInvitation(NativeAuthDestination(runtime.selectedServerOrigin, link))
        } else if (uri.encodedPath == "/join" || origin == runtime.defaultServerOrigin) {
            runtime.openServerInvitation(NativeAuthDestination(origin, link))
        }
    }

    private fun acceptApplicationLink(uri: Uri?) {
        if (browserFamilyLink(uri, runtime.defaultServerOrigin,
                NativeOrigins.get(this).initialServerSelection?.kind == NativeServerKind.CUSTOM)) {
            val browser = CustomTabsIntent.Builder().build().intent
            browser.setPackage(requireNotNull(CustomTabsClient.getPackageName(this, null)) {
                "No browser supports Custom Tabs"
            })
            browser.removeExtra(Browser.EXTRA_HEADERS)
            browser.data = uri
            NativeRuntimeFactory.openBrowser(this, browser)
            return
        }
        acceptFamilyLink(uri)
        acceptTaskLink(uri)
        acceptPluginLink(uri)
    }

    private fun acceptFamilyLink(uri: Uri?) {
        if (uri == null || uri.userInfo != null || uri.fragment != null) return
        val origin = runCatching {
            canonicalOrigin("${uri.scheme}://${uri.encodedAuthority}", allowInsecureLoopback = BuildConfig.DEBUG)
        }.getOrNull()
        if (!acceptsOrigin(origin)) return
        val parts = uri.pathSegments
        if (parts == listOf("circle", "shared") || parts == listOf("settings", "shared")) {
            if (uri.queryParameterNames.any { it !in setOf("id", "day") } ||
                uri.queryParameterNames.any { uri.getQueryParameters(it).size != 1 }) return
            sharedProjectionDestination(uri.getQueryParameter("id"), uri.getQueryParameter("day"))?.let(runtime::openCircle)
            return
        }
        if (uri.query != null) return
        fun personalId(value: String) = value.isNotBlank() && value.length <= 256 &&
            value !in setOf(".", "..") && value.none { it.isISOControl() || it in "/?#\\" }
        circleDestination(parts)?.let { runtime.openCircle(it); return }
        when {
            uri.encodedPath == "/places" -> runtime.openPlaces()
            uri.encodedPath == "/settings/location" -> runtime.openLocationSettings()
            parts.size == 2 && parts[0] == "places" && validPlaceDate(parts[1]) -> runtime.openPlaces(parts[1])
            uri.encodedPath == "/personal" -> runtime.openPersonal(FamilyScreen.Personal)
            uri.encodedPath == "/personal/profile" -> runtime.openPersonal(FamilyScreen.Profile)
            uri.encodedPath == "/personal/goals" -> runtime.openPersonal(FamilyScreen.Goals)
            uri.encodedPath == "/personal/history" -> runtime.openPersonal(FamilyScreen.PersonalHistory)
            uri.encodedPath == "/personal/sources" -> runtime.openPersonal(FamilyScreen.Sources)
            parts.size in 3..4 && parts[0] == "personal" && parts[1] == "sources" &&
                parts.drop(2).all(::personalId) -> runtime.openPersonal(FamilyScreen.Sources, parts[2], parts.getOrNull(3))
            uri.encodedPath == "/settings/speech" -> runtime.openSection(FamilyScreen.SpeechSettings)
            uri.encodedPath == "/recordings" -> runtime.openSection(FamilyScreen.Recordings)
            uri.encodedPath == "/settings/devices" -> runtime.openDevice(null)
            parts.size == 3 && parts[0] == "settings" && parts[1] == "devices" -> {
                val id = parts[2]
                if (id.isNotBlank() && id.length <= 128 && id !in setOf(".", "..") &&
                    id.none { it.isISOControl() || it in "/?#\\" }) {
                    runtime.openDevice(id)
                }
            }
            uri.encodedPath == "/assistant" -> runtime.openAssistant(null)
            parts.size == 3 && parts[0] == "assistant" && parts[1] == "m" -> {
                val id = parts[2]
                if (id.isNotBlank() && id.length <= 128 && id.none { it.isISOControl() || it in "/?#" }) {
                    runtime.openAssistant(id)
                }
            }
        }
    }

    private fun validPlaceDate(value: String): Boolean = runCatching {
        java.time.LocalDate.parse(value).toString() == value
    }.getOrDefault(false)

    private fun acceptTaskLink(uri: Uri?) {
        if (uri == null || uri.userInfo != null || uri.query != null || uri.fragment != null) return
        val origin = runCatching {
            canonicalOrigin("${uri.scheme}://${uri.encodedAuthority}", allowInsecureLoopback = BuildConfig.DEBUG)
        }.getOrNull()
        if (!acceptsOrigin(origin)) return
        val parts = uri.pathSegments
        fun validId(id: String) = id.isNotBlank() && id.length <= 128 &&
            id.none { it.isISOControl() || it in "/?#" }
        when {
            uri.encodedPath == "/activity" -> runtime.openActivity()
            parts.size == 2 && parts[0] == "activity" && validId(parts[1]) &&
                parts[1] !in setOf(".", "..") -> runtime.openActivity(parts[1])
            uri.encodedPath == "/timers" -> runtime.openTimers()
            uri.encodedPath == "/notifications" || uri.encodedPath == "/settings/notifications" ||
                parts.size == 2 && parts[0] in setOf("timers", "reminders") ->
                notificationDestination(uri.encodedPath)?.let(runtime::openNotification)
            uri.encodedPath == "/tasks" -> runtime.openTasks()
            parts.size == 2 && parts[0] == "tasks" && validId(parts[1]) -> runtime.openTasks(taskId = parts[1])
            parts.size == 3 && parts[0] == "circle" && parts[1] == "lists" && validId(parts[2]) ->
                runtime.openTasks(listId = parts[2])
        }
    }

    private fun acceptPluginLink(uri: Uri?) {
        if (uri == null || uri.userInfo != null || uri.fragment != null) return
        val origin = runCatching {
            canonicalOrigin("${uri.scheme}://${uri.encodedAuthority}", allowInsecureLoopback = BuildConfig.DEBUG)
        }.getOrNull()
        if (!acceptsOrigin(origin)) return
        val parts = uri.pathSegments
        if (parts.size !in 2..3 || parts[0] != "plugins") return
        fun safePart(value: String) = value.isNotBlank() && value.length <= 200 &&
            value !in setOf(".", "..") && value.none { it.isISOControl() || it in "/?#\\" }
        if (!safePart(parts[1]) || parts.getOrNull(2)?.let { !safePart(it) } == true) return
        if (uri.queryParameterNames.any { it != "coin" } || uri.getQueryParameters("coin").size > 1) return
        val coin = uri.getQueryParameter("coin")
        if (coin != null && !safePart(coin)) return
        runtime.openPlugin(parts[1], parts.getOrNull(2) ?: "home", coin)
    }

    private fun openManagement() = openManagementPath("")

    private fun acceptsOrigin(origin: String?): Boolean =
        origin != null && (origin == runtime.core.http.serverOrigin || NativeOrigins.get(this).acceptsOrigin(origin))

    private fun openAssistantAccess(action: String) {
        val path = when (action) {
            "configure_access" -> "/admin/users"
            "open_accounts" -> "/accounts"
            else -> return
        }
        openManagementPath(path)
    }

    private fun openManagementPath(path: String) {
        if (runtime.core.sessions.identity.value?.systemAdmin != true) return
        val origin = runtime.core.http.serverOrigin
        if (!origin.startsWith("https://") && !BuildConfig.DEBUG) return
        // A fresh Custom Tabs intent contains browser UI extras only: no session or headers.
        val browser = CustomTabsIntent.Builder().build().intent
        browser.removeExtra(Browser.EXTRA_HEADERS)
        browser.data = Uri.parse(origin + path)
        NativeRuntimeFactory.openBrowser(this, browser)
    }

    private fun systemBarStyle(dark: Boolean): SystemBarStyle = SystemBarStyle.auto(
        // API 26 needs a dark navigation scrim because dark navigation icons
        // arrive in API 27; Activity chooses the supported generated scrim.
        GeneratedColors.light.getValue("background").toArgb(),
        GeneratedColors.dark.getValue("background").toArgb(),
    ) { dark }
}

internal fun browserFamilyLink(uri: Uri?, defaultOrigin: String, customSelected: Boolean): Boolean {
    if (!customSelected || uri == null || uri.userInfo != null || uri.fragment != null) return false
    val origin = runCatching {
        canonicalOrigin("${uri.scheme}://${uri.encodedAuthority}", allowInsecureLoopback = BuildConfig.DEBUG)
    }.getOrNull()
    if (origin != defaultOrigin) return false
    val path = uri.encodedPath.orEmpty()
    return path in setOf("/assistant", "/tasks", "/timers", "/notifications", "/settings/notifications",
        "/personal", "/activity", "/places", "/settings/location", "/community", "/circle",
        "/circle/friends", "/circle/shared", "/settings/sharing", "/settings/access", "/settings/shared",
        "/settings/speech", "/recordings", "/settings/devices") ||
        listOf("/assistant/m/", "/tasks/", "/timers/", "/reminders/", "/personal/", "/activity/",
            "/places/", "/circle/lists/", "/circle/challenges/", "/circle/chat/", "/circle/spaces/",
            "/settings/devices/", "/plugins/").any(path::startsWith)
}
