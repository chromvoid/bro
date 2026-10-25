package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UPDATE_CODE = 200L
private val UPDATE_CERTIFICATE = "a".repeat(64)
private val UPDATE_BYTES = ByteArray(131_073) { (it % 251).toByte() }
private fun updateDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

private fun updateOffer() = NativeUpdateOffer(NATIVE_UPDATE_PACKAGE, UPDATE_CODE, "2026.10.16", updateDigest(UPDATE_BYTES), UPDATE_BYTES.size.toLong())

private fun updateManifest(offer: NativeUpdateOffer = updateOffer(), edits: Map<String, JsonElement?> = emptyMap()): ByteArray {
    val release = mutableMapOf<String, JsonElement>(
        "package" to JsonPrimitive(offer.packageName), "version_code" to JsonPrimitive(offer.versionCode),
        "version_name" to JsonPrimitive(offer.versionName), "sha256" to JsonPrimitive(offer.sha256), "size" to JsonPrimitive(offer.size),
    )
    for ((key, value) in edits) if (value == null) release.remove(key) else release[key] = value
    return JsonObject(mapOf("release" to JsonObject(release))).toString().encodeToByteArray()
}

private class UpdateDownload : NativeUpdateDownload {
    private val bytes = ByteArrayOutputStream()
    var discarded = false
    var finished = false
    var writes = 0
    override suspend fun write(bytes: ByteArray, count: Int) {
        this.bytes.write(bytes, 0, count)
        writes++
    }
    override suspend fun finish(): String {
        finished = true
        return updateDigest(bytes.toByteArray())
    }
    override suspend fun discard() { discarded = true }
    fun contents(): ByteArray = bytes.toByteArray()
}

private class UpdatePlatform : NativeUpdatePlatform {
    override var installedVersionCode = 100L
    override val releaseCertificateSha256 = UPDATE_CERTIFICATE
    override val installs = MutableStateFlow(NativeInstallState())
    val download = UpdateDownload()
    var identity = NativeApkIdentity(NATIVE_UPDATE_PACKAGE, UPDATE_CODE, listOf(UPDATE_CERTIFICATE))
    var creates = 0
    var inspections = 0
    var installAttempts = 0
    var allowInstall = true
    override suspend fun createDownload(): NativeUpdateDownload { creates++; return download }
    override suspend fun inspect(download: NativeUpdateDownload): NativeApkIdentity { inspections++; return identity }
    override suspend fun install(download: NativeUpdateDownload) {
        installAttempts++
        if (!allowInstall) throw NativeUpdateFailure(NativeUpdateError.PermissionRequired)
    }
}

private class UpdateFixture(
    val sessions: SessionManager,
    val sessionHttp: NativeHttpClient,
    val http: NativeUpdateHttpClient,
    val controller: NativeUpdateController,
    val platform: UpdatePlatform,
) {
    fun close() { http.close(); sessionHttp.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.updateEngine(handler: MockRequestHandler): MockEngine = MockEngine(
    MockEngineConfig().apply {
        // Controller jobs and HTTP callbacks must share one scheduler before runCurrent asserts.
        dispatcher = UnconfinedTestDispatcher(testScheduler)
        addHandler(handler)
    },
)

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.updateFixture(
    engine: MockEngine,
    platform: UpdatePlatform = UpdatePlatform(),
    record: StoredSession? = featureRecord(),
    origin: String = FEATURE_ORIGIN,
    allowInsecureLoopback: Boolean = false,
    sessionOrigin: String = origin,
    serverOrigin: () -> String = { sessionOrigin },
): UpdateFixture {
    val secrets = FeatureSessionSecrets(record?.let { it.copy(namespace = it.namespace.copy(origin = sessionOrigin)) })
    val sessions = SessionManager("synthetic", sessionOrigin, secrets, {}, { FEATURE_NOW }, allowInsecureLoopback = sessionOrigin.startsWith("http://"))
    val sessionHttp = NativeHttpClient(updateEngine { error("Unexpected session request") }, sessionOrigin, sessions, { FEATURE_NOW },
        allowInsecureLoopback = sessionOrigin.startsWith("http://"), serverOrigin = serverOrigin)
    val http = NativeUpdateHttpClient(engine, origin, allowInsecureLoopback)
    sessions.restore()
    val controller = NativeUpdateController(backgroundScope, http, platform)
    runCurrent()
    return UpdateFixture(sessions, sessionHttp, http, controller, platform)
}

@OptIn(ExperimentalCoroutinesApi::class)
class NativeUpdateTest {
    @Test
    fun manifestAcceptsThePinnedChannelAndNoRelease() {
        assertEquals(updateOffer(), decodeNativeUpdateManifest(updateManifest()))
        assertNull(decodeNativeUpdateManifest("""{"release":null}""".encodeToByteArray()))
        assertEquals(NativeUpdateError.WrongPackage, assertFailsWith<NativeUpdateFailure> {
            decodeNativeUpdateManifest(updateManifest(edits = mapOf("package" to JsonPrimitive("dev.foreign.app"))))
        }.error)
    }

    @Test
    fun manifestRejectsMissingFieldsWrongTypesAndUnsafeValues() {
        val invalidFields = listOf(
            "package" to null,
            "version_code" to JsonPrimitive("200"), "version_code" to JsonPrimitive(true),
            "version_code" to JsonPrimitive(1), "version_code" to JsonPrimitive(2_100_000_001L),
            "version_name" to JsonPrimitive(200), "version_name" to JsonPrimitive(""),
            "version_name" to JsonPrimitive("a".repeat(65)), "version_name" to JsonPrimitive("bad\nname"),
            "sha256" to JsonPrimitive("a".repeat(63)), "sha256" to JsonPrimitive("A".repeat(64)),
            "size" to JsonPrimitive("12"), "size" to JsonPrimitive(0), "size" to JsonPrimitive(MAX_UPDATE_BYTES + 1),
            "size" to JsonNull,
        )
        for ((field, value) in invalidFields) {
            assertEquals(NativeUpdateError.InvalidManifest, assertFailsWith<NativeUpdateFailure>("$field=$value") {
                decodeNativeUpdateManifest(updateManifest(edits = mapOf(field to value)))
            }.error)
        }
        for (body in listOf("{}", "[]", "{", """{"release":true}""")) {
            assertEquals(NativeUpdateError.InvalidManifest, assertFailsWith<NativeUpdateFailure>(body) {
                decodeNativeUpdateManifest(body.encodeToByteArray())
            }.error)
        }
    }

    @Test
    fun archiveRejectsForeignPackageCertificateMultipleSignersAndDowngrades() {
        val offer = updateOffer()
        val apk = NativeApkIdentity(NATIVE_UPDATE_PACKAGE, UPDATE_CODE, listOf(UPDATE_CERTIFICATE))
        validateNativeUpdateApk(offer, apk, 100, UPDATE_CERTIFICATE)
        for (foreign in listOf("dev.unrelated.app", "dev.foreign.app")) {
            assertEquals(NativeUpdateError.WrongPackage, assertFailsWith<NativeUpdateFailure> {
                validateNativeUpdateApk(offer, apk.copy(packageName = foreign), 100, UPDATE_CERTIFICATE)
            }.error)
            assertEquals(NativeUpdateError.WrongPackage, assertFailsWith<NativeUpdateFailure> {
                validateNativeUpdateApk(offer.copy(packageName = foreign), apk, 100, UPDATE_CERTIFICATE)
            }.error)
        }
        for (signers in listOf(emptyList(), listOf("b".repeat(64)), listOf(UPDATE_CERTIFICATE, "b".repeat(64)), listOf(UPDATE_CERTIFICATE, UPDATE_CERTIFICATE))) {
            assertEquals(NativeUpdateError.Certificate, assertFailsWith<NativeUpdateFailure> {
                validateNativeUpdateApk(offer, apk.copy(signerSha256 = signers), 100, UPDATE_CERTIFICATE)
            }.error)
        }
        assertEquals(NativeUpdateError.Certificate, assertFailsWith<NativeUpdateFailure> {
            validateNativeUpdateApk(offer, apk, 100, "invalid-pin")
        }.error)
        for (code in listOf(99L, 100L)) {
            assertEquals(NativeUpdateError.Downgrade, assertFailsWith<NativeUpdateFailure> {
                validateNativeUpdateApk(offer, apk.copy(versionCode = code), 100, UPDATE_CERTIFICATE)
            }.error)
            assertEquals(NativeUpdateError.Downgrade, assertFailsWith<NativeUpdateFailure> {
                validateNativeUpdateApk(offer.copy(versionCode = code), apk, 100, UPDATE_CERTIFICATE)
            }.error)
        }
        assertEquals(NativeUpdateError.Integrity, assertFailsWith<NativeUpdateFailure> {
            validateNativeUpdateApk(offer, apk.copy(versionCode = UPDATE_CODE + 1), 100, UPDATE_CERTIFICATE)
        }.error)
    }

    @Test
    fun successfulDownloadUsesPublicBuildOriginPathsWithoutCredentialsOrCookiesAndReportsProgress() = runTest {
        val requests = mutableListOf<String>()
        val fixture = updateFixture(updateEngine { request ->
            requests += request.url.toString()
            assertEquals("synthetic.invalid", request.url.host)
            assertEquals("https", request.url.protocol.name)
            assertTrue(request.url.parameters.isEmpty())
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["X-Expected-User"])
            assertNull(request.headers["Cookie"])
            if (request.url.encodedPath.endsWith(".apk")) respond(UPDATE_BYTES, headers = headersOf("Content-Length", UPDATE_BYTES.size.toString()))
            else respond(updateManifest(edits = mapOf("url" to JsonPrimitive("https://other.invalid/foreign.apk"))),
                headers = headersOf("Set-Cookie", "session=ignored; Path=/; Secure"))
        })
        val states = mutableListOf<NativeUpdateState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.controller.state.collect { states += it } }
        try {
            fixture.controller.check()
            runCurrent()
            assertEquals(NativeUpdatePhase.Available, fixture.controller.state.value.phase)
            fixture.controller.downloadAndInstall()
            runCurrent()
            assertEquals(NativeUpdatePhase.Installing, fixture.controller.state.value.phase)
            assertEquals(listOf("$FEATURE_ORIGIN/v1/shell/android", "$FEATURE_ORIGIN/v1/shell/android/$UPDATE_CODE.apk"), requests)
            assertTrue(states.any { it.phase == NativeUpdatePhase.Downloading && it.progress > 0f && it.progress < 1f })
            assertTrue(states.any { it.phase == NativeUpdatePhase.Verifying && it.progress == 1f })
            assertContentEquals(UPDATE_BYTES, fixture.platform.download.contents())
            assertTrue(fixture.platform.download.finished)
            assertTrue(fixture.platform.download.discarded)
            assertEquals(2, fixture.platform.inspections)
            assertEquals(1, fixture.platform.installAttempts)
            fixture.platform.installs.value = NativeInstallState(NativeInstallPhase.Confirmation)
            runCurrent()
            assertEquals(NativeUpdatePhase.Confirmation, fixture.controller.state.value.phase)
            fixture.platform.installs.value = NativeInstallState(NativeInstallPhase.Installed)
            runCurrent()
            assertEquals(NativeUpdatePhase.Installed, fixture.controller.state.value.phase)
        } finally { fixture.close() }
    }

    @Test
    fun differentSelectedAndMovedServerOriginsNeverReplaceTheBuildOrigin() = runTest {
        val requests = mutableListOf<String>()
        val selectedServer = "https://self-hosted.synthetic.invalid"
        var server = selectedServer
        val movedServer = "https://moved.synthetic.invalid"
        val fixture = updateFixture(updateEngine { request ->
            requests += request.url.toString()
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["X-Expected-User"])
            if (request.url.encodedPath.endsWith(".apk")) respond(UPDATE_BYTES) else respond(updateManifest())
        }, sessionOrigin = selectedServer, serverOrigin = { server })
        try {
            val lease = fixture.sessions.lease()!!
            assertEquals(selectedServer, fixture.sessionHttp.serverOrigin)
            server = movedServer
            assertEquals(movedServer, fixture.sessionHttp.serverOrigin)
            fixture.controller.check()
            runCurrent()
            assertEquals(NativeUpdatePhase.Available, fixture.controller.state.value.phase)
            fixture.controller.downloadAndInstall()
            runCurrent()
            assertEquals(NativeUpdatePhase.Installing, fixture.controller.state.value.phase)
            assertEquals(listOf("$FEATURE_ORIGIN/v1/shell/android", "$FEATURE_ORIGIN/v1/shell/android/$UPDATE_CODE.apk"), requests)
            assertEquals(selectedServer, fixture.sessions.origin)
            assertEquals(selectedServer, fixture.sessions.lease()!!.namespace.origin)
            assertTrue(fixture.sessions.isCurrent(lease))
        } finally { fixture.close() }
    }

    @Test
    fun hashAndActualOrDeclaredSizeMismatchAreRejectedBeforeArchiveInspection() = runTest {
        for (kind in listOf("hash", "short", "long", "content-length")) {
            val bytes = when (kind) { "short" -> UPDATE_BYTES.copyOf(UPDATE_BYTES.size - 1); "long" -> UPDATE_BYTES + byteArrayOf(0); else -> UPDATE_BYTES }
            val offer = if (kind == "hash") updateOffer().copy(sha256 = "b".repeat(64)) else updateOffer()
            val fixture = updateFixture(updateEngine { request ->
                if (request.url.encodedPath.endsWith(".apk")) {
                    val headers = if (kind == "content-length") headersOf("Content-Length", "1") else headersOf()
                    respond(bytes, headers = headers)
                } else respond(updateManifest(offer))
            })
            try {
                fixture.controller.check()
                runCurrent()
                fixture.controller.downloadAndInstall()
                runCurrent()
                assertEquals(NativeUpdatePhase.Failed, fixture.controller.state.value.phase, kind)
                assertEquals(NativeUpdateError.Integrity, fixture.controller.state.value.error, kind)
                assertEquals(0, fixture.platform.inspections, kind)
                assertEquals(0, fixture.platform.installAttempts, kind)
                assertTrue(fixture.platform.download.discarded, kind)
            } finally { fixture.close() }
        }
    }

    @Test
    fun wrongArchiveIdentityIsRejectedBeforeInstaller() = runTest {
        val cases = listOf(
            NativeApkIdentity("dev.foreign.app", UPDATE_CODE, listOf(UPDATE_CERTIFICATE)) to NativeUpdateError.WrongPackage,
            NativeApkIdentity(NATIVE_UPDATE_PACKAGE, UPDATE_CODE, listOf("b".repeat(64))) to NativeUpdateError.Certificate,
            NativeApkIdentity(NATIVE_UPDATE_PACKAGE, 100, listOf(UPDATE_CERTIFICATE)) to NativeUpdateError.Downgrade,
            NativeApkIdentity(NATIVE_UPDATE_PACKAGE, UPDATE_CODE + 1, listOf(UPDATE_CERTIFICATE)) to NativeUpdateError.Integrity,
        )
        for ((identity, expected) in cases) {
            val fixture = updateFixture(updateEngine { request ->
                if (request.url.encodedPath.endsWith(".apk")) respond(UPDATE_BYTES) else respond(updateManifest())
            }, platform = UpdatePlatform().also { it.identity = identity })
            try {
                fixture.controller.check()
                runCurrent()
                fixture.controller.downloadAndInstall()
                runCurrent()
                assertEquals(expected, fixture.controller.state.value.error)
                assertEquals(0, fixture.platform.installAttempts)
                assertTrue(fixture.platform.download.discarded)
            } finally { fixture.close() }
        }
    }

    @Test
    fun manifestAndApkRedirectsAreNeverFollowed() = runTest {
        for (redirectManifest in listOf(true, false)) {
            val requests = mutableListOf<String>()
            val fixture = updateFixture(updateEngine { request ->
                requests += request.url.toString()
                if (!redirectManifest && !request.url.encodedPath.endsWith(".apk")) respond(updateManifest())
                else respond("", HttpStatusCode.Found, headersOf("Location", "https://other.invalid/foreign.apk"))
            })
            try {
                fixture.controller.check()
                runCurrent()
                if (!redirectManifest) {
                    fixture.controller.downloadAndInstall()
                    runCurrent()
                    assertTrue(fixture.platform.download.discarded)
                }
                assertEquals(NativeUpdateError.Network, fixture.controller.state.value.error)
                assertEquals(if (redirectManifest) 1 else 2, requests.size)
                assertTrue(requests.all { it.startsWith(FEATURE_ORIGIN) })
                assertEquals(0, fixture.platform.installAttempts)
            } finally { fixture.close() }
        }
    }

    @Test
    fun unknownSourcePermissionResumesTheVerifiedDownloadAndRechecksArchive() = runTest {
        var downloads = 0
        val fixture = updateFixture(updateEngine { request ->
            if (request.url.encodedPath.endsWith(".apk")) { downloads++; respond(UPDATE_BYTES) } else respond(updateManifest())
        }, platform = UpdatePlatform().also { it.allowInstall = false })
        try {
            fixture.controller.check()
            runCurrent()
            fixture.controller.downloadAndInstall()
            runCurrent()
            assertEquals(NativeUpdatePhase.PermissionRequired, fixture.controller.state.value.phase)
            assertEquals(2, fixture.platform.inspections)
            assertFalse(fixture.platform.download.discarded)
            fixture.platform.allowInstall = true
            fixture.controller.resumeInstall()
            runCurrent()
            assertEquals(NativeUpdatePhase.Installing, fixture.controller.state.value.phase)
            assertEquals(3, fixture.platform.inspections)
            assertEquals(2, fixture.platform.installAttempts)
            assertEquals(1, downloads)
            assertTrue(fixture.platform.download.discarded)
        } finally { fixture.close() }
    }

    @Test
    fun permissionResumeRejectsAnArchiveOrInstalledCodeThatChangedWhileInSettings() = runTest {
        for (change in listOf("package", "certificate", "downgrade")) {
            val fixture = updateFixture(updateEngine { request ->
                if (request.url.encodedPath.endsWith(".apk")) respond(UPDATE_BYTES) else respond(updateManifest())
            }, platform = UpdatePlatform().also { it.allowInstall = false })
            try {
                fixture.controller.check()
                runCurrent()
                fixture.controller.downloadAndInstall()
                runCurrent()
                assertEquals(NativeUpdatePhase.PermissionRequired, fixture.controller.state.value.phase)
                when (change) {
                    "package" -> fixture.platform.identity = fixture.platform.identity.copy(packageName = "dev.foreign.app")
                    "certificate" -> fixture.platform.identity = fixture.platform.identity.copy(signerSha256 = listOf("b".repeat(64)))
                    else -> fixture.platform.installedVersionCode = UPDATE_CODE
                }
                fixture.platform.allowInstall = true
                fixture.controller.resumeInstall()
                runCurrent()
                val expected = when (change) { "package" -> NativeUpdateError.WrongPackage; "certificate" -> NativeUpdateError.Certificate; else -> NativeUpdateError.Downgrade }
                assertEquals(expected, fixture.controller.state.value.error)
                assertEquals(1, fixture.platform.installAttempts)
                assertTrue(fixture.platform.download.discarded)
            } finally { fixture.close() }
        }
    }

}
