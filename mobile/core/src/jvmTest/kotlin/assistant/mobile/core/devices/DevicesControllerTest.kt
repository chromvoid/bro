package assistant.mobile.core.devices

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.NativeJson
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesControllerTest {
    @Test
    fun explicitSaveSendsCurrentRevisionThenRereadsListAndPrivatePreview() = runTest {
        val fixture = DevicesControllerFixture(this)
        try {
            fixture.start()
            val previousFrame = fixture.display.state.value!!.preview!!
            fixture.requests.clear()
            fixture.display.setLayout(0, DisplayLayout.Grid)
            fixture.display.setTitle(0, "  Обзор  ")
            fixture.display.setHomeAfter(60)
            fixture.display.updateName("  Прихожая  ")
            assertTrue(fixture.display.state.value!!.changed)
            fixture.display.save()
            val put = fixture.requests.single { it.method == HttpMethod.Put }
            val payload = devicesBody(put)
            assertEquals("2", payload.getValue("revision").jsonPrimitive.content)
            assertEquals("Прихожая", payload.getValue("name").jsonPrimitive.content)
            assertEquals("Обзор", fixture.server.settings.pages.first().title)
            assertEquals(listOf("subscriptions", "clock", "clock", "clock"), fixture.server.settings.pages.first().cards)
            assertEquals(60, fixture.server.settings.homeAfterSeconds)
            val state = fixture.display.state.value!!
            assertTrue(state.saved)
            assertFalse(state.changed)
            assertEquals(3L, state.display.data!!.device.revision)
            assertEquals("Прихожая", fixture.devices.state.value!!.devices.data!!.first().name)
            assertTrue(!previousFrame.contentEquals(state.preview!!))
            assertTrue(fixture.requests.any { it.url.encodedPath == "/v1/devices" })
            assertEquals("Bearer ${fixture.credential}", put.headers["Authorization"])
            assertNull(put.headers["Idempotency-Key"], "Display writes have no receipt/lookup API and are never retried automatically")
        } finally { fixture.close() }
    }

    @Test
    fun pairingValidatesThenApprovesOnlyTheNormalizedCodeAndRefreshesTheList() = runTest {
        val fixture = DevicesControllerFixture(this)
        try {
            fixture.start()
            fixture.requests.clear()
            assertNull(fixture.devices.approve("4K7Q-2MX0", "Стол"))
            assertEquals(GeneratedStrings.Devices.codeInvalid, fixture.devices.state.value!!.error)
            assertTrue(fixture.requests.isEmpty())
            val paired = assertNotNull(fixture.devices.approve("4k7q 2mxa", "  Стол  "))
            assertEquals("paired", paired.id)
            assertEquals("Стол", paired.name)
            val body = devicesBody(fixture.requests.single { it.method == HttpMethod.Post })
            assertEquals("4K7Q-2MXA", body.getValue("code").jsonPrimitive.content)
            assertEquals("Стол", body.getValue("name").jsonPrimitive.content)
            assertTrue(fixture.devices.state.value!!.devices.data!!.any { it.id == "paired" })
            assertFalse(fixture.devices.state.value!!.busy)
            assertNull(fixture.devices.state.value!!.error)
        } finally { fixture.close() }
    }

    @Test
    fun listRevokeRefreshesOnRefusalAndNeverRevokesTheCurrentDevice() = runTest {
        val fixture = DevicesControllerFixture(this)
        try {
            fixture.start()
            fixture.requests.clear()
            assertFalse(fixture.devices.revoke("android"))
            assertTrue(fixture.requests.isEmpty())
            fixture.revokeError = true
            assertFalse(fixture.devices.revoke("browser"))
            assertEquals("Устройство не найдено", fixture.devices.state.value!!.serverError)
            assertTrue(fixture.requests.any { it.url.encodedPath == "/v1/devices" })
            fixture.revokeError = false
            assertTrue(fixture.devices.revoke("browser"))
            assertFalse(fixture.devices.state.value!!.devices.data!!.any { it.id == "browser" })
        } finally { fixture.close() }
    }

    @Test
    fun previewRedirectNeverForwardsBearerToAnotherOriginAndInvalidImagesAreIgnored() = runTest {
        val fixture = DevicesControllerFixture(this)
        try {
            fixture.start()
            val previous = fixture.display.state.value!!.preview!!.copyOf()
            val version = fixture.display.state.value!!.previewVersion
            fixture.requests.clear()
            fixture.redirectPreview = true
            fixture.display.loadPreview()
            assertEquals(1, fixture.requests.size)
            assertEquals("synthetic.invalid", fixture.requests.single().url.host)
            assertEquals("Bearer ${fixture.credential}", fixture.requests.single().headers["Authorization"])
            assertEquals(version, fixture.display.state.value!!.previewVersion)
            fixture.redirectPreview = false
            fixture.invalidPreview = true
            fixture.display.loadPreview()
            assertEquals(version, fixture.display.state.value!!.previewVersion)
            assertContentEquals(previous, fixture.display.state.value!!.preview)
            assertTrue(fixture.requests.all { it.url.host == "synthetic.invalid" })
        } finally { fixture.close() }
    }

    @Test
    fun sessionExpiryDuringPreviewDiscardsLateBytesAndClearsAllPrivateDeviceState() = runTest {
        val fixture = DevicesControllerFixture(this)
        try {
            fixture.start()
            val gate = CompletableDeferred<Unit>()
            fixture.previewGate = gate
            val pending = launch { fixture.display.loadPreview() }
            runCurrent()
            assertFalse(pending.isCompleted)
            fixture.sessions.expire(fixture.sessions.lease()!!)
            runCurrent()
            assertNull(fixture.display.state.value)
            assertNull(fixture.devices.state.value)
            gate.complete(Unit)
            pending.join()
            assertNull(fixture.display.state.value)
            assertNull(fixture.devices.state.value)
        } finally { fixture.close() }
    }
}

private fun devicesBody(request: HttpRequestData) =
    NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject

@OptIn(ExperimentalCoroutinesApi::class)
private class DevicesControllerFixture(private val test: TestScope) {
    private val record = featureRecord()
    val credential = record.credential
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), {}, { FEATURE_NOW })
    val requests = mutableListOf<HttpRequestData>()
    var server = decodeDisplayDescription(devicesFixture("display-server"))
    private var frame = 1
    private var paired: PairedDevice? = null
    private val revokedIds = mutableSetOf<String>()
    var revoked = false
    var rejectSave = false
    var saveError: String? = null
    var inputError: String? = null
    var revokeError = false
    var redirectPreview = false
    var invalidPreview = false
    var listGate: CompletableDeferred<Unit>? = null
    var previewGate: CompletableDeferred<Unit>? = null
    var descriptionGate: CompletableDeferred<Unit>? = null
    val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            assertEquals("synthetic.invalid", request.url.host)
            assertEquals("Bearer $credential", request.headers["Authorization"])
            assertEquals(record.namespace.userId, request.headers["X-Expected-User"])
            val path = request.url.encodedPath
            when {
                path == "/v1/devices" -> {
                    listGate?.await()
                    val devices = decodeDevices(devicesFixture("list")).map {
                        if (it.id == "d") server.device else it
                    }.filterNot { it.id in revokedIds }.toMutableList()
                    paired?.let { devices += ClientDevice(it.id, it.kind, it.name, "2026-10-16T12:00:00Z", revision = 0) }
                    respond("{\"devices\":${NativeJson.encodeToString(devices)}}")
                }
                path == "/v1/devices/pairings/approve" -> {
                    val payload = devicesBody(request)
                    assertEquals("4K7Q-2MXA", payload.getValue("code").jsonPrimitive.content)
                    paired = PairedDevice("paired", "display", payload["name"]?.jsonPrimitive?.content ?: "Экран")
                    respond("{\"device\":${NativeJson.encodeToString(paired)}}")
                }
                path.endsWith("/revoke") -> {
                    if (revokeError) respond("{\"message\":\"Устройство не найдено\"}", HttpStatusCode.NotFound)
                    else {
                        val id = path.removePrefix("/v1/devices/").removeSuffix("/revoke")
                        revokedIds += id
                        if (id == "d") revoked = true
                        respond("{\"revoked\":true}")
                    }
                }
                path.endsWith("/preview/input") -> {
                    if (inputError != null) respond(inputError!!, HttpStatusCode.UnprocessableEntity)
                    else {
                        frame++
                        respond("{\"etag\":\"frame-$frame\"}")
                    }
                }
                path.endsWith("/preview.png") -> {
                    previewGate?.await()
                    when {
                        redirectPreview -> respond("", HttpStatusCode.Found, headersOf("Location", "https://other.invalid/private.png"))
                        invalidPreview -> respond("<html>private</html>", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                        request.headers["If-None-Match"] == "\"frame-$frame\"" -> respond("", HttpStatusCode.NotModified)
                        else -> respond(pngFrame(frame), HttpStatusCode.OK,
                            headersOf("Content-Type" to listOf("image/png"), "ETag" to listOf("\"frame-$frame\"")))
                    }
                }
                path == "/v1/display/devices/d" && request.method == HttpMethod.Put -> {
                    val body = devicesBody(request)
                    assertEquals(server.device.revision.toString(), body.getValue("revision").jsonPrimitive.content)
                    when {
                        saveError != null -> respond(JsonObject(mapOf("message" to JsonPrimitive(saveError!!))).toString(), HttpStatusCode.UnprocessableEntity)
                        rejectSave -> {
                            rejectSave = false
                            server = server.copy(device = server.device.copy(name = "Другой клиент", revision = server.device.revision!! + 1))
                            respond("{\"message\":\"Настройки экрана уже изменены на другом устройстве\"}", HttpStatusCode.Conflict)
                        }
                        else -> {
                            server = server.copy(device = server.device.copy(
                                name = body["name"]?.jsonPrimitive?.content ?: server.device.name,
                                revision = server.device.revision!! + 1),
                                settings = decodeDisplaySettings(body.getValue("settings").toString().encodeToByteArray()))
                            frame++
                            respond(NativeJson.encodeToString(server))
                        }
                    }
                }
                path == "/v1/display/devices/d" -> {
                    // Capture before suspension, as a delayed old response may outlive navigation.
                    val captured = server
                    descriptionGate?.await()
                    respond(NativeJson.encodeToString(captured))
                }
                path == "/v1/display/devices/other" -> respond(NativeJson.encodeToString(server.copy(
                    device = server.device.copy(id = "other", name = "Другой экран"))))
                else -> error("Unexpected devices request: ${request.method.value} $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
    val devices = DevicesController(test.backgroundScope, sessions, http)
    val display = DisplayController(test.backgroundScope, sessions, http, devices::refresh)

    suspend fun start() {
        sessions.restore()
        devices.activate()
        display.activate("d")
        devices.state.first { it?.devices?.data != null }
        display.state.first { it?.display?.data != null && it.preview != null }
        test.runCurrent()
    }

    fun close() {
        devices.close()
        display.close()
        http.close()
    }
}

/** A synthetic black-and-white frame; each server event changes actual PNG pixels. */
private fun pngFrame(index: Int): ByteArray {
    val image = BufferedImage(400, 300, BufferedImage.TYPE_BYTE_BINARY)
    image.raster.setSample(index % 400, 0, 0, 1)
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
}
