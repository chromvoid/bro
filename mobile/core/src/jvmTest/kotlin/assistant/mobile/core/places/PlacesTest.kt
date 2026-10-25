package assistant.mobile.core.places

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Frozen synthetic fixtures from web/src/sections/places/places.test.ts, with the same server wire shape. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacesTest {
    @Test fun daysAndDayUseTheCurrentNativeSessionAndSixtyDayLimit() = runTest {
        val sessions = sessions()
        val paths = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            assertEquals("Bearer ${sessions.lease()!!.credential}", request.headers["Authorization"])
            assertEquals(sessions.lease()!!.namespace.userId, request.headers["X-Expected-User"])
            assertNull(request.headers["Cookie"])
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/v1/places/days") {
                assertEquals("60", request.url.parameters["limit"])
                respond(placesFixture("days"))
            } else respond(placesFixture("day"))
        }, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
        try {
            val api = NativePlacesApi(http)
            assertEquals(2, api.days(sessions.lease()!!).days.size)
            assertEquals("2026-10-09", api.day("2026-10-09", sessions.lease()!!).date)
            assertEquals(listOf("/v1/places/days", "/v1/places/days/2026-10-09"), paths)
        } finally { http.close() }
    }

    @Test fun aResponseForAnotherDayIsRejectedBeforePublishing() = runTest {
        val sessions = sessions()
        val http = NativeHttpClient(MockEngine { respond(placesFixture("day")) }, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
        try {
            assertEquals(HttpOutcome.InvalidResponse, assertFailsWith<PlacesRequestException> {
                NativePlacesApi(http).day("2026-10-08", sessions.lease()!!)
            }.outcome)
        } finally { http.close() }
    }

    @Test fun mapTileCarriesPrivateNativeAuthorityAndCannotRedirectToAnotherServer() = runTest {
        val sessions = sessions()
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        var redirect = false
        var calls = 0
        // Keep the fake producer on the same virtual clock as the production streaming deadline.
        val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                calls++
                assertEquals(FEATURE_ORIGIN + "/v1/maps/osm/4/7/5.png", request.url.toString())
                assertEquals("Bearer ${sessions.lease()!!.credential}", request.headers["Authorization"])
                if (redirect) respond("", HttpStatusCode.Found, headersOf("Location", "https://external.invalid/tile.png"))
                else respond(png, headers = headersOf("Content-Type", "image/png"))
            }
        }), FEATURE_ORIGIN, sessions, { FEATURE_NOW })
        try {
            val api = NativePlacesApi(http)
            assertContentEquals(png, api.tile(4, 7, 5, sessions.lease()!!))
            redirect = true
            assertEquals(302, (assertFailsWith<PlacesRequestException> { api.tile(4, 7, 5, sessions.lease()!!) }.outcome as HttpOutcome.Failure).status)
            assertEquals(2, calls)
        } finally { http.close() }
    }

    @Test fun aTileFinishingAfterLogoutNeverReachesTheVisibleMap() = runTest {
        val sessions = sessions()
        val gate = CompletableDeferred<Unit>()
        val reader = FakePlacesReader().apply { tileGate = gate }
        val controller = PlacesController(backgroundScope, sessions, reader)
        try {
            controller.activate("2026-10-09")
            runCurrent()
            val tile = async { controller.tile(4, 7, 5) }
            runCurrent()
            sessions.expire(sessions.lease()!!)
            gate.complete(Unit)
            assertNull(tile.await())
        } finally { controller.close() }
    }

    private suspend fun sessions(): SessionManager = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(featureRecord()), {}, { FEATURE_NOW }).also { it.restore() }
}

private fun placesFixture(name: String): ByteArray = requireNotNull(PlacesTest::class.java.getResourceAsStream("/places/$name.json")).use { it.readBytes() }
private class FakePlacesReader : PlacesReader {
    var gate: CompletableDeferred<Unit>? = null
    var tileGate: CompletableDeferred<Unit>? = null
    override suspend fun days(lease: SessionLease) = decodePlacesDays(placesFixture("days"))
    override suspend fun day(date: String, lease: SessionLease): PlacesDay {
        gate?.await()
        return decodePlacesDay(placesFixture("day")).copy(date = date)
    }
    override suspend fun tile(z: Int, x: Int, y: Int, lease: SessionLease): ByteArray { tileGate?.await(); return byteArrayOf(1) }
}
