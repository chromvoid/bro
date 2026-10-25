package assistant.mobile.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val HOME = "https://previous.synthetic.invalid"
private const val CURRENT = "https://current.synthetic.invalid"
private const val NEXT = "https://next.synthetic.invalid"
private const val INSTANCE_ID = "11111111-1111-4111-8111-111111111111"
private const val OTHER_INSTANCE = "22222222-2222-4222-8222-222222222222"
private const val USER = "33333333-3333-4333-8333-333333333333"
private const val DEVICE = "44444444-4444-4444-8444-444444444444"
private const val NOW = 1_792_152_000_000L
private const val DECIDED_AT = "2026-10-16T12:00:00Z"

private fun moveSession(origin: String = HOME): StoredSession = StoredSession(
    AccountNamespace("native", origin, USER), "synthetic-credential",
    NativeIdentity(USER, "synthetic", "Synthetic person", "ru", "Europe/Moscow", false, 1,
        DEVICE, "2027-10-16T12:00:00Z"), DEVICE, "synthetic-session", "2027-10-16T12:00:00Z",
)

private class MoveSessions(initial: StoredSession = moveSession()) : OriginMoveSessions, SessionSecretStore {
    val values = mutableMapOf((initial.namespace.instance to initial.namespace.origin) to initial)
    var loads = 0
    var saves = 0
    var deletes = 0
    override fun exists(instance: String, origin: String): Boolean = values.containsKey(instance to origin)
    override suspend fun load(instance: String, origin: String): StoredSession? {
        loads++
        return values[instance to origin]
    }
    override suspend fun save(session: StoredSession) {
        saves++
        values[session.namespace.instance to session.namespace.origin] = session
    }
    override suspend fun delete(instance: String, origin: String) {
        deletes++
        values.remove(instance to origin)
    }
}

private class MoveRecords(initial: OriginMoveRecord? = null) : OriginMoveRecordStore {
    var bytes: ByteArray? = initial?.let { NativeJson.encodeToString(it).encodeToByteArray() }
    var reads = 0
    var writes = 0
    var failWrite = false
    override fun read(): OriginMoveRecord? {
        reads++
        return bytes?.let { decodeOriginMoveRecord(it) }
    }
    override fun write(record: OriginMoveRecord) {
        if (failWrite) throw IllegalStateException("Synthetic write interruption")
        bytes = NativeJson.encodeToString(record).encodeToByteArray()
        writes++
    }
}

private data class ProbeRequest(val origin: String, val path: String, val credential: String? = null)

private class MoveProbe : OriginMoveProbe {
    val requests = mutableListOf<ProbeRequest>()
    var configs: (String) -> OriginMoveConfigResult = { OriginMoveConfigResult.Available(INSTANCE_ID) }
    var identity: suspend () -> OriginMoveMeResult = { OriginMoveMeResult.Identity(USER, DEVICE) }
    override suspend fun config(origin: String): OriginMoveConfigResult {
        requests += ProbeRequest(origin, "/v1/client/config")
        return configs(origin)
    }
    override suspend fun me(origin: String, session: StoredSession): OriginMoveMeResult {
        requests += ProbeRequest(origin, "/v1/me", session.credential)
        return identity()
    }
}

private class MoveFixture(
    val sessions: MoveSessions = MoveSessions(),
    val records: MoveRecords = MoveRecords(),
    val probe: MoveProbe = MoveProbe(),
) {
    fun coordinator(current: String = CURRENT, previous: String? = HOME, clockMs: () -> Long = { NOW }) =
        OriginMoveCoordinator(current, previous, sessions, records, probe, clockMs)

    fun assertUntouched(original: StoredSession = moveSession()) {
        assertEquals(0, sessions.saves)
        assertEquals(0, sessions.deletes)
        assertEquals(original, sessions.values["native" to HOME])
    }
}

class OriginMoveTest {
    @Test
    fun noPreviousOriginSelectsCurrentWithoutNetworkOrSessionDecryption() = runTest {
        for (previous in listOf(null, "")) {
            val f = MoveFixture()
            val coordinator = f.coordinator(previous = previous)
            assertEquals(OriginMoveResolution(CURRENT, CURRENT, false), coordinator.resolve())
            assertEquals(coordinator.resolve(), coordinator.checkPending())
            assertTrue(f.probe.requests.isEmpty())
            assertEquals(0, f.sessions.loads)
            assertEquals(0, f.records.writes)
        }
    }

    @Test
    fun verifiedMoveKeepsHomeAndUpdatesServerWithoutChangingSessionStorage() = runTest {
        val f = MoveFixture()
        val original = f.sessions.values["native" to HOME]!!
        val coordinator = f.coordinator()
        assertEquals(OriginMoveResolution(HOME, HOME, true), coordinator.resolve())
        assertEquals(0, f.sessions.loads)
        assertEquals(OriginMoveResolution(HOME, CURRENT, false), coordinator.checkPending())
        assertEquals(HOME, coordinator.homeOrigin)
        assertEquals(CURRENT, coordinator.serverOrigin(HOME))
        assertEquals(NEXT, coordinator.serverOrigin(NEXT))
        val record = f.records.read()!!
        assertEquals(HOME, record.home)
        assertEquals(CURRENT, record.server)
        assertEquals(INSTANCE_ID, record.instanceId)
        assertEquals(OriginMoveState.MOVED, record.state)
        assertEquals(1, f.records.writes)
        assertSame(original, f.sessions.values["native" to HOME])
        f.assertUntouched(original)
        assertEquals(listOf(
            ProbeRequest(CURRENT, "/v1/client/config"),
            ProbeRequest(HOME, "/v1/client/config"),
            ProbeRequest(CURRENT, "/v1/me", original.credential),
        ), f.probe.requests)
        assertTrue(f.probe.requests.filter { it.credential != null }.all { it.origin == CURRENT && it.path == "/v1/me" })
    }

    @Test
    fun differentOldInstanceRejectsBeforeLoadingOrSendingCredential() = runTest {
        val f = MoveFixture()
        f.probe.configs = { OriginMoveConfigResult.Available(if (it == HOME) OTHER_INSTANCE else INSTANCE_ID) }
        val coordinator = f.coordinator()
        assertEquals(OriginMoveResolution(HOME, HOME, false), coordinator.checkPending())
        assertEquals(HOME, coordinator.homeOrigin)
        assertEquals(OriginMoveResolution(CURRENT, CURRENT, false), f.coordinator().resolve())
        assertEquals(OriginMoveState.REJECTED, f.records.read()!!.state)
        assertEquals(0, f.sessions.loads)
        assertEquals(2, f.probe.requests.size)
        assertTrue(f.probe.requests.all { it.credential == null })
        assertEquals(OriginMoveResolution(HOME, HOME, false), coordinator.checkPending())
        assertEquals(2, f.probe.requests.size)
        f.assertUntouched()
    }

    @Test
    fun anotherUserOrDeviceRejectWithoutChangingTheProcessHomeOrOldState() = runTest {
        val rejections = listOf(
            OriginMoveMeResult.Identity("another-user", DEVICE),
            OriginMoveMeResult.Identity(USER, "another-device"),
        )
        for (rejection in rejections) {
            val f = MoveFixture()
            f.probe.identity = { rejection }
            assertEquals(OriginMoveResolution(HOME, HOME, false), f.coordinator().checkPending())
            assertEquals(OriginMoveResolution(CURRENT, CURRENT, false), f.coordinator().resolve())
            assertEquals(OriginMoveState.REJECTED, f.records.read()!!.state)
            assertEquals(1, f.records.writes)
            f.assertUntouched()
        }
    }

    @Test
    fun offlinePendingVerificationRetriesAtFifteenMinutesWithoutChangingTheHome() = runTest {
        for (failedStep in listOf("config", "me")) {
            val f = MoveFixture()
            var now = NOW
            val coordinator = f.coordinator(clockMs = { now })
            f.probe.configs = { if (failedStep == "config" && it == CURRENT) OriginMoveConfigResult.Unavailable
                else OriginMoveConfigResult.Available(INSTANCE_ID) }
            f.probe.identity = { OriginMoveMeResult.Unavailable }
            val pending = OriginMoveResolution(HOME, HOME, true)
            assertEquals(pending, coordinator.checkPending())
            val requests = f.probe.requests.size
            assertEquals(if (failedStep == "config") 1 else 3, requests)
            now += 15 * 60_000 - 1
            assertEquals(pending, coordinator.checkPending())
            assertEquals(requests, f.probe.requests.size)
            now++
            assertEquals(pending, coordinator.checkPending())
            assertEquals(2 * requests, f.probe.requests.size)
            assertNull(f.records.bytes)
            f.probe.configs = { OriginMoveConfigResult.Available(INSTANCE_ID) }
            f.probe.identity = { OriginMoveMeResult.Identity(USER, DEVICE) }
            now += 15 * 60_000
            assertEquals(OriginMoveResolution(HOME, CURRENT, false), coordinator.checkPending())
            assertEquals(2 * requests + 3, f.probe.requests.size)
            assertEquals(1, f.records.writes)
            f.assertUntouched()
        }
    }

    @Test
    fun explicitLogoutKeepsTheProcessHomeBeforeAndAfterACommittedMove() = runTest {
        for (moved in listOf(false, true)) {
            val f = MoveFixture()
            val coordinator = f.coordinator()
            val sessions = SessionManager("native", coordinator.homeOrigin, f.sessions, {}, { NOW })
            val http = NativeHttpClient(MockEngine { respond("{}") }, HOME, sessions, { NOW },
                serverOrigin = { coordinator.serverOrigin(HOME) })
            try {
                assertTrue(sessions.restore() is SessionResult.SignedIn)
                if (moved) coordinator.checkPending()
                assertTrue(sessions.logout() is HttpOutcome.Success)
                assertNull(f.sessions.values["native" to HOME])
                assertEquals(1, f.sessions.deletes)
                assertEquals(HOME, coordinator.homeOrigin)
                assertEquals(OriginMoveResolution(HOME, if (moved) CURRENT else HOME, !moved), coordinator.resolve())
                assertEquals(if (moved) HOME else CURRENT, f.coordinator().homeOrigin)
            } finally { http.close() }
        }
    }

    @Test
    fun committedMoveOrRejectionIsIdempotentAcrossProcesses() = runTest {
        for (state in OriginMoveState.entries) {
            val f = MoveFixture(records = MoveRecords(OriginMoveRecord(HOME, CURRENT, INSTANCE_ID, state, DECIDED_AT)))
            val expected = if (state == OriginMoveState.MOVED) OriginMoveResolution(HOME, CURRENT, false)
                else OriginMoveResolution(CURRENT, CURRENT, false)
            repeat(2) { assertEquals(expected, f.coordinator().checkPending()) }
            if (state == OriginMoveState.MOVED)
                assertEquals(expected, f.coordinator(previous = null).checkPending())
            assertTrue(f.probe.requests.isEmpty())
            assertEquals(0, f.sessions.loads)
            assertEquals(0, f.records.writes)
            f.assertUntouched()
        }
    }

    @Test
    fun interruptionBeforeCommitRetriesAndAfterCommitNeedsNoNetwork() = runTest {
        val f = MoveFixture()
        f.probe.identity = { throw CancellationException("Synthetic process interruption") }
        assertFailsWith<CancellationException> { f.coordinator().checkPending() }
        assertNull(f.records.read())
        f.probe.identity = { OriginMoveMeResult.Identity(USER, DEVICE) }
        f.records.failWrite = true
        assertFailsWith<IllegalStateException> { f.coordinator().checkPending() }
        assertNull(f.records.read())
        f.records.failWrite = false
        assertEquals(OriginMoveResolution(HOME, CURRENT, false), f.coordinator().checkPending())
        val requestsAfterCommit = f.probe.requests.size
        assertEquals(OriginMoveResolution(HOME, CURRENT, false), f.coordinator().checkPending())
        assertEquals(requestsAfterCommit, f.probe.requests.size)
        assertEquals(1, f.records.writes)
        f.assertUntouched()
    }

    @Test
    fun chainKeepsOriginalHomeAndProbesItsPreviouslyConfirmedServer() = runTest {
        val f = MoveFixture(records = MoveRecords(OriginMoveRecord(HOME, CURRENT, INSTANCE_ID, OriginMoveState.MOVED, DECIDED_AT)))
        val coordinator = f.coordinator(current = NEXT, previous = CURRENT)
        assertEquals(OriginMoveResolution(HOME, CURRENT, true), coordinator.resolve())
        assertEquals(CURRENT, coordinator.serverOrigin(HOME))
        assertEquals(OriginMoveResolution(HOME, NEXT, false), coordinator.checkPending())
        assertEquals(NEXT, coordinator.serverOrigin(HOME))
        assertEquals(HOME, f.records.read()!!.home)
        assertEquals(listOf(NEXT, CURRENT, NEXT), f.probe.requests.map { it.origin })
        assertTrue(f.probe.requests.filter { it.credential != null }.all { it.origin == NEXT })
        f.assertUntouched()
    }

    @Test
    fun mismatchedLoadedNamespaceNeverSendsCredential() = runTest {
        for (invalid in listOf(
            moveSession().copy(namespace = AccountNamespace("other-instance", HOME, USER)),
            moveSession(CURRENT),
            moveSession().copy(namespace = AccountNamespace("native", HOME, "another-user")),
            moveSession().copy(deviceId = "another-device"),
            moveSession().copy(credential = "header\r\ninjection"),
        )) {
            val f = MoveFixture()
            f.sessions.values["native" to HOME] = invalid
            assertEquals(OriginMoveResolution(HOME, HOME, true), f.coordinator().checkPending())
            assertEquals(2, f.probe.requests.size)
            assertTrue(f.probe.requests.all { it.credential == null })
            assertNull(f.records.read())
            f.assertUntouched(invalid)
        }
    }

    @Test
    fun currentSessionCreatedDuringProbePreventsStaleMoveCommit() = runTest {
        val f = MoveFixture()
        f.probe.identity = {
            f.sessions.values["native" to CURRENT] = moveSession(CURRENT)
            OriginMoveMeResult.Identity(USER, DEVICE)
        }
        val coordinator = f.coordinator()
        assertEquals(OriginMoveResolution(HOME, HOME, true), coordinator.checkPending())
        assertEquals(HOME, coordinator.homeOrigin)
        assertEquals(OriginMoveResolution(CURRENT, CURRENT, false), f.coordinator().resolve())
        assertNull(f.records.read())
        assertFalse(f.sessions.values.isEmpty())
        f.assertUntouched()
    }

    @Test
    fun deletedOrReplacedHomeSessionDuringProbePreventsAStaleDecisionWithoutChangingHome() = runTest {
        for (deleted in listOf(false, true)) {
            val f = MoveFixture()
            f.probe.identity = {
                if (deleted) f.sessions.values.remove("native" to HOME)
                else f.sessions.values["native" to HOME] = moveSession().let { original ->
                    original.copy(namespace = original.namespace.copy(userId = "another-user"),
                        identity = original.identity.copy(id = "another-user"), credential = "another-credential")
                }
                OriginMoveMeResult.Identity(USER, DEVICE)
            }
            val coordinator = f.coordinator()
            assertEquals(OriginMoveResolution(HOME, HOME, true), coordinator.checkPending())
            assertEquals(HOME, coordinator.homeOrigin)
            assertEquals(HOME, coordinator.serverOrigin(HOME))
            assertEquals(if (deleted) CURRENT else HOME, f.coordinator().homeOrigin)
            assertNull(f.records.bytes)
            assertEquals(3, f.probe.requests.size)
            assertEquals(0, f.sessions.saves)
            assertEquals(0, f.sessions.deletes)
        }
    }
}
