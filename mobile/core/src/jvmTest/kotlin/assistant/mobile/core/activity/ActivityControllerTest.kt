package assistant.mobile.core.activity

import assistant.mobile.core.*
import assistant.mobile.core.journal.*
import assistant.mobile.core.personal.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityControllerTest {
    @Test
    fun manualSessionExerciseSetsEditAndDeleteFollowServerRevisions() = runTest {
        val fixture = ActivityControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.beginSession(kind = "strength")
            fixture.controller.updateSessionDraft(fixture.controller.state.value!!.sessionDraft!!.copy(title = "New strength", duration = "30"))
            fixture.controller.saveSession()
            val added = fixture.controller.state.value!!.session.data!!
            assertEquals("session-new", added.id)
            assertNull(fixture.controller.state.value!!.sessionDraft)
            fixture.controller.addExercise("bench")
            val exercise = fixture.controller.state.value!!.session.data!!.exercises.single()
            fixture.controller.beginSet(exercise.id)
            fixture.controller.updateSetDraft(fixture.controller.state.value!!.setDraft!!.copy(repetitions = "8", loadValue = "20", implementCount = "2"))
            fixture.controller.saveSet()
            val set = fixture.controller.state.value!!.session.data!!.exercises.single().sets.single()
            assertEquals(8, set.repetitions)
            fixture.controller.beginSet(exercise.id, set.id)
            fixture.controller.updateSetDraft(fixture.controller.state.value!!.setDraft!!.copy(repetitions = "10"))
            fixture.controller.saveSet()
            assertEquals(10, fixture.controller.state.value!!.session.data!!.exercises.single().sets.single().repetitions)
            fixture.controller.deleteSet(exercise.id, set.id)
            assertTrue(fixture.controller.state.value!!.session.data!!.exercises.single().sets.isEmpty())
            fixture.controller.deleteExercise(exercise.id)
            assertTrue(fixture.controller.state.value!!.session.data!!.exercises.isEmpty())
            fixture.controller.deleteSession()
            assertNull(fixture.controller.state.value!!.selectedSessionId)
            assertEquals(7, fixture.posts.size)
            assertEquals(7, fixture.writes)
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun lostReceiptLooksUpOriginalKeyWithoutASecondPostOrWrite() = runTest {
        val fixture = ActivityControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.openSession("session-1")
            fixture.controller.beginSession("session-1")
            fixture.controller.updateSessionDraft(fixture.controller.state.value!!.sessionDraft!!.copy(title = "Saved once"))
            fixture.loseNextReceipt = true
            fixture.controller.saveSession()
            val pending = fixture.controller.state.value!!.pending.single()
            assertEquals(SubmissionState.UNCERTAIN, pending.state)
            assertNotNull(fixture.controller.state.value!!.sessionDraft)
            fixture.controller.saveSession()
            assertEquals(1, fixture.posts.size)
            fixture.controller.check(pending.key)
            assertNull(fixture.controller.state.value!!.sessionDraft)
            assertEquals("Saved once", fixture.controller.state.value!!.session.data!!.title)
            assertEquals(listOf(ACTIVITY_LOOKUP + pending.key), fixture.lookups.map { it.url.encodedPath })
            assertEquals(1, fixture.posts.size)
            assertEquals(1, fixture.writes)
            assertTrue(fixture.store.submissions(fixture.namespace).isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun durableDraftsRestoreAndFailedJournalReadCannotPermitAnotherPost() = runTest {
        val fixture = ActivityControllerFixture(this)
        try {
            fixture.start()
            fixture.controller.openSession("session-1")
            fixture.controller.beginSet("performed-1", "set-1")
            fixture.controller.updateSetDraft(fixture.controller.state.value!!.setDraft!!.copy(loadValue = " 22,500 ", note = " kept "))
            // Room persists on its own executor; draining TestDispatcher cannot establish durability.
            fixture.persistedDrafts.first { draft -> draft?.target == "activity/set" &&
                NativeJson.decodeFromString<ActivitySetDraft>(draft.json.decodeToString()).let { it.loadValue == " 22,500 " && it.note == " kept " } }
            val stored = assertNotNull(fixture.store.draft(fixture.namespace, "activity/set"))
            assertEquals(" 22,500 ", NativeJson.decodeFromString<ActivitySetDraft>(stored.json.decodeToString()).loadValue)
            assertEquals(" kept ", NativeJson.decodeFromString<ActivitySetDraft>(stored.json.decodeToString()).note)
            val recreated = fixture.recreate()
            recreated.activate()
            val restored = recreated.state.first { it?.setDraft != null }
            assertEquals(" 22,500 ", restored!!.setDraft!!.loadValue)
            assertEquals(" kept ", restored.setDraft!!.note)
            fixture.failJournalReads = true
            recreated.saveSet()
            assertTrue(fixture.posts.isEmpty())
            assertNotNull(recreated.state.value!!.error)
            assertEquals(" 22,500 ", recreated.state.value!!.setDraft!!.loadValue)
        } finally { fixture.close() }
    }

    @Test
    fun personalPermissionRevocationCannotRestoreAnEarlierInFlightResponse() = runTest {
        val fixture = ActivityControllerFixture(this, personal = true)
        try {
            fixture.start()
            assertNotNull(fixture.controller.state.value!!.overview.data)
            fixture.overviewGate = CompletableDeferred()
            val refreshing = backgroundScope.launch { fixture.controller.refresh() }
            fixture.overviewStarted.first { it }
            fixture.identityScopes = listOf("activity.read", "activity.write")
            fixture.sessions.refreshIdentity()
            runCurrent()
            fixture.overviewGate!!.complete(Unit)
            refreshing.join()
            val state = fixture.controller.state.value!!
            assertFalse(state.canPersonal)
            assertNull(state.overview.data)
            assertNull(state.steps.data)
            assertNull(state.weekSteps.data)
            assertNull(state.runningWorkouts.data)
            assertNull(state.otherWorkouts.data)
        } finally { fixture.close() }
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
private class ActivityControllerFixture(private val test: TestScope, personal: Boolean = false) {
    private val directory = Files.createTempDirectory("native-activity-journal").toFile()
    private val database = openJournalDatabase(directory.resolve("journal.db"))
    private val durable = RoomJournalStore(database)
    var failJournalReads = false
    val persistedDrafts = kotlinx.coroutines.flow.MutableStateFlow<Draft?>(null)
    val store = object : JournalStore by durable {
        override suspend fun saveDraft(draft: Draft) {
            durable.saveDraft(draft)
            persistedDrafts.value = draft
        }
        override suspend fun submissions(namespace: AccountNamespace): List<Submission> {
            if (failJournalReads) throw JournalStorageException(IOException("synthetic unavailable journal"))
            return durable.submissions(namespace)
        }
    }
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilliseconds()
    private val record = featureRecord().let { it.copy(identity = it.identity.copy(timezone = "Europe/Moscow", scopes = listOf("activity.read", "activity.write") + if (personal) listOf("personal.read", "personal.write") else emptyList())) }
    val namespace = record.namespace
    var identityScopes = record.identity.scopes
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, FeatureSessionSecrets(record), store::clearAccount, { now })
    private var savedSession: ActivitySession? = activityTestSession()
    private val catalog = listOf(ExerciseDefinition("bench", 1, "Bench", "Dumbbell", "", "per_implement"))
    private var stepsOverride: ActivityStepsOverride? = null
    val requests = mutableListOf<HttpRequestData>()
    val posts get() = requests.filter { it.method == HttpMethod.Post }
    val lookups get() = requests.filter { it.url.encodedPath.startsWith(ACTIVITY_LOOKUP) || it.url.encodedPath.startsWith(PERSONAL_LOOKUP) }
    var rejectNext = false
    var loseNextReceipt = false
    var writes = 0
    var sessionReads = 0
    var progressReads = 0
    var sessionReadGate: CompletableDeferred<Unit>? = null
    var overviewGate: CompletableDeferred<Unit>? = null
    val overviewStarted = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val receipts = mutableMapOf<String, String>()
    private val http = NativeHttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(test.testScheduler)
        addHandler { request ->
            requests += request
            val path = request.url.encodedPath
            when {
                path.startsWith(ACTIVITY_LOOKUP) || path.startsWith(PERSONAL_LOOKUP) -> receipts[path.substringAfterLast('/')]?.let { respond(it) } ?: respond("{}", HttpStatusCode.NotFound)
                path in setOf(ACTIVITY_OPERATIONS, PERSONAL_OPERATIONS) -> {
                    if (rejectNext) {
                        rejectNext = false
                        savedSession = savedSession!!.let { session -> session.copy(title = "Remote edit", revision = session.revision + 1,
                            exercises = session.exercises.map { it.copy(revision = it.revision + 1, sets = it.sets.map { set -> set.copy(revision = set.revision + 1) }) }) }
                        respond("{}", HttpStatusCode.Conflict)
                    } else {
                        val command = command(request)
                        val payload = command.getValue("payload").jsonObject
                        val kind = command.getValue("kind").jsonPrimitive.content
                        val key = assertNotNull(request.headers["Idempotency-Key"])
                        assertFalse(key in receipts)
                        writes++
                        val expected = command.getValue("expected_revisions").jsonObject.mapValues { it.value.jsonPrimitive.long }
                        val receipt = if (kind.startsWith("personal.steps.")) {
                            val old = stepsOverride
                            assertEquals(old?.let { mapOf(it.id to it.revision) } ?: emptyMap(), expected)
                            stepsOverride = ActivityStepsOverride("override-1", (old?.revision ?: 0) + 1, payload["value"]?.jsonPrimitive?.intOrNull, kind == "personal.steps.set")
                            val override = stepsOverride!!
                            """{"operation_id":"steps-$writes","kind":"$kind","date":${payload.getValue("date")},"override":{"id":"${override.id}","revision":${override.revision},"value":${override.value},"active":${override.active}}}"""
                        } else writeActivity(kind, payload, expected)
                        receipts[key] = receipt
                        if (loseNextReceipt) { loseNextReceipt = false; throw IOException("synthetic receipt lost after commit") }
                        respond(receipt)
                    }
                }
                path == "/v1/me" -> respond(NativeJson.encodeToString(record.identity.copy(scopes = identityScopes)))
                else -> error("Unexpected activity request $path")
            }
        }
    }), FEATURE_ORIGIN, sessions, { now })
    private val reader = object : ActivityReader {
        override suspend fun overview(day: String, lease: SessionLease): PersonalOverview {
            overviewGate?.let { overviewStarted.value = true; it.await() }
            return PersonalOverview(day, "Europe/Moscow", PersonalProfile(namespace.userId, "Europe/Moscow", PersonalDisplayUnits.Metric, null, null, null, 1, "manual"), emptyMap(), null, emptyMap(), emptyList())
        }
        override suspend fun sessions(range: ActivityRange, lease: SessionLease) = ActivitySessions(listOfNotNull(savedSession).filter { it.date in range.from..range.to && (range.kind == null || range.kind == it.kind) })
        override suspend fun summary(range: ActivityRange, lease: SessionLease) = ActivitySummary(range.from, range.to, ActivitySummaryValues(if (savedSession == null) 0 else 1, savedSession?.durationMinutes, 0, emptyMap(), 0), emptyList())
        override suspend fun session(id: String, lease: SessionLease): ActivitySession { sessionReads++; sessionReadGate?.await(); return requireNotNull(savedSession?.takeIf { it.id == id }) }
        override suspend fun exercises(lease: SessionLease) = catalog
        override suspend fun progress(exerciseId: String, range: ActivityRange, lease: SessionLease): ExerciseProgress {
            progressReads++; return ExerciseProgress(catalog.first { it.id == exerciseId }, range.from, range.to, emptyList(), emptyList())
        }
        override suspend fun steps(day: String, lease: SessionLease) = ActivityStepsReply(day, stepsOverride)
    }
    private val coordinator = SubmissionCoordinator(store, http, sessions, { now }, SubmissionReceiptValidator { entry, response ->
        if (!validateActivityStepsSubmissionReceipt(entry, response)) validateActivitySubmissionReceipt(entry, response)
    })
    var controller = makeController()
        private set
    private fun makeController() = ActivityController(test.backgroundScope, sessions, http, coordinator, AccountDrafts(store, sessions, { now }), { now }, reader)
    fun recreate(): ActivityController { controller.deactivate(); controller = makeController(); return controller }
    suspend fun start() {
        sessions.restore(); controller.activate("2026-10-17")
        controller.state.first { it?.sessions?.data != null && !it.sessions.loading }
        controller.refresh()
    }
    fun command(request: HttpRequestData) = NativeJson.parseToJsonElement(featureBody(request.body).decodeToString()).jsonObject

    private fun writeActivity(kind: String, payload: JsonObject, expected: Map<String, Long>): String {
        var session = savedSession ?: activityTestSession().copy(exercises = emptyList())
        val exerciseId = payload["session_exercise_id"]?.jsonPrimitive?.content
        val setId = payload["set_id"]?.jsonPrimitive?.content
        if (kind != "activity.session.add") {
            assertEquals(session.revision, expected[session.id])
            exerciseId?.let { id -> assertEquals(session.exercises.first { it.id == id }.revision, expected[id]) }
            setId?.let { id -> assertEquals(session.exercises.flatMap { it.sets }.first { it.id == id }.revision, expected[id]) }
        }
        var deletedId: String? = null
        var resultExerciseId = exerciseId
        var resultSetId = setId
        when (kind) {
            "activity.session.add", "activity.session.update" -> {
                val baseline = activityTestSessionJson(session)
                val values = JsonObject(baseline + payload.filterKeys { it != "session_id" } + mapOf("id" to JsonPrimitive(if (kind.endsWith(".add")) "session-new" else session.id),
                    "revision" to JsonPrimitive(if (kind.endsWith(".add")) 1 else session.revision + 1), "exercises" to if (kind.endsWith(".add")) JsonArray(emptyList()) else baseline.getValue("exercises")))
                session = decodeActivitySession(values)
            }
            "activity.session.delete" -> { deletedId = session.id; session = session.copy(revision = session.revision + 1) }
            "activity.session_exercise.add" -> {
                val definition = catalog.first { it.id == payload.getValue("exercise_id").jsonPrimitive.content }
                resultExerciseId = "performed-new"
                session = session.copy(revision = session.revision + 1, exercises = session.exercises + SessionExercise(resultExerciseId, 1, definition.id, 0, definition.snapshot, "", emptyList()))
            }
            "activity.session_exercise.delete" -> { deletedId = exerciseId; session = session.copy(revision = session.revision + 1, exercises = session.exercises.filterNot { it.id == exerciseId }) }
            "activity.set.add", "activity.set.update", "activity.set.delete" -> {
                resultSetId = setId ?: "set-new"
                if (kind.endsWith(".delete")) deletedId = setId
                session = session.copy(revision = session.revision + 1, exercises = session.exercises.map { exercise ->
                    if (exercise.id != exerciseId) exercise else {
                        val old = exercise.sets.firstOrNull { it.id == setId }
                        val saved = if (kind.endsWith(".delete")) null else ActivitySet(resultSetId, (old?.revision ?: 0) + 1, old?.position ?: 0,
                            payload.getValue("kind").jsonPrimitive.content, payload["repetitions"]?.jsonPrimitive?.intOrNull, payload["load_value"]?.jsonPrimitive?.contentOrNull,
                            payload.getValue("load_unit").jsonPrimitive.content, payload.getValue("implement_count").jsonPrimitive.int, payload["note"]?.jsonPrimitive?.contentOrNull)
                        exercise.copy(revision = exercise.revision + 1, sets = exercise.sets.filterNot { it.id == setId } + listOfNotNull(saved))
                    }
                })
            }
            else -> error("Unexpected activity operation $kind")
        }
        savedSession = if (kind == "activity.session.delete") null else session
        return activityTestReceipt(kind, session, resultExerciseId, resultSetId, deletedId)
    }
    fun close() { controller.deactivate(); test.runCurrent(); http.close(); database.close(); directory.deleteRecursively() }
}
