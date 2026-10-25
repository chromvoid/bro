package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val PROBE_ORIGIN = "https://server.synthetic.invalid"
private const val PROBE_INSTANCE = "abcdefab-1111-4111-8111-111111111111"
private const val PROBE_CONFIG = """{"instance_id":"$PROBE_INSTANCE","name":"Synthetic server","client_api":1,"native_api":1}"""
private val PROBE_JSON_HEADERS = headersOf("Content-Type", "application/json; charset=utf-8")

@OptIn(ExperimentalCoroutinesApi::class)
class NativeServerProbeTest {
    @Test
    fun addressesAreCanonicalAndEveryRequestIsAnonymousWithoutKeepingCookies() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val probe = probe { request ->
            requests += request
            assertEquals("GET", request.method.value)
            assertEquals("$PROBE_ORIGIN/v1/client/config", request.url.toString())
            assertEquals("application/json", request.headers["Accept"])
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["X-Expected-User"])
            assertNull(request.headers["Cookie"])
            respond(PROBE_CONFIG.replace(PROBE_INSTANCE, PROBE_INSTANCE.uppercase()), headers = headersOf(
                "Content-Type" to listOf("application/json"), "Set-Cookie" to listOf("session=synthetic; Secure"),
            ))
        }
        try {
            for (address in listOf("server.synthetic.invalid", "$PROBE_ORIGIN/", "https://SERVER.SYNTHETIC.INVALID:443")) {
                assertEquals(NativeServerCheckResult.Compatible(PROBE_ORIGIN, PROBE_INSTANCE, "Synthetic server"),
                    probe.check(address))
            }
            assertEquals(3, requests.size)
        } finally { probe.close() }
    }

    @Test
    fun invalidAddressesAreRejectedBeforeAnyNetworkRequest() = runTest {
        var requests = 0
        val probe = probe { requests++; respond(PROBE_CONFIG, headers = PROBE_JSON_HEADERS) }
        try {
            val invalid = listOf(
                "", " ", "\t\n ", "server. synthetic.invalid", "server.\tsynthetic.invalid", "https://server.\nsynthetic.invalid",
                "http://server.synthetic.invalid", "http://127.0.0.1:1234", "ftp://server.synthetic.invalid",
                "$PROBE_ORIGIN/path", "$PROBE_ORIGIN//", "$PROBE_ORIGIN/./", "$PROBE_ORIGIN/%2F",
                "$PROBE_ORIGIN?", "$PROBE_ORIGIN?query=value", "$PROBE_ORIGIN#", "$PROBE_ORIGIN#fragment",
                "https://user:password@server.synthetic.invalid", "https://@server.synthetic.invalid",
                "https://server.synthetic.invalid\\other", "https://", "https:///", "https://server.synthetic.invalid:",
                "https://server.synthetic.invalid:0", "https://server.synthetic.invalid:65536", "https://server.synthetic.invalid:wrong",
            )
            for (address in invalid) assertEquals(NativeServerCheckResult.InvalidAddress, probe.check(address), address)
            assertEquals(0, requests)
        } finally { probe.close() }
    }

    @Test
    fun onlyExplicitDebugAllowsCleartextLoopback() = runTest {
        val requests = mutableListOf<String>()
        val probe = NativeServerProbe(engine { request ->
            requests += request.url.toString()
            respond(PROBE_CONFIG, headers = PROBE_JSON_HEADERS)
        }, allowInsecureLoopback = true)
        try {
            for (address in listOf("http://127.0.0.1:1234/", "http://localhost:1234", "http://[::1]:1234/")) {
                assertEquals(NativeServerCheckResult.Compatible(address.removeSuffix("/"), PROBE_INSTANCE, "Synthetic server"),
                    probe.check(address))
            }
            assertEquals(NativeServerCheckResult.InvalidAddress, probe.check("http://server.synthetic.invalid"))
            assertEquals(3, requests.size)
        } finally { probe.close() }
    }

    @Test
    fun statusesAndRedirectsAreTypedAndNeverFollowed() = runTest {
        val requests = mutableListOf<String>()
        var status = HttpStatusCode.OK
        val probe = probe { request ->
            requests += request.url.toString()
            respond(PROBE_CONFIG, status, headersOf(
                "Content-Type" to listOf("application/json"), "Location" to listOf("https://foreign.synthetic.invalid/config"),
            ))
        }
        try {
            for (code in listOf(201, 204, 301, 302, 307, 308, 400, 401, 403, 404, 429, 500, 503)) {
                status = HttpStatusCode.fromValue(code)
                assertEquals(if (code >= 500) NativeServerCheckResult.Unreachable else NativeServerCheckResult.NotBro,
                    probe.check(PROBE_ORIGIN), "HTTP $code")
            }
            assertEquals(List(13) { "$PROBE_ORIGIN/v1/client/config" }, requests)
        } finally { probe.close() }
    }

    @Test
    fun malformedConfigurationIsNotBroAndDoesNotBecomeACompatibilityError() = runTest {
        var body = PROBE_CONFIG
        val probe = probe { respond(body, headers = PROBE_JSON_HEADERS) }
        try {
            val invalid = listOf(
                "{", "[]", "null", "{}", "<html>not Bro</html>",
                PROBE_CONFIG.replace("\"$PROBE_INSTANCE\"", "123"),
                PROBE_CONFIG.replace(PROBE_INSTANCE, "not-a-uuid"),
                PROBE_CONFIG.replace(PROBE_INSTANCE, "1-1-1-1-1"),
                PROBE_CONFIG.replace("\"Synthetic server\"", "null"),
                PROBE_CONFIG.replace("Synthetic server", ""),
                PROBE_CONFIG.replace("Synthetic server", "x".repeat(121)),
                PROBE_CONFIG.replace("\"client_api\":1", "\"client_api\":2"),
                PROBE_CONFIG.replace("\"client_api\":1", "\"client_api\":\"1\""),
                PROBE_CONFIG.replace("\"client_api\":1", "\"client_api\":1.0"),
                PROBE_CONFIG.replace("\"client_api\":1", "\"client_api\":2147483648"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":null"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":0"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":-1"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":\"1\""),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":1.0"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":2147483648.5"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":1e0"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":-9223372036854775809"),
                PROBE_CONFIG.replace("\"native_api\":1", "\"native_api\":true"),
            )
            for (invalidBody in invalid) {
                body = invalidBody
                assertEquals(NativeServerCheckResult.NotBro, probe.check(PROBE_ORIGIN), invalidBody)
            }
            for (name in listOf("x", "x".repeat(120))) {
                body = PROBE_CONFIG.replace("Synthetic server", name)
                assertEquals(NativeServerCheckResult.Compatible(PROBE_ORIGIN, PROBE_INSTANCE, name), probe.check(PROBE_ORIGIN))
            }
        } finally { probe.close() }
    }

    @Test
    fun streamLimitAcceptsExactly16KiBAndRejectsAnExtraByteWithoutContentLength() = runTest {
        var body = PROBE_CONFIG.padEnd(16 * 1024, ' ')
        val probe = probe { respond(ByteReadChannel(body.encodeToByteArray()), headers = PROBE_JSON_HEADERS) }
        try {
            assertEquals(NativeServerCheckResult.Compatible(PROBE_ORIGIN, PROBE_INSTANCE, "Synthetic server"),
                probe.check(PROBE_ORIGIN))
            body += " "
            assertEquals(NativeServerCheckResult.NotBro, probe.check(PROBE_ORIGIN))
        } finally { probe.close() }
    }

    private fun TestScope.probe(handler: MockRequestHandler) =
        NativeServerProbe(engine(handler))

    private fun TestScope.engine(handler: MockRequestHandler) =
        MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        })
}
