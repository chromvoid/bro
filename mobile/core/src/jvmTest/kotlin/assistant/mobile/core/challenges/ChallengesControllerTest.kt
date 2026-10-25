package assistant.mobile.core.challenges

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChallengesControllerTest {
    @Test
    fun twoUsersJoinBeforeStartSeeOwnFixedTermsAndOnlyPublicRankingThenLeave() = runTest {
        val f = ChallengesFixture(this)
        try {
            f.start(challenge = CHALLENGE_ID)
            assertNull(f.controller.state.value!!.board.data!!.participation)
            assertTrue(f.controller.join())
            assertEquals(Participation.Pending, f.controller.state.value!!.board.data!!.participation)
            assertNull(f.controller.state.value!!.board.data!!.terms)
            f.replaceAccount(CHALLENGE_PEER)
            assertTrue(f.controller.join())
            f.now = Instant.parse("2026-10-03T01:00:00Z").toEpochMilliseconds()
            f.controller.refresh()
            val peer = f.controller.state.value!!.board.data!!
            assertEquals(Participation.Active, peer.participation)
            assertEquals(ChallengeTerms("2200", "UTC"), peer.terms)
            assertEquals(listOf(1, 1), peer.ranking.map { it.place })
            assertEquals(listOf(2, 2), peer.ranking.map { it.days })
            f.replaceAccount(CHALLENGE_ME)
            val first = f.controller.state.value!!.board.data!!
            assertEquals(ChallengeTerms("2000", "UTC"), first.terms)
            assertEquals(first.ranking, peer.ranking)
            assertFalse(f.controller.state.value!!.joinOpen)
            assertTrue(f.controller.leave())
            assertNull(f.controller.state.value!!.board.data!!.participation)
            assertNull(f.controller.state.value!!.board.data!!.terms)
            assertEquals(listOf(CHALLENGE_PEER), f.controller.state.value!!.board.data!!.ranking.map { it.userId })
            assertFalse(f.controller.join())
            assertEquals(listOf("join", "join", "leave"), f.posts.map { it.url.encodedPath.substringAfterLast('/') })
            assertTrue(f.posts.all { NativeJson.parseToJsonElement(featureBody(it.body).decodeToString()).jsonObject.isEmpty() })
            assertTrue(f.posts.all { it.headers["Idempotency-Key"] != null })
        } finally { f.close() }
    }

    @Test
    fun lostCreateOrParticipationReceiptOnlyRereadsAcrossRestartAndNeverPostsAgain() = runTest {
        val f = ChallengesFixture(this)
        try {
            f.start(space = CHALLENGE_SPACE)
            f.loseReceipt = true
            assertNull(f.controller.create(ChallengeDraft("Days", "2026-10-01", "2026-10-14")))
            val unknownCreate = f.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, unknownCreate.state)
            assertEquals(1, f.posts.size)
            f.recreate()
            f.start(space = CHALLENGE_SPACE)
            f.controller.check(unknownCreate.key)
            assertNull(f.controller.create(ChallengeDraft("Days", "2026-10-01", "2026-10-14")))
            assertEquals(1, f.posts.size)
            f.controller.forget(unknownCreate.key)
            f.start(challenge = CHALLENGE_ID)
            f.loseReceipt = true
            assertFalse(f.controller.join())
            val unknownJoin = f.controller.state.value!!.pending.single()
            assertEquals(Participation.Pending, f.controller.state.value!!.board.data!!.participation)
            f.recreate()
            f.start(challenge = CHALLENGE_ID)
            f.controller.check(unknownJoin.key)
            f.controller.refresh()
            assertFalse(f.controller.join())
            assertEquals(2, f.posts.size)
            assertFalse(f.requests.any { "by-key" in it.url.encodedPath })
            f.controller.forget(unknownJoin.key)
            assertTrue(f.controller.leave())
            assertEquals(3, f.posts.size)
        } finally { f.close() }
    }

    @Test
    fun resourceAccessInvalidationPurgesBoardWhileOldReadWaitsAnd404CannotRestoreIt() = runTest {
        val f = ChallengesFixture(this)
        try {
            f.start(challenge = CHALLENGE_ID)
            f.controller.join()
            f.now = Instant.parse("2026-10-03T01:00:00Z").toEpochMilliseconds()
            f.controller.refresh()
            assertNotNull(f.controller.state.value!!.board.data!!.terms)
            val gate = ChallengeReadGate()
            f.nextGate = gate
            val oldRead = async { f.controller.refresh() }
            gate.started.await()
            f.gone = true
            val invalidation = async { f.controller.invalidate(EventInvalidation.Domains(setOf(EventDomain.Shared))) }
            runCurrent()
            assertNull(f.controller.state.value!!.board.data)
            gate.release.complete(Unit)
            oldRead.await(); invalidation.await()
            assertTrue(f.controller.state.value!!.gone)
            assertNull(f.controller.state.value!!.board.data)
            assertFalse(f.controller.leave())
        } finally { f.close() }
    }

}

private class ChallengeReadGate {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

@OptIn(ExperimentalCoroutinesApi::class)
private class ChallengesFixture(private val test: TestScope) {
    var now = Instant.parse("2026-09-30T21:59:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "UTC", scopes = listOf("challenges.read", "challenges.write"))) }
    val namespace get() = sessions.lease()!!.namespace
    private val secrets = FeatureSessionSecrets(record)
    val store = ChallengesMemoryJournal()
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, { store.clearAccount(it) }, { now })
    val requests = mutableListOf<HttpRequestData>()
    val posts get() = requests.filter { it.method == HttpMethod.Post }
    val admitted = mutableListOf<Submission>()
    var withoutGoal = emptySet<String>()
    var loseReceipt = false
    var rejectJoin = false
    var gone = false
    var denied = false
    var nextGate: ChallengeReadGate? = null
    private val joined = mutableSetOf<String>()
    private val challenge = NativeJson.parseToJsonElement(challengeFixture("server-board").decodeToString()).jsonObject.getValue("challenge")
    private fun board(user: String): String {
        val started = now >= Instant.parse("2026-09-30T22:00:00Z").toEpochMilliseconds()
        val participation = if (user !in joined) null else if (!started) "pending" else if (user in withoutGoal) "no_goal" else "active"
        val ranking = if (!started) JsonArray(emptyList()) else JsonArray(joined.filterNot { it in withoutGoal }.sorted().map { id -> buildJsonObject {
            put("user_id", id); put("name", if (id == CHALLENGE_ME) "Synthetic first" else "Synthetic second")
            put("days", 2); put("streak", 2); put("place", 1); put("version", 1)
        } })
        return buildJsonObject {
            put("challenge", challenge); put("participation", participation?.let(::JsonPrimitive) ?: JsonNull)
            put("terms", if (participation == "active") buildJsonObject { put("calorie_target", if (user == CHALLENGE_ME) 2000 else 2200); put("timezone", "UTC") } else JsonNull)
            put("ranking", ranking)
        }.toString()
    }
    private val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            val user = request.headers["X-Expected-User"] ?: CHALLENGE_ME
            when {
                request.method == HttpMethod.Get && path == "/v1/me" -> respond(NativeJson.encodeToString(secrets.value!!.identity))
                request.method == HttpMethod.Get && path == CHALLENGES_PATH -> {
                    assertEquals(CHALLENGE_SPACE, request.url.parameters["space_id"])
                    if (gone) respond("{}", HttpStatusCode.NotFound)
                    else if (denied) respond("{}", HttpStatusCode.Forbidden)
                    else respond(challengeFixture("web-list").decodeToString())
                }
                request.method == HttpMethod.Get && path == challengePath(CHALLENGE_ID) -> {
                    val snapshot = board(user)
                    nextGate?.also { nextGate = null; it.started.complete(Unit); it.release.await() }
                    if (gone) respond("{}", HttpStatusCode.NotFound)
                    else if (denied) respond("{}", HttpStatusCode.Forbidden)
                    else respond(snapshot)
                }
                request.method == HttpMethod.Post && isChallengeWriteEndpoint(path) -> {
                    val key = requireNotNull(request.headers["Idempotency-Key"])
                    admitted += requireNotNull(store.submission(sessions.lease()!!.namespace, key)).also { assertEquals(SubmissionState.SENDING, it.state) }
                    if (rejectJoin && path.endsWith("/join")) {
                        rejectJoin = false; now = Instant.parse("2026-10-03T01:00:00Z").toEpochMilliseconds()
                        respond("{\"message\":\"Участие закрыто после начала\"}", HttpStatusCode.Conflict)
                    } else {
                        if (path.endsWith("/join")) joined += user
                        if (path.endsWith("/leave")) joined -= user
                        if (loseReceipt) { loseReceipt = false; throw IOException("Synthetic receipt lost after commit") }
                        when {
                            path == CHALLENGES_PATH -> respond("{\"id\":\"$CHALLENGE_ID\"}", HttpStatusCode.Created)
                            path.endsWith("/join") -> respond("{\"joined\":true}")
                            else -> respond("{\"joined\":false}")
                        }
                    }
                }
                else -> error("Unexpected challenge request: ${request.method.value} $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val submissions = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator(::validateChallengeSubmissionReceipt))
    var controller = newController()
        private set
    private fun newController() = ChallengesController(test.backgroundScope, sessions, http, submissions, { now })
    suspend fun start(space: String? = null, challenge: String? = null) {
        if (sessions.lease() == null) sessions.restore()
        controller.activate(space, challenge)
        test.runCurrent()
        controller.refresh()
    }
    suspend fun replaceAccount(user: String) {
        secrets.value = record.copy(namespace = record.namespace.copy(userId = user), identity = record.identity.copy(id = user))
        sessions.restore()
        test.runCurrent()
        start(challenge = CHALLENGE_ID)
    }
    fun recreate() { controller.close(); test.runCurrent(); controller = newController() }
    fun close() { controller.close(); test.runCurrent(); http.close() }
}

private class ChallengesMemoryJournal : JournalStore {
    private val entries = mutableMapOf<Pair<AccountNamespace, String>, Submission>()
    override suspend fun insertSubmission(entry: Submission) { check(entries.putIfAbsent(entry.namespace to entry.key, entry) == null) }
    override suspend fun saveSubmission(entry: Submission) { check(entries.containsKey(entry.namespace to entry.key)); entries[entry.namespace to entry.key] = entry }
    override suspend fun submission(namespace: AccountNamespace, key: String) = entries[namespace to key]
    override suspend fun submissions(namespace: AccountNamespace) = entries.values.filter { it.namespace == namespace }
    override suspend fun deleteSubmission(namespace: AccountNamespace, key: String) { entries.remove(namespace to key) }
    override suspend fun saveDraft(draft: Draft) = Unit
    override suspend fun draft(namespace: AccountNamespace, target: String): Draft? = null
    override suspend fun deleteDraft(namespace: AccountNamespace, target: String) = Unit
    override suspend fun readCursor(namespace: AccountNamespace): String? = null
    override suspend fun writeCursor(namespace: AccountNamespace, cursor: String) = Unit
    override suspend fun clearAccount(namespace: AccountNamespace) { entries.keys.removeAll { it.first == namespace } }
}
