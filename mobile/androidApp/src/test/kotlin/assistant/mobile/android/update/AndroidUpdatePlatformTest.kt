package assistant.mobile.android.update

import assistant.mobile.core.NATIVE_UPDATE_PACKAGE
import assistant.mobile.core.NativeApkIdentity
import assistant.mobile.core.NativeInstallPhase
import assistant.mobile.core.NativeInstallState
import assistant.mobile.core.NativeUpdateController
import assistant.mobile.core.NativeUpdateDownload
import assistant.mobile.core.NativeUpdateError
import assistant.mobile.core.NativeUpdateHttpClient
import assistant.mobile.core.NativeUpdatePhase
import assistant.mobile.core.NativeUpdatePlatform
import assistant.mobile.platform.NativeCorePlatform
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUpdatePlatformTest {
    @Test
    fun publicManifestAndApkNeverSendCredentialsOrEchoCookies() = runBlocking {
        UpdateServer().use { server -> UpdateFixture(server.origin, coroutineContext).use { fixture ->
            fixture.controller.check()
            fixture.await(NativeUpdatePhase.Available)
            fixture.controller.downloadAndInstall()
            fixture.await(NativeUpdatePhase.Installed)

            assertEquals(listOf(MANIFEST_PATH, APK_PATH), server.requests.map { it.path })
            for (request in server.requests) {
                assertEquals("GET", request.method)
                assertNull(request.authorization)
                assertNull(request.expectedUser)
                assertNull(request.cookie)
                assertNull(request.query)
            }
            assertArrayEquals(APK_BYTES, fixture.platform.download.contents())
        } }
    }

    @Test
    fun realEngineRejectsManifestAndApkRedirectsWithoutContactingTheirDestination() = runBlocking {
        for (path in listOf(MANIFEST_PATH, APK_PATH)) {
            UpdateServer().use { source -> UpdateServer().use { destination ->
                source.location = "${destination.origin}/unexpected"
                source.redirectPath = path
                UpdateFixture(source.origin, coroutineContext).use { fixture ->
                    fixture.controller.check()
                    if (path == APK_PATH) {
                        fixture.await(NativeUpdatePhase.Available)
                        fixture.controller.downloadAndInstall()
                    }
                    assertEquals(NativeUpdateError.Network, fixture.await(NativeUpdatePhase.Failed).error)
                    assertEquals(if (path == MANIFEST_PATH) listOf(MANIFEST_PATH) else listOf(MANIFEST_PATH, APK_PATH),
                        source.requests.map { it.path })
                    assertTrue(destination.requests.isEmpty())
                }
            } }
        }
    }

    @Test
    fun droppedManifestAndApkConnectionsFailWithoutRetrying() = runBlocking {
        for (path in listOf(MANIFEST_PATH, APK_PATH)) {
            UpdateServer().use { server -> UpdateFixture(server.origin, coroutineContext).use { fixture ->
                server.dropPath = path
                fixture.controller.check()
                if (path == APK_PATH) {
                    fixture.await(NativeUpdatePhase.Available)
                    fixture.controller.downloadAndInstall()
                }
                assertEquals(NativeUpdateError.Network, fixture.await(NativeUpdatePhase.Failed).error)
                assertEquals(if (path == MANIFEST_PATH) listOf(MANIFEST_PATH) else listOf(MANIFEST_PATH, APK_PATH),
                    server.requests.map { it.path })
            } }
        }
    }
}

private class UpdateFixture(origin: String, context: CoroutineContext) : Closeable {
    private val scope = CoroutineScope(context + SupervisorJob())
    private val http = NativeUpdateHttpClient(NativeCorePlatform.httpEngine(), origin, allowInsecureLoopback = true)
    val platform = WireUpdatePlatform()
    val controller = NativeUpdateController(scope, http, platform)
    suspend fun await(phase: NativeUpdatePhase) = withTimeout(10_000) { controller.state.first { it.phase == phase } }
    override fun close() { scope.cancel(); http.close() }
}

private class WireUpdatePlatform : NativeUpdatePlatform {
    override val installedVersionCode = 100L
    override val releaseCertificateSha256 = "a".repeat(64)
    override val installs = MutableStateFlow(NativeInstallState())
    val download = WireUpdateDownload()
    override suspend fun createDownload(): NativeUpdateDownload = download
    override suspend fun inspect(download: NativeUpdateDownload) =
        NativeApkIdentity(NATIVE_UPDATE_PACKAGE, UPDATE_CODE, listOf(releaseCertificateSha256))
    override suspend fun install(download: NativeUpdateDownload) { installs.value = NativeInstallState(NativeInstallPhase.Installed) }
}

private class WireUpdateDownload : NativeUpdateDownload {
    private val bytes = ByteArrayOutputStream()
    override suspend fun write(bytes: ByteArray, count: Int) { this.bytes.write(bytes, 0, count) }
    override suspend fun finish(): String = updateDigest(contents())
    override suspend fun discard() = Unit
    fun contents(): ByteArray = bytes.toByteArray()
}

private class UpdateServer : Closeable {
    data class Request(val method: String, val path: String, val query: String?, val authorization: String?,
        val expectedUser: String?, val cookie: String?)
    val requests = Collections.synchronizedList(mutableListOf<Request>())
    @Volatile var redirectPath: String? = null
    @Volatile var location: String? = null
    @Volatile var dropPath: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += Request(exchange.requestMethod, path, exchange.requestURI.rawQuery,
                exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("X-Expected-User"),
                exchange.requestHeaders.getFirst("Cookie"))
            exchange.requestBody.use { it.readBytes() }
            if (path == dropPath) {
                exchange.close()
            } else {
                exchange.responseHeaders.set("Set-Cookie", "update_session=synthetic; Path=/")
                location?.let { exchange.responseHeaders.set("Location", it) }
                val bytes = if (path == APK_PATH) APK_BYTES else MANIFEST_BYTES
                exchange.sendResponseHeaders(if (path == redirectPath) 302 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        start()
    }
    val origin = "http://127.0.0.1:${server.address.port}"
    override fun close() { server.stop(0) }
}

private const val UPDATE_CODE = 200L
private const val MANIFEST_PATH = "/v1/shell/android"
private const val APK_PATH = "/v1/shell/android/200.apk"
private val APK_BYTES = "synthetic APK bytes".toByteArray(Charsets.UTF_8)
private fun updateDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private val MANIFEST_BYTES = """{"release":{"package":"dev.assistant.shell","version_code":200,"version_name":"2026.10.22","sha256":"${updateDigest(APK_BYTES)}","size":${APK_BYTES.size}}}"""
    .toByteArray(Charsets.UTF_8)
