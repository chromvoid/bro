package assistant.mobile.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.AtomicFile
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import assistant.mobile.android.ambient.AmbientBinding
import assistant.mobile.android.ambient.AmbientQueue
import assistant.mobile.android.whoop.WhoopCollectWorker
import assistant.mobile.android.whoop.WhoopStore
import assistant.mobile.core.AccountNamespace
import assistant.mobile.core.NativeServerCheckResult
import assistant.mobile.core.assistant.AssistantHistory
import assistant.mobile.core.assistant.AssistantMessage
import assistant.mobile.core.journal.Draft
import assistant.mobile.core.journal.RoomJournalStore
import assistant.mobile.core.journal.Submission
import assistant.mobile.core.journal.SubmissionState
import assistant.mobile.core.location.LocationCollectorGrant
import assistant.mobile.platform.AndroidNativeOrigins
import assistant.mobile.platform.FileAssistantHistoryCache
import assistant.mobile.platform.NativeCorePlatform
import assistant.mobile.platform.location.CollectorReply
import assistant.mobile.platform.location.CollectorTransport
import assistant.mobile.platform.location.LocationUpdateRegistration
import assistant.mobile.platform.location.NativeLocationCollector
import assistant.mobile.platform.ring.RingStore
import assistant.mobile.platform.ring.RingUploadWorker
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

/** Debug-only process driver: the instrumentation and its fixture server remain alive. */
class NativeServerSwitchSmokeProbe : Service() {
    override fun onBind(intent: Intent): IBinder {
        NativeServerSwitchSmokeFixture.initializeWork(this)
        val runId = requireNotNull(intent.getStringExtra("run_id"))
        require(UUID.fromString(runId).toString() == runId)
        val phase = requireNotNull(intent.getStringExtra("phase"))
        require(phase in setOf("to_custom", "to_default", "stop"))
        val started = AtomicBoolean()
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                check(Binder.getCallingUid() == Process.myUid())
                if (started.compareAndSet(false, true)) {
                    if (phase == "stop") Process.killProcess(Process.myPid())
                    else NativeServerSwitchSmokeFixture.scope.launch {
                        val directory = NativeServerSwitchSmokeFixture.directory(this@NativeServerSwitchSmokeProbe, runId)
                        try {
                            val config = JSONObject(File(directory, "config.json").readText())
                            val previous = AndroidNativeOrigins.homeOrigin(this@NativeServerSwitchSmokeProbe)
                            val target = config.getString(if (phase == "to_custom") "custom_origin" else "default_origin")
                            if (phase == "to_custom") withContext(Dispatchers.IO) {
                                RingStore(this@NativeServerSwitchSmokeProbe, previous).use { it.clearForServerSwitch() }
                                WhoopStore(this@NativeServerSwitchSmokeProbe, previous).use { it.clearForServerSwitch() }
                            }
                            val collector = NativeServerSwitchSmokeFixture.collector(this@NativeServerSwitchSmokeProbe, previous)
                            val runtime = NativeRuntime(this@NativeServerSwitchSmokeProbe, restartApplication = { context, origin, invite ->
                                NativeServerRestart.restart(context, origin, invite, NativeServerSwitchSmokeActivity::class.java)
                            })
                            runtime.ready.first { it }
                            check(runtime.core.sessions.lease() == null)
                            withContext(Dispatchers.IO) {
                                NativeServerSwitchSmokeFixture.seed(this@NativeServerSwitchSmokeProbe, previous, collector)
                                NativeServerSwitchSmokeFixture.write(File(directory, "$phase-seeded.json"),
                                    JSONObject().put("pid", Process.myPid()).put("home", previous))
                                NativeServerSwitchSmokeFixture.write(File(cacheDir, "native-server-switch-smoke/active.json"),
                                    JSONObject().put("run_id", runId).put("phase", phase).put("previous_origin", previous)
                                        .put("expected_origin", target).put("previous_pid", Process.myPid()))
                            }
                            runtime.switchServer(NativeServerCheckResult.Compatible(target,
                                config.getString("instance_id"), "Synthetic switch server"))
                        } catch (failure: Throwable) {
                            NativeServerSwitchSmokeFixture.failure(directory, phase, failure)
                        }
                    }
                }
                reply?.writeNoException()
                return true
            }
        }
    }
}

/** Runs the production MainActivity lifecycle in the fresh process created by the trampoline. */
class NativeServerSwitchSmokeActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        NativeServerSwitchSmokeFixture.initializeWork(this)
        lateinit var created: NativeRuntime
        NativeRuntimeFactory.testFactory = { context -> NativeRuntime(context).also { created = it } }
        try { super.onCreate(savedInstanceState) } finally { NativeRuntimeFactory.testFactory = null }
        lifecycleScope.launch {
            val active = JSONObject(File(cacheDir, "native-server-switch-smoke/active.json").readText())
            val phase = active.getString("phase")
            val directory = NativeServerSwitchSmokeFixture.directory(this@NativeServerSwitchSmokeActivity, active.getString("run_id"))
            try {
                created.ready.first { it }
                repeat(400) { if (!firstScreenComposed) delay(25) }
                check(firstScreenComposed) { "MainActivity did not compose its initial screen" }
                check(Process.myPid() != active.getInt("previous_pid")) { "The old process survived the switch" }
                val origin = AndroidNativeOrigins.homeOrigin(this@NativeServerSwitchSmokeActivity)
                check(origin == active.getString("expected_origin")) { "The restarted home origin is incorrect" }
                check(created.core.sessions.origin == origin)
                withContext(Dispatchers.IO) {
                    NativeServerSwitchSmokeFixture.verify(this@NativeServerSwitchSmokeActivity,
                        active.getString("previous_origin"), origin)
                }
                check(checkNotNull(created.ring).states.value.error?.code != "store_unavailable")
                check(created.whoop.states.value.error?.code != "store_unavailable")
                NativeServerSwitchSmokeFixture.write(File(directory, "$phase-restarted.json"),
                    JSONObject().put("pid", Process.myPid()).put("home", origin)
                        .put("main_activity_composed", true).put("private_stores_readable", true))
            } catch (failure: Throwable) {
                NativeServerSwitchSmokeFixture.failure(directory, phase, failure)
            }
        }
    }
}

internal object NativeServerSwitchSmokeFixture {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private const val USER = "b7c045a2-f7ca-44ad-a7c0-c388f4058a84"
    private const val EXPIRED_USER = "27871360-7b26-4a61-b476-18f318c522c8"
    private const val DEVICE = "fe8582b1-2ecb-4ff0-a152-00dc5c148e4a"
    private val ringTables = listOf("metadata", "revisions", "outbox", "batches", "closed_bindings",
        "closed_metadata", "closed_revisions", "closed_outbox", "closed_batches")
    private val whoopTables = listOf("metadata", "segments", "batches", "closed_bindings",
        "closed_metadata", "closed_segments", "closed_batches")

    fun directory(context: Context, runId: String) = File(context.cacheDir, "native-server-switch-smoke/$runId")

    fun initializeWork(context: Context) {
        try { WorkManager.getInstance(context) }
        catch (_: IllegalStateException) {
            // Startup's provider runs in the APK's main process; this test owns a separate one.
            WorkManager.initialize(context, androidx.work.Configuration.Builder()
                .setDefaultProcessName(context.packageName + ":serverSwitchSmoke").build())
        }
    }

    fun write(file: File, value: JSONObject) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray()); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    fun failure(directory: File, phase: String, failure: Throwable) = write(File(directory, "$phase-failed.json"),
        JSONObject().put("error", failure.javaClass.simpleName).put("detail", failure.message?.take(500)))

    fun collector(context: Context, origin: String) = NativeLocationCollector(context, origin,
        transport = object : CollectorTransport {
            override suspend fun post(path: String, body: JsonObject, token: String) =
                CollectorReply(if (path == NativeLocationCollector.REVOKE) 200 else 503, "{}")
        }, updates = object : LocationUpdateRegistration {
            override fun start() = Unit
            override fun stop() = Unit
        })

    suspend fun seed(context: Context, origin: String, collector: NativeLocationCollector) {
        val namespace = AccountNamespace("native", origin, USER)
        val database = NativeCorePlatform.journal(context)
        try {
            val journal = RoomJournalStore(database)
            for (owner in listOf(namespace, namespace.copy(userId = EXPIRED_USER))) {
                journal.insertSubmission(Submission(owner, UUID.randomUUID().toString(), "/synthetic-switch", null,
                    "POST", "{\"origin\":\"$origin\"}".toByteArray(), "synthetic", emptyMap(), "synthetic-draft",
                    SubmissionState.RESOLVED, System.currentTimeMillis(), state = SubmissionState.UNCERTAIN))
                journal.saveDraft(Draft(owner, "synthetic-draft", origin.toByteArray(), 1, System.currentTimeMillis()))
                journal.writeCursor(owner, "synthetic-cursor")
            }
        } finally { database.close() }
        FileAssistantHistoryCache(context).write(namespace,
            AssistantHistory(listOf(AssistantMessage("synthetic-switch-history", Instant.now().toString(), text = origin)), null))
        val grant = buildJsonObject {
            put("user_id", USER); put("source_id", "synthetic-source"); put("collector_id", "synthetic-collector")
            put("generation", 1)
        }
        RingStore(context, origin).use { store ->
            store.put("grant", grant)
            store.append(listOf(buildJsonObject { put("external_id", "synthetic-closed"); put("origin", origin) }), JsonNull)
            check(store.closeBinding("synthetic_server_switch") != null)
            store.put("grant", grant)
            store.append(listOf(buildJsonObject { put("external_id", "synthetic-current"); put("origin", origin) }), JsonNull)
            store.put("grant", JsonNull)
            store.put("control_recovery", buildJsonObject { put("synthetic_origin", origin) })
            check(store.count() == 1 && store.closedRecordCount() == 1)
        }
        WhoopStore(context, origin).use { store ->
            store.put("grant", grant); check(store.append(listOf(origin.toByteArray())))
            check(store.closeBinding("synthetic_server_switch") != null)
            check(store.append(listOf("current:$origin".toByteArray())))
            check(store.batch(JsonNull, null) != null)
            store.put("synthetic-marker", JsonPrimitive(origin))
            check(store.count() == 1 && store.closedRecordCount() == 1)
        }
        val now = System.currentTimeMillis()
        collector.enable(namespace, "synthetic-installation", LocationCollectorGrant("synthetic-location", USER,
            "synthetic-location-token", Instant.ofEpochMilli(now + 3_600_000).toString()))
        collector.received(listOf(Location("synthetic-switch").apply {
            latitude = 62.12345; longitude = 42.54321; accuracy = 20f; time = System.currentTimeMillis() + 1
        }))
        check(collector.status(namespace).queued == 1)
        AmbientQueue(File(context.filesDir, "ambient-audio")).enqueue(
            AmbientBinding(origin, USER, DEVICE, Instant.ofEpochMilli(now + 3_600_000).toString()),
            UUID.randomUUID().toString(), 0, Instant.ofEpochMilli(now).toString(), ByteArray(640))
        val work = WorkManager.getInstance(context)
        work.enqueue(OneTimeWorkRequestBuilder<RingUploadWorker>().addTag("assistant.ring")
            .setInputData(workDataOf("origin" to origin, "collector" to "synthetic-collector"))
            .setInitialDelay(1, TimeUnit.DAYS).build()).result.get()
        work.enqueue(OneTimeWorkRequestBuilder<WhoopCollectWorker>().addTag("assistant.native.whoop")
            .setInputData(workDataOf("origin" to origin, "collector" to "synthetic-collector"))
            .setInitialDelay(1, TimeUnit.DAYS).build()).result.get()
    }

    suspend fun verify(context: Context, previous: String, current: String) {
        val database = NativeCorePlatform.journal(context)
        try {
            val journal = RoomJournalStore(database)
            for (user in listOf(USER, EXPIRED_USER)) {
                val namespace = AccountNamespace("native", previous, user)
                check(journal.submissions(namespace).isEmpty()) { "The old journal survived" }
                check(journal.draft(namespace, "synthetic-draft") == null) { "The old draft survived" }
                check(journal.readCursor(namespace) == null) { "The old replay cursor survived" }
                check(FileAssistantHistoryCache(context).read(namespace) == null) { "The old history cache survived" }
            }
        } finally { database.close() }
        RingStore(context, current).use { store ->
            for (table in ringTables.filterNot { it == "metadata" })
                check(empty(store.readableDatabase, table)) { "Ring $table survived" }
            check(onlyFreshState(store.readableDatabase)) { "Old ring metadata survived" }
            check(store.get("state")?.toString()?.contains(previous) != true)
            store.put("synthetic-readable", JsonPrimitive(current))
            check(store.get("synthetic-readable") == JsonPrimitive(current)) { "The new ring store is unreadable" }
        }
        WhoopStore(context, current).use { store ->
            for (table in whoopTables.filterNot { it == "metadata" })
                check(empty(store.readableDatabase, table)) { "WHOOP $table survived" }
            check(onlyFreshState(store.readableDatabase)) { "Old WHOOP metadata survived" }
            check(store.get("state")?.toString()?.contains(previous) != true)
            store.put("synthetic-readable", JsonPrimitive(current))
            check(store.get("synthetic-readable") == JsonPrimitive(current)) { "The new WHOOP store is unreadable" }
        }
        val previousLocation = NativeLocationCollector.get(context, previous).status()
        check(!previousLocation.enabled && previousLocation.queued == 0) { "The old location queue survived" }
        val registered = context.getSharedPreferences("native-location-origins", Context.MODE_PRIVATE)
            .getStringSet("origins", emptySet()).orEmpty()
        check(previous !in registered) { "The old location registry survived" }
        val location = NativeLocationCollector.get(context, current).status()
        check(!location.enabled && location.queued == 0)
        val work = WorkManager.getInstance(context)
        for (tag in listOf("assistant.ring", "assistant.native.whoop"))
            check(work.getWorkInfosByTag(tag).get().all { it.state.isFinished }) { "Old collector work survived" }
        val suffix = java.security.MessageDigest.getInstance("SHA-256").digest(previous.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        for (name in listOf("native-location-upload-$suffix", "native-location-periodic-$suffix"))
            check(work.getWorkInfosForUniqueWork(name).get().all { it.state.isFinished }) { "Old location work survived" }
        val ambient = AmbientQueue(File(context.filesDir, "ambient-audio"))
        check(ambient.list().isEmpty() && ambient.snapshot().diskBytes == 0L) { "The old ambient queue survived" }
    }

    private fun empty(database: android.database.sqlite.SQLiteDatabase, table: String) =
        database.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) == 0 }

    private fun onlyFreshState(database: android.database.sqlite.SQLiteDatabase) =
        database.rawQuery("SELECT key FROM metadata", null).use { rows ->
            var fresh = true
            while (rows.moveToNext()) if (rows.getString(0) != "state") fresh = false
            fresh
        }
}
