package assistant.mobile.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.DeletionStatus
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeIdentity
import assistant.mobile.core.NativeJson
import assistant.mobile.core.NativeUpdateHttpClient
import assistant.mobile.core.StoredAccountDeletion
import assistant.mobile.core.StoredSession
import assistant.mobile.core.dictation.DictationProblem
import assistant.mobile.core.dictation.DictationPreparationResult
import assistant.mobile.design.localizedString
import assistant.mobile.platform.KeystoreSessionSecretStore
import assistant.mobile.platform.KeystoreDeletionSecretStore
import assistant.mobile.platform.NativeCorePlatform
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** CI emulator only: a synthetic HTTP/SSE fixture and the real client/platform stores. */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val testName = TestName()

    private lateinit var server: NativeLoopbackServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var scenarioOwned = false
    private lateinit var runtime: NativeRuntime
    private val dictationAudio = SyntheticDictationAudioSource()
    private var fixtureDiagnostics: NativeFixtureDiagnostics? = null

    private val activity: MainActivity
        get() {
            lateinit var result: MainActivity
            onActivity { result = it }
            return result
        }

    @Before
    fun launchIsolatedClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fixtureDiagnostics = NativeFixtureDiagnostics(context.applicationContext as Application, "NativeSmokeTest.${testName.methodName}")
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        server = NativeLoopbackServer()
        val instance = "smoke-${UUID.randomUUID()}"
        NativeRuntimeFactory.testFactory = { runtimeContext ->
            NativeRuntime(runtimeContext, origin = server.origin, instance = instance,
                updateHttp = NativeUpdateHttpClient(NativeCorePlatform.httpEngine(), server.origin,
                    allowInsecureLoopback = true),
                dictationAudioSource = dictationAudio,
            ).also { runtime = it }
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        scenarioOwned = true
        waitForScreen("Login")
    }

    @Test
    fun assistantDictationPermissionDenialNeverStartsCapture() {
        dictationAudio.preparation = DictationPreparationResult.Unavailable(DictationProblem.PERMISSION_DENIED)
        login()
        open(FamilyScreen.Assistant)
        startAssistantDictation()
        compose.waitUntil(10_000) { runtime.dictation.state.value.phase.name == "FAILED" }
        compose.onNodeWithTag("assistant_dictation_status").assertIsDisplayed()
        assertEquals(1, dictationAudio.preparations.get())
        assertEquals(0, dictationAudio.starts.get())
        assertTrue(dictationAudio.releases.get() > 0)
        assertEquals(1, server.requests.count { it.path == "/v1/me/speech/readiness?capability=asr" })
        assertTrue(server.failures.isEmpty())
    }

    @After
    fun releaseIsolatedClient() {
        val screenTags = runCatching {
            listOf("screen_Today", "screen_Login", "screen_SigningOut", "screen_Assistant", "screen_Settings", "screen_Food").filter { tag ->
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
            }
        }.getOrElse { listOf("unavailable:${it.javaClass.simpleName}") }
        fixtureDiagnostics?.finish(if (::runtime.isInitialized) runtime else null,
            if (::server.isInitialized) server.requests.toList() else emptyList(),
            if (::server.isInitialized) server.failures.size else 0,
            if (::server.isInitialized) server.responses.toList() else emptyList(), screenTags)
        fixtureDiagnostics = null
        try {
            if (scenarioOwned && ::runtime.isInitialized) runBlocking {
                if (runtime.core.sessions.lease() != null) runtime.core.sessions.logout()
            }
        } finally {
            try {
                if (scenarioOwned) closeScenario()
            } finally {
                NativeRuntimeFactory.testFactory = null
                if (::server.isInitialized) server.close()
                InstrumentationRegistry.getInstrumentation().targetContext
                    .getSharedPreferences("appearance", 0).edit().clear().commit()
            }
        }
    }

    @Test
    fun loginSettingsLogoutAndAccountReplacement() {
        login()
        compose.waitUntil(10_000) { server.activeStreams.get() == 1 }
        open(FamilyScreen.Assistant)
        compose.onNodeWithTag("draft").assertIsDisplayed().performTextInput(DRAFT)
        awaitSavedDraft(DRAFT)
        compose.waitUntil(10_000) { imeVisible() }
        systemBack()
        compose.waitUntil(10_000) { !imeVisible() }
        systemBack()
        open(FamilyScreen.Settings)
        compose.onNodeWithTag("settings_management").assertDoesNotExist()
        compose.onNodeWithTag("settings_name").performScrollTo().performTextReplacement("New Person")
        compose.onNodeWithTag("settings_locale").performScrollTo().performTextReplacement("en")
        compose.onNodeWithTag("settings_timezone").performScrollTo().performTextReplacement("UTC")
        compose.onNodeWithTag("settings_save").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runtime.core.sessions.identity.value?.let { it.name == "New Person" && it.locale == "en" && it.timezone == "UTC" } == true
        }
        val update = server.requests.single { it.method == "POST" && it.path == "/v1/me" }
        assertEquals(setOf("name", "locale", "timezone"), update.json().keys().asSequence().toSet())
        assertFalse(update.body.contains(PASSWORD))
        compose.onNodeWithTag("settings_logout").performScrollTo().performClick()
        waitForScreen("Login")
        compose.waitUntil(10_000) { server.activeStreams.get() == 0 }
        login("other")
        open(FamilyScreen.Assistant)
        assertEquals("", compose.onNodeWithTag("draft").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        systemBack()
        open(FamilyScreen.Settings)
        compose.onNodeWithTag("settings_name").assertTextContains("Other Person")
        assertFalse(compose.onNodeWithTag("settings_name").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text.contains("New Person"))
        assertTrue(server.requests.any { it.path == "/v1/auth/native/logout" })
        compose.onNodeWithTag("settings_logout").performScrollTo().performClick()
        waitForScreen("Login")
        login()
        open(FamilyScreen.Assistant)
        assertEquals("", compose.onNodeWithTag("draft").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun expiryReturnsToLoginAndKeepsJournalDraft() {
        login()
        open(FamilyScreen.Assistant)
        compose.onNodeWithTag("draft").assertIsDisplayed().performTextInput(DRAFT)
        awaitSavedDraft(DRAFT)
        server.refreshExpiresAt = System.currentTimeMillis() + 750
        runBlocking { assertTrue(runtime.core.sessions.refreshIdentity() is HttpOutcome.Success) }
        waitForScreen("Login")
        compose.waitUntil(10_000) { server.activeStreams.get() == 0 }
        login()
        open(FamilyScreen.Assistant)
        compose.onNodeWithTag("draft").assertTextContains(DRAFT)
        awaitSavedDraft(DRAFT)
    }

    @Test
    fun deletionReceiptSurvivesSessionRevocationAndFreshKeystoreAdapter() {
        login()
        val namespace = requireNotNull(runtime.core.sessions.lease()).namespace
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        open(FamilyScreen.Settings)
        compose.onNodeWithTag("settings_deletion").performScrollTo().performClick()
        waitForScreen("AccountDeletion")
        awaitDeletion("load options") { runtime.deletion.state.value.options != null && !runtime.deletion.state.value.busy }
        // Controller completion precedes the lifecycle-collected UI state. Wait for the field
        // itself before typing, then dismiss the IME before scrolling to a touch target.
        awaitDeletion("password field enabled") {
            !compose.onNodeWithTag("deletion_password").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("deletion_password").performScrollTo().performTextInput(PASSWORD)
        compose.waitUntil(10_000) { imeVisible() }
        systemBack()
        compose.waitUntil(10_000) { !imeVisible() }
        awaitDeletion("submit enabled") {
            !compose.onNodeWithTag("deletion_submit").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag("deletion_submit").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        awaitDeletion("admission") {
            runtime.deletion.state.value.status == DeletionStatus.PENDING &&
                !runtime.deletion.state.value.busy && runtime.core.sessions.lease() == null
        }
        compose.onNodeWithTag("deletion_status").assertTextContains(activity.localizedString(GeneratedStrings.AccountDeletion.pending))
        compose.waitUntil(10_000) { server.activeStreams.get() == 0 }
        val admission = server.requests.single { it.method == "DELETE" && it.path == "/v1/me" }
        assertEquals(PASSWORD, admission.json().getString("password"))
        assertEquals(0, admission.json().getJSONObject("space_successors").length())
        val key = requireNotNull(admission.headers["idempotency-key"])
        assertEquals(key, UUID.fromString(key).toString())
        runBlocking {
            assertNull(KeystoreSessionSecretStore(context).load(namespace.instance, namespace.origin))
            val reopened = requireNotNull(KeystoreDeletionSecretStore(context).load(namespace.instance, namespace.origin))
            assertEquals(namespace, reopened.namespace)
            assertNull(reopened.pending)
            assertEquals(NativeLoopbackServer.DELETION_RECEIPT, reopened.receipt?.receiptToken)
            assertFalse(NativeJson.encodeToString(StoredAccountDeletion.serializer(), reopened).contains(PASSWORD))
        }
        assertPrivateFilesContainNoPlaintext(PASSWORD, NativeLoopbackServer.DELETION_RECEIPT)

        val firstRuntime = runtime
        closeScenario()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        scenarioOwned = true
        waitForScreen("AccountDeletion")
        assertNotSame(firstRuntime, runtime)
        assertNull(runtime.core.sessions.lease())
        awaitDeletion("receipt restore") { runtime.deletion.state.value.status == DeletionStatus.PENDING && !runtime.deletion.state.value.busy }
        compose.onNodeWithTag("deletion_status").assertTextContains(activity.localizedString(GeneratedStrings.AccountDeletion.pending))
        compose.onNodeWithTag("deletion_refresh").performScrollTo()
        val statusReadsBeforeRefresh = server.requests.count { it.path == "/v1/account-deletions/${NativeLoopbackServer.DELETION_ID}" }
        server.deletionCompleted = true
        compose.onNodeWithTag("deletion_refresh").performClick()
        awaitDeletion("status refresh") { runtime.deletion.state.value.status == DeletionStatus.COMPLETED }
        compose.onNodeWithTag("deletion_status").assertTextContains(activity.localizedString(GeneratedStrings.AccountDeletion.completed))
        val statusReads = server.requests.filter { it.path == "/v1/account-deletions/${NativeLoopbackServer.DELETION_ID}" }
        assertTrue(statusReads.size > statusReadsBeforeRefresh)
        statusReads.forEach { request ->
            assertEquals("Bearer ${NativeLoopbackServer.DELETION_RECEIPT}", request.headers["authorization"])
            assertFalse(request.headers.containsKey("x-expected-user"))
            assertFalse(request.body.contains(PASSWORD))
        }
        assertEquals(1, server.requests.count { it.path == "/v1/auth/native/login" })
        assertEquals(1, server.requests.count { it.method == "DELETE" && it.path == "/v1/me" })
        runBlocking { assertNull(KeystoreDeletionSecretStore(context).load(namespace.instance, namespace.origin)) }
        compose.onNodeWithTag("deletion_finished").performScrollTo().performClick()
        waitForScreen("Login")
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun firstActivityDrawWaitsForComposedScreenWithAndWithoutStoredSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        closeScenario()
        FirstDrawObserver(context.applicationContext as Application).use { draws ->
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            scenarioOwned = true
            waitForScreen("Login")
            assertNull(runtime.core.sessions.lease())
            val loginActivity = activity
            compose.waitUntil(10_000) { draws.firstDrawComposed(loginActivity) != null }
            assertEquals("The first login draw must contain the composed screen", true, draws.firstDrawComposed(loginActivity))

            login()
            val previousRuntime = runtime
            val storedNamespace = requireNotNull(runtime.core.sessions.lease()).namespace
            closeScenario()
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            scenarioOwned = true
            waitForScreen("Today")
            assertNotSame(previousRuntime, runtime)
            assertEquals(storedNamespace, runtime.core.sessions.lease()?.namespace)
            val signedInActivity = activity
            assertNotSame(loginActivity, signedInActivity)
            compose.waitUntil(10_000) { draws.firstDrawComposed(signedInActivity) != null }
            assertEquals("The first signed-in draw must contain the composed screen", true, draws.firstDrawComposed(signedInActivity))
        }
    }

    @Test
    fun launchAndNavigateWithSystemBack() {
        server.circleEnabled = true
        server.todayEnabled = true
        server.activityEnabled = true
        server.placesEnabled = true
        login()
        assertEquals("com.chromvoid.bro", activity.packageName)
        assertEquals("нет", activity.localizedString(GeneratedStrings.Personal.SleepNight.awakeningCount(0.0, "0", "5 мин")))
        assertEquals("21 раз · 5 мин", activity.localizedString(GeneratedStrings.Personal.SleepNight.awakeningCount(21.0, "21", "5 мин")))
        assertEquals("Маша (@masha) увидит Питание:", activity.localizedString(GeneratedStrings.Access.willSee("Маша", "masha", "Питание")))
        listOf(
            FamilyScreen.Assistant, FamilyScreen.Food, FamilyScreen.Tasks, FamilyScreen.Timers,
            FamilyScreen.Circle, FamilyScreen.Personal, FamilyScreen.Activity, FamilyScreen.Places,
            FamilyScreen.Recordings, FamilyScreen.Notifications, FamilyScreen.Settings,
        ).forEach { section ->
            open(section)
            compose.onNodeWithTag("screen_${section.name}").assertIsDisplayed()
            if (section == FamilyScreen.Personal) {
                listOf("profile" to "PersonalProfile", "goals" to "PersonalGoals").forEach { (entry, screen) ->
                    compose.onNodeWithTag("personal_more").performClick()
                    compose.onNodeWithTag("personal_open_$entry").performScrollTo().performClick()
                    waitForScreen(screen)
                    systemBack()
                    compose.onNodeWithTag("screen_Personal").assertIsDisplayed()
                }
            } else if (section == FamilyScreen.Circle) {
                awaitTag("circle_friends")
                compose.onNodeWithTag("circle_friends").performScrollTo().performClick()
                waitForScreen("CircleFriends")
                systemBack()
                compose.onNodeWithTag("screen_${section.name}").assertIsDisplayed()
            } else {
                section.children().forEach { child ->
                    open(child)
                    compose.onNodeWithTag("screen_${child.name}").assertIsDisplayed()
                    systemBack()
                    compose.onNodeWithTag("screen_${section.name}").assertIsDisplayed()
                }
            }
            systemBack()
            compose.onNodeWithTag("screen_Today").assertIsDisplayed()
        }
    }

    @Test
    fun keystoreSessionSurvivesAdapterReopenAndErasesOnlyItsOrigin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origin = "https://synthetic.invalid"
        val otherOrigin = "https://other-synthetic.invalid"
        val identity = NativeIdentity("synthetic-user", "person", "Person", "ru", "Europe/Moscow", false, 1,
            "synthetic-device", "2099-11-15T12:00:00Z", listOf("session.control"))
        val record = StoredSession(AccountNamespace("synthetic-installation", origin, identity.id),
            "synthetic-native-credential-never-valid", identity, identity.deviceId, "synthetic-session", identity.sessionExpiresAt)
        val store = KeystoreSessionSecretStore(context)
        try {
            store.save(record)
            store.save(record.copy(namespace = record.namespace.copy(origin = otherOrigin)))
            assertEquals(record, KeystoreSessionSecretStore(context).load(record.namespace.instance, origin))
            val files = java.io.File(context.noBackupFilesDir, "native-sessions").listFiles().orEmpty()
            assertFalse(files.isEmpty())
            assertFalse(files.any { it.readBytes().toString(Charsets.ISO_8859_1).contains(record.credential) })
            store.delete(record.namespace.instance, origin)
            assertNull(store.load(record.namespace.instance, origin))
            assertEquals(otherOrigin, store.load(record.namespace.instance, otherOrigin)?.namespace?.origin)
        } finally {
            store.delete(record.namespace.instance, origin)
            store.delete(record.namespace.instance, otherOrigin)
        }
    }

    private fun login(login: String = "person") {
        compose.loginNativeClient(login, PASSWORD, ::imeClosed)
    }

    private fun startAssistantDictation() {
        if (runtime.assistant.state.value.draftText.isBlank()) {
            compose.onNodeWithTag("assistant_dictation_start").assertIsDisplayed().performClick()
        } else {
            if (imeVisible()) {
                systemBack()
                compose.waitUntil(10_000) { imeClosed() }
            }
            systemBack()
            waitForScreen("Today")
            compose.awaitNativeTabBar(::imeClosed)
            compose.onNodeWithTag("voice_tab").assertIsDisplayed().performClick()
            waitForScreen("Assistant")
        }
    }

    private fun awaitTag(tag: String, useUnmergedTree: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = useUnmergedTree).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FirstDrawObserver(private val application: Application) : Application.ActivityLifecycleCallbacks, AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val draws = CopyOnWriteArrayList<FirstDraw>()

        init {
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(this) }
        }

        fun firstDrawComposed(activity: MainActivity): Boolean? =
            draws.singleOrNull { it.activity === activity }?.composed?.get()

        override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity) draws += FirstDraw(activity)
        }

        override fun close() {
            instrumentation.runOnMainSync {
                application.unregisterActivityLifecycleCallbacks(this)
                draws.forEach { it.close() }
                draws.clear()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        private class FirstDraw(val activity: MainActivity) {
            val composed = AtomicReference<Boolean?>()
            private val decor: View = activity.window.decorView
            private val listener = ViewTreeObserver.OnDrawListener {
                // Listener removal belongs outside draw dispatch, in the observer's close().
                composed.compareAndSet(null, activity.firstScreenComposed)
            }

            init {
                decor.viewTreeObserver.addOnDrawListener(listener)
            }

            fun close() {
                val tree = decor.viewTreeObserver
                if (tree.isAlive) tree.removeOnDrawListener(listener)
            }
        }
    }

    private fun closeScenario() {
        check(scenarioOwned) { "No ActivityScenario is owned by this test" }
        scenario.close()
        scenarioOwned = false
    }

    private fun awaitDeletion(stage: String, expected: () -> Boolean) {
        fun diagnostic(): String {
            val state = runtime.deletion.state.value
            val lease = runtime.core.sessions.lease()
            val routes = server.requests.map { "${it.method} ${it.path.substringBefore('?')}" }
            val protectedState = runCatching {
                runBlocking {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val record = KeystoreDeletionSecretStore(context).load(runtime.core.sessions.instance, runtime.core.sessions.origin)
                    "pending=${record?.pending != null}, receipt=${record?.receipt != null}, status=${record?.receipt?.status}"
                }
            }.getOrElse { "storeFailure=${it.javaClass.simpleName}" }
            return "Deletion $stage: status=${state.status}, busy=${state.busy}, options=${state.options != null}, " +
                "recovery=${state.hasRecovery}, failure=${state.failure}, session=${lease != null}, " +
                "namespaceMatchesLease=${state.namespace == lease?.namespace}; protected=[$protectedState]; " +
                "requests=$routes; responses=${server.responses}; fixtureFailures=${server.failures}"
        }
        try {
            compose.waitUntil(10_000) {
                if (server.failures.isNotEmpty() || runtime.deletion.state.value.failure != null)
                    throw AssertionError(diagnostic())
                expected()
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(diagnostic(), timeout)
        }
    }

    private fun awaitSavedDraft(text: String) {
        compose.waitUntil(10_000) {
            val lease = runtime.core.sessions.lease()
            lease != null && runBlocking {
                runtime.core.drafts.read(lease, ASSISTANT_DRAFT_TARGET)?.json?.decodeToString()?.contains(text) == true
            }
        }
    }

    private fun waitForScreen(name: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("screen_$name")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen_$name").assertIsDisplayed()
    }

    private fun open(screen: FamilyScreen) {
        val tag = when (screen) {
            FamilyScreen.Today -> "tab_Today"
            FamilyScreen.Food -> "tab_Food"
            FamilyScreen.Tasks -> "tab_Tasks"
            FamilyScreen.Assistant -> "tab_Assistant"
            FamilyScreen.Notifications -> "notifications_bell"
            FamilyScreen.Timers, FamilyScreen.Recordings, FamilyScreen.Circle, FamilyScreen.Activity,
            FamilyScreen.Personal, FamilyScreen.Places, FamilyScreen.Settings -> {
                awaitTag("profile_menu")
                compose.onNodeWithTag("profile_menu").performClick()
                "open_${screen.name}"
            }
            else -> "open_${screen.name}"
        }
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollToIfScrollable().performClick()
        waitForScreen(screen.name)
        if (screen == FamilyScreen.Assistant) compose.waitUntil(10_000) {
            !compose.onNodeWithTag("draft").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
    }

    private fun systemBack() {
        waitForWindowFocus()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun waitForWindowFocus() {
        compose.waitUntil(10_000) {
            var ready = false
            onActivity {
                val view = it.window.decorView
                ready = view.hasWindowFocus() && view.isAttachedToWindow && ViewCompat.getRootWindowInsets(view) != null
            }
            ready
        }
    }

    private fun imeVisible(): Boolean {
        var visible = false
        onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return visible
    }

    private fun imeClosed(): Boolean {
        var closed = false
        onActivity { closed = it.window.decorView.isImeClosed() }
        return closed
    }

    private fun onActivity(action: (MainActivity) -> Unit) {
        scenario.onActivity(action)
    }

    private fun assertPrivateFilesContainNoPlaintext(vararg forbidden: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = context.noBackupFilesDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.parentFile?.name == "native-account-deletion" })
        assertTrue(files.any { it.parentFile?.name == "native-core" })
        files.forEach { file ->
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            forbidden.forEach { secret -> assertFalse("Private storage contains request material: ${file.name}", bytes.contains(secret)) }
        }
    }

    companion object {
        private const val PASSWORD = "Synthetic Password 123!"
        private const val DRAFT = "Синтетический черновик"
    }
}
