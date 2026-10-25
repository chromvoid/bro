package assistant.mobile.core.location

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.HttpOutcome
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionLease
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureBody
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Position/foreground fixtures mirror position.test.ts, useLocationReporter.test.ts and test_location.py. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationTest {
    private val fix = LocationFix("2026-10-09T18:10:00Z", 55.73812, 37.58004, 39.2)

    @Test fun snapshotAndExplicitPointUseOnlyThePrivateNativeSession() = runTest {
        val sessions = sessions()
        val record = featureRecord()
        val paths = mutableListOf<String>()
        val http = NativeHttpClient(MockEngine { request ->
            assertEquals("Bearer ${record.credential}", request.headers["Authorization"])
            assertEquals(record.namespace.userId, request.headers["X-Expected-User"])
            assertNull(request.headers["Cookie"])
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/me/location" -> respond(locationFixture())
                "/v1/me/location/points" -> {
                    val sent = Json.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject.getValue("points").jsonArray.single().jsonObject
                    assertEquals("40.0", sent.getValue("accuracy_m").jsonPrimitive.content)
                    assertEquals(setOf("captured_at", "latitude", "longitude", "accuracy_m"), sent.keys)
                    respond("{\"accepted\":1,\"duplicates\":0}")
                }
                else -> error("Unexpected location route")
            }
        }, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
        try {
            val api = NativeLocationApi(http)
            assertEquals(800, api.snapshot(sessions.lease()!!).current!!.accuracyMeters)
            assertEquals(LocationAcknowledgement(1, 0), api.sendFix(fix, sessions.lease()!!))
            assertEquals(listOf("/v1/me/location", "/v1/me/location/points"), paths)
        } finally { http.close() }
    }

    @Test fun enrollmentBindsReturnedGrantToTheHumanAccountAndRedactsBearer() = runTest {
        val sessions = sessions()
        var wrongAccount = false
        val http = NativeHttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/me/location/collectors", request.url.encodedPath)
            assertEquals("native-installation", Json.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject.getValue("installation_id").jsonPrimitive.content)
            respond("{\"collector_id\":\"collector\",\"user_id\":\"${if (wrongAccount) "other" else sessions.lease()!!.namespace.userId}\",\"token\":\"private-collector-token\",\"expires_at\":\"2026-11-16T12:00:00Z\"}", HttpStatusCode.Created)
        }, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
        try {
            val api = NativeLocationApi(http)
            val grant = api.enroll("native-installation", sessions.lease()!!)
            assertFalse(grant.toString().contains(grant.token))
            wrongAccount = true
            assertEquals(HttpOutcome.InvalidResponse, assertFailsWith<LocationRequestException> { api.enroll("native-installation", sessions.lease()!!) }.outcome)
        } finally { http.close() }
    }

    @Test fun historyDeletionIsOneExplicitDeleteWithoutAutomaticRetry() = runTest {
        val sessions = sessions()
        var count = 0
        val http = NativeHttpClient(MockEngine { request ->
            count++
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/v1/me/location/history", request.url.encodedPath)
            respond("{\"deleted_points\":14}")
        }, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
        try {
            assertEquals(14, NativeLocationApi(http).deleteHistory(sessions.lease()!!).deletedPoints)
            assertEquals(1, count)
        } finally { http.close() }
    }

    @Test fun reporterDoesNotReadPositionBeforePermissionAndKeepsApproximateAccuracy() = runTest {
        val sessions = sessions()
        val api = FakeLocationApi()
        val position = FakePosition(fix.copy(accuracyMeters = 6000.0))
        val reporter = ForegroundLocationReporter(sessions, api, position)
        for (permission in listOf(PositionPermission.PROMPT, PositionPermission.DENIED, PositionPermission.UNAVAILABLE)) {
            position.permission = PositionStatus(permission)
            reporter.onVisible()
        }
        assertEquals(0, position.reads)
        assertEquals(0, position.requests)
        position.permission = PositionStatus(PositionPermission.GRANTED, PositionPrecision.APPROXIMATE)
        reporter.tick()
        assertEquals(6000.0, api.sent.single().accuracyMeters)
    }

    @Test fun aDelayedFixCannotCrossLogoutIntoAnotherNativeSession() = runTest {
        val sessions = sessions()
        val gate = CompletableDeferred<Unit>()
        val api = FakeLocationApi()
        val source = object : PositionSource {
            override suspend fun status() = PositionStatus(PositionPermission.GRANTED, PositionPrecision.PRECISE)
            override suspend fun request() = status()
            override suspend fun current(): LocationFix { gate.await(); return fix }
        }
        val reporter = ForegroundLocationReporter(sessions, api, source)
        val reporting = async { reporter.onVisible() }
        runCurrent()
        sessions.expire(sessions.lease()!!)
        gate.complete(Unit)
        reporting.await()
        assertTrue(api.sent.isEmpty())
    }

    @Test fun ackAndTerminalFailuresHaveDifferentDecisionsFromRetriableFailures() {
        assertEquals(LocationUploadDisposition.ACK, LocationQueuePolicy.retryDisposition(200))
        assertEquals(LocationUploadDisposition.ACK, LocationQueuePolicy.retryDisposition(204))
        for (status in listOf(401, 403)) assertEquals(LocationUploadDisposition.REVOKE, LocationQueuePolicy.retryDisposition(status))
        for (status in listOf(413, 422)) assertEquals(LocationUploadDisposition.DISCARD, LocationQueuePolicy.retryDisposition(status))
        for (status in listOf(301, 429, 500, 503)) assertEquals(LocationUploadDisposition.RETRY, LocationQueuePolicy.retryDisposition(status))
    }

    private suspend fun sessions(): SessionManager = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(featureRecord()), {}, { FEATURE_NOW }).also { it.restore() }
}

internal fun locationFixture(): String = requireNotNull(LocationTest::class.java.getResourceAsStream("/location/snapshot.json")).use { it.readBytes().decodeToString() }

private class FakePosition(private val fix: LocationFix) : PositionSource {
    var permission = PositionStatus(PositionPermission.GRANTED, PositionPrecision.PRECISE)
    var requests = 0
    var reads = 0
    override suspend fun status() = permission
    override suspend fun request(): PositionStatus { requests++; return permission }
    override suspend fun current(): LocationFix { reads++; return fix }
}
private class FakeLocationApi : LocationApi {
    val sent = mutableListOf<LocationFix>()
    var failure = false
    override suspend fun snapshot(lease: SessionLease) = LocationSnapshot(null, LocationHistory(0, null), LocationBackground(false))
    override suspend fun sendFix(fix: LocationFix, lease: SessionLease): LocationAcknowledgement {
        if (failure) throw LocationRequestException(HttpOutcome.NetworkFailure)
        sent += fix
        return LocationAcknowledgement(1, 0)
    }
    override suspend fun deleteHistory(lease: SessionLease) = LocationDeletion(0)
    override suspend fun enroll(installationId: String, lease: SessionLease): LocationCollectorGrant = error("Only the explicit background button enrolls")
}
