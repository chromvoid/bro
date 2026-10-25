package assistant.mobile.platform.ring

import assistant.mobile.platform.R

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Independent encrypted journal. An observation is committed before the caller may display it. */
class RingWorkoutStore(private val context: Context, private val origin: String) :
    SQLiteOpenHelper(context, "ring-workouts-${sha256(origin)}.sqlite", null, 2) {
    private val namespace = sha256(origin)
    private val crypto = RingCrypto("assistant.ring.workouts.$namespace")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE cleanup (key TEXT PRIMARY KEY, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE sessions (identity TEXT PRIMARY KEY, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE gaps (id INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY AUTOINCREMENT, identity TEXT NOT NULL UNIQUE, session TEXT NOT NULL, binding TEXT NOT NULL, category TEXT NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX points_session ON points(session,id)")
        db.execSQL("CREATE INDEX gaps_session ON gaps(session,id)")
        db.execSQL("CREATE INDEX records_binding ON records(binding,category,id)")
        createTrackTables(db)
    }
    private fun createTrackTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE track_points (id INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE track_gaps (id INTEGER PRIMARY KEY AUTOINCREMENT, session TEXT NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX track_points_session ON track_points(session,id)")
        db.execSQL("CREATE INDEX track_gaps_session ON track_gaps(session,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == 1 && newVersion == 2) { "Unsupported workout store version" }
        // Existing authenticated ciphertext, HR sequence, terminal ACK and usage remain intact.
        createTrackTables(db)
    }
    private fun seal(value: JsonElement, tag: String) = crypto.seal(value.toString().toByteArray(Charsets.UTF_8), "$namespace:$tag")
    private fun open(value: ByteArray, tag: String): JsonObject = ringJson.parseToJsonElement(
        crypto.open(value, "$namespace:$tag").toString(Charsets.UTF_8)) as JsonObject
    private fun binding(value: RingWorkoutBinding) = obj {
        put("origin", value.origin); put("user_id", value.userId); put("source_id", value.sourceId)
        put("collector_id", value.collectorId); put("address", value.address)
        put("grant_generation", value.grantGeneration); put("timezone", value.timezone)
    }
    private fun binding(value: JsonObject) = RingWorkoutBinding(value.string("origin")!!, value.string("user_id")!!,
        value.string("source_id")!!, value.string("collector_id")!!, value.string("address")!!,
        value["grant_generation"]!!.jsonPrimitive.long, value.string("timezone")!!)
    private fun clock(value: RingWorkoutClock, precise: Boolean = true) = obj {
        put("utc_at", value.utcAt.toString()); put("elapsed_realtime_ms", value.elapsedRealtimeMs); put("boot_id", value.bootId)
        if (precise) put("elapsed_realtime_nanos", value.elapsedRealtimeNanos)
    }
    private fun clock(value: JsonObject) = RingWorkoutClock(Instant.parse(value.string("utc_at")!!),
        value["elapsed_realtime_ms"]!!.jsonPrimitive.long, value.string("boot_id")!!,
        value["elapsed_realtime_nanos"]?.jsonPrimitive?.longOrNull ?: value["elapsed_realtime_ms"]!!.jsonPrimitive.long * 1_000_000)
    private fun session(value: RingWorkoutSession) = obj {
        put("id", value.id); put("binding", binding(value.binding)); put("anchor", clock(value.anchor))
        put("start_elapsed_ms", value.startElapsedMs); put("trigger", value.trigger); put("status", value.status)
        put("last_observed_elapsed_ms", value.lastObservedElapsedMs)
        put("last_observed_elapsed_nanos", value.lastObservedElapsedNanos)
        put("heart_rate_requested", value.heartRateRequested); put("heart_rate_lost", value.heartRateLost)
        put("gps_requested", value.gpsRequested)
        value.sportPlan?.let { put("sport_plan", it.json()) }; put("sport_requested", value.sportRequested)
        value.pausedAtElapsedMs?.let { put("paused_at_elapsed_ms", it) }
        put("pauses", JsonArray(value.pauses.map { pause -> obj { put("start_ms", pause.startElapsedMs); put("end_ms", pause.endElapsedMs) } }))
        value.runEndElapsedMs?.let { put("run_end_elapsed_ms", it) }
        value.recoveryEndElapsedMs?.let { put("recovery_end_elapsed_ms", it) }
        value.stopReason?.let { put("stop_reason", it) }
    }
    private fun session(value: JsonObject) = RingWorkoutSession(value.string("id")!!,
        binding(value["binding"] as JsonObject), clock(value["anchor"] as JsonObject),
        value["start_elapsed_ms"]!!.jsonPrimitive.long, value.string("trigger")!!,
        value["run_end_elapsed_ms"]?.jsonPrimitive?.longOrNull, value["recovery_end_elapsed_ms"]?.jsonPrimitive?.longOrNull,
        value.string("stop_reason"), value.string("status")!!, value["last_observed_elapsed_ms"]!!.jsonPrimitive.long,
        value["last_observed_elapsed_nanos"]?.jsonPrimitive?.longOrNull ?: value["last_observed_elapsed_ms"]!!.jsonPrimitive.long * 1_000_000,
        value.bool("heart_rate_requested") ?: true, value.bool("heart_rate_lost") ?: false,
        (value["sport_plan"] as? JsonObject)?.let { RingSportPlan.from(it) }, value.bool("sport_requested") ?: (value.bool("heart_rate_requested") ?: true),
        value["paused_at_elapsed_ms"]?.jsonPrimitive?.longOrNull,
        (value["pauses"] as? JsonArray)?.map { pause -> (pause as JsonObject).let {
            RingWorkoutPause(it["start_ms"]!!.jsonPrimitive.long, it["end_ms"]!!.jsonPrimitive.long)
        } } ?: emptyList(), value.bool("gps_requested") ?: true)
    private fun state(value: RingWorkoutState) = obj {
        put("phase", value.phase); value.session?.let { put("session", session(it)) }
        put("auto_blocked", value.autoBlocked); put("command_unknown", value.commandUnknown)
        value.lastTransitionElapsedMs?.let { put("last_transition_elapsed_ms", it) }
        value.transitionBootId?.let { put("transition_boot_id", it) }
        value.pendingCommand?.let { put("pending_command", it.name) }
        value.lastTransitionNanos?.let { put("last_transition_nanos", it) }
        put("last_transition_kinds", JsonArray(value.lastTransitionKinds.map { JsonPrimitive(it.name) }))
        value.queuedEnter?.let { candidate -> put("queued_enter", obj {
            put("binding", binding(candidate.binding)); put("elapsed_ms", candidate.elapsedMs)
            put("elapsed_nanos", candidate.elapsedNanos)
            put("record_heart_rate", candidate.recordHeartRate)
            put("record_gps", candidate.recordGps)
            put("boot_id", candidate.bootId); put("session_id", candidate.sessionId)
        }) }
    }
    private fun state(value: JsonObject) = RingWorkoutState(value.string("phase")!!,
        (value["session"] as? JsonObject)?.let(::session), value.bool("auto_blocked") ?: false,
        value["last_transition_elapsed_ms"]?.jsonPrimitive?.longOrNull, value.string("transition_boot_id"),
        value.string("pending_command")?.let(RingWorkoutCommand::valueOf), value.bool("command_unknown") ?: false,
        value["last_transition_nanos"]?.jsonPrimitive?.longOrNull,
        (value["last_transition_kinds"] as? JsonArray)?.map { RingRunningTransition.Kind.valueOf(it.jsonPrimitive.content) }?.toSet() ?: emptySet(),
        (value["queued_enter"] as? JsonObject)?.let { candidate -> RingWorkoutEnterCandidate(binding(candidate["binding"] as JsonObject),
            candidate["elapsed_ms"]!!.jsonPrimitive.long, candidate.string("boot_id")!!, candidate.string("session_id")!!,
            candidate["elapsed_nanos"]?.jsonPrimitive?.longOrNull ?: candidate["elapsed_ms"]!!.jsonPrimitive.long * 1_000_000,
            candidate.bool("record_heart_rate") ?: true, candidate.bool("record_gps") ?: true) })
    @Synchronized fun load(): RingWorkoutState {
        cleanup()?.let { return it }
        return readableDatabase.rawQuery("SELECT payload FROM metadata WHERE key='state'", null).use {
            if (it.moveToFirst()) state(open(it.getBlob(0), "state")) else RingWorkoutState()
        }
    }
    private fun cleanup(): RingWorkoutState? = readableDatabase.rawQuery("SELECT payload FROM cleanup WHERE key='pending_stop'", null).use {
        if (it.moveToFirst()) state(open(it.getBlob(0), "pending_stop")) else null
    }
    private fun writeCleanup(value: RingWorkoutState) {
        val row = writableDatabase.insertWithOnConflict("cleanup", null, ContentValues().apply {
            put("key", "pending_stop"); put("payload", seal(state(value), "pending_stop"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (row == -1L) throw RingFailure("store_unavailable", RingText.text(R.string.ring_workout_57a162c7aa87))
    }
    private fun writeState(value: RingWorkoutState) {
        val row = writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", "state"); put("payload", seal(state(value), "state"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (row == -1L) throw RingFailure("store_unavailable", RingText.text(R.string.ring_workout_cbb7e0ff2dae))
    }
    private fun journal(id: String): JsonObject? = journalIdentity(sha256(id))
    private fun journalIdentity(identity: String): JsonObject? = readableDatabase.rawQuery("SELECT payload FROM sessions WHERE identity=?", arrayOf(identity)).use {
        if (it.moveToFirst()) open(it.getBlob(0), "session:$identity") else null
    }
    private fun writeJournal(id: String, value: JsonObject) {
        val identity = sha256(id)
        val row = writableDatabase.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("identity", identity); put("payload", seal(value, "session:$identity"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (row == -1L) throw RingFailure("store_unavailable", RingText.text(R.string.ring_workout_ed3d9e2c8615))
    }

    /** Must complete before submitting START/STOP. Unknown commands cannot be replaced by a new session. */
    @Synchronized fun save(value: RingWorkoutState) = transaction {
        val old = load()
        if (old.commandUnknown && old.session?.id != value.session?.id)
            throw RingFailure("workout_unknown", RingText.text(R.string.ring_workout_d1673e7ebce9))
        val current = value.session
        if (current != null && current.binding.origin != origin)
            throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_a49928f1845c))
        if (old.phase !in setOf("idle", "unavailable") && old.session != null && current != null && old.session.id != current.id)
            throw RingFailure("workout_busy", RingText.text(R.string.ring_workout_26ddcf9c9c06))
        if (old.session != null && current != null && old.session.id == current.id &&
            (old.session.binding != current.binding || old.session.anchor != current.anchor || old.session.startElapsedMs != current.startElapsedMs))
            throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_2457c5cc45d3))
        if (old.session?.id == current?.id && old.session != null && current != null) {
            val before = old.session.sportPlan; val after = current.sportPlan
            if (before != null && (after == null || before.requestedId != after.requestedId || before.name != after.name ||
                before.family != after.family || before.candidateWireMode != after.candidateWireMode ||
                before.resolvedActivityId != after.resolvedActivityId || before.mappingBasis != after.mappingBasis ||
                before.actualWireMode != null && before.actualWireMode != after.actualWireMode))
                throw RingFailure("workout_plan_changed", RingText.text(R.string.ring_workout_79621bef8dea))
            if (current.pauses.take(old.session.pauses.size) != old.session.pauses)
                throw RingFailure("workout_pause_changed", RingText.text(R.string.ring_workout_9dd1a8dc5b82))
        }
        val merged = if (current != null && old.session?.id == current.id) value.copy(session = current.copy(
            lastObservedElapsedMs = maxOf(current.lastObservedElapsedMs, old.session.lastObservedElapsedMs),
            lastObservedElapsedNanos = maxOf(current.lastObservedElapsedNanos, old.session.lastObservedElapsedNanos))) else value
        // Forgotten measurements stay forgotten. Only a known stop on this same binding clears its fence.
        if (cleanup() != null) {
            if (merged.phase == "idle" && merged.pendingCommand == null && !merged.commandUnknown) {
                writableDatabase.delete("cleanup", null, null)
                writeState(merged.copy(session = null))
            } else writeCleanup(merged)
            return@transaction
        }
        writeState(merged)
        merged.session?.let { s ->
            if (journal(s.id) == null) writeJournal(s.id, obj {
                put("next_sequence", 0); put("terminal_acknowledged", false); put("binding", binding(s.binding))
                put("last_encoded_end_ms", s.startElapsedMs)
            })
            val previousPauses = old.session?.takeIf { it.id == s.id }?.pauses?.size ?: 0
            s.pauses.drop(previousPauses).forEach { pause -> persistPauseGaps(s, pause) }
            if (merged.phase in setOf("idle", "interrupted") && s.runEndElapsedMs != null) {
                sealPending(s)
                sealTrackPending(s)
                if (journal(s.id)?.bool("terminal_acknowledged") != true) addRecord(s, "terminal", terminal(s))
            }
        }
    }

    /** Closed pause intervals are authenticated gaps in both independent streams, never interpolated points. */
    private fun persistPauseGaps(s: RingWorkoutSession, pause: RingWorkoutPause) {
        val j = journal(s.id)!!
        for (track in listOf(false, true)) {
            if (if (track) !s.gpsRequested else !s.heartRateRequested) continue
            val watermark = j[if (track) "track_encoded_end_ms" else "last_encoded_end_ms"]?.jsonPrimitive?.longOrNull ?: s.startElapsedMs
            val gaps = if (track) trackGaps(s.id) else pendingGaps(s.id)
            val pointsEnd = if (track) trackPoints(s.id).lastOrNull()?.elapsedRealtimeMs?.plus(1)
                else pendingPoints(s.id).lastOrNull()?.elapsedRealtimeMs?.plus(1)
            val start = maxOf(pause.startElapsedMs, watermark, gaps.lastOrNull()?.endElapsedMs ?: s.startElapsedMs, pointsEnd ?: s.startElapsedMs)
            if (start >= pause.endElapsedMs) continue
            val label = if (track) "track-gap" else "gap"
            val payload = seal(obj { put("start_ms", start); put("end_ms", pause.endElapsedMs); put("reason", "paused") }, "$label:${sha256(s.id)}")
            if (track) ensureTrackCapacity(payload.size) else ensureCapacity(payload.size)
            writableDatabase.insertOrThrow(if (track) "track_gaps" else "gaps", null,
                ContentValues().apply { put("session", sha256(s.id)); put("payload", payload) })
            addUsage(1, payload.size.toLong())
        }
    }

    @Synchronized fun appendPoint(binding: RingWorkoutBinding, clock: RingWorkoutClock, bpm: Int, quality: String = "valid"): Boolean = transaction {
        val value = load(); val s = acceptingSession(value, binding, clock) ?: return@transaction false
        val point = RingWorkoutPoint(clock.elapsedRealtimeMs, bpm, quality, clock.elapsedRealtimeNanos)
        val j = journal(s.id)!!
        val encodedEnd = j["last_encoded_end_ms"]!!.jsonPrimitive.long
        if (point.elapsedRealtimeMs < s.startElapsedMs || point.elapsedRealtimeMs < encodedEnd ||
            point.elapsedRealtimeNanos < s.lastObservedElapsedNanos) return@transaction false
        val gaps = pendingGaps(s.id)
        if (gaps.any { point.elapsedRealtimeMs >= it.startElapsedMs && point.elapsedRealtimeMs < it.endElapsedMs }) return@transaction false
        val payload = obj { put("elapsed_ms", point.elapsedRealtimeMs); put("elapsed_nanos", point.elapsedRealtimeNanos); put("bpm", point.bpm); put("quality", point.quality) }
        val sealed = seal(payload, "point:${sha256(s.id)}")
        ensureCapacity(sealed.size)
        writableDatabase.insertOrThrow("points", null, ContentValues().apply { put("session", sha256(s.id)); put("payload", sealed) })
        addUsage(1, sealed.size.toLong())
        val updated = s.copy(lastObservedElapsedMs = maxOf(s.lastObservedElapsedMs, point.elapsedRealtimeMs), lastObservedElapsedNanos = point.elapsedRealtimeNanos)
        writeState(value.copy(session = updated))
        val points = pendingPoints(s.id)
        if (points.count { it.elapsedRealtimeMs == point.elapsedRealtimeMs } > RingWorkoutChunker.MAX_POINTS)
            throw RingFailure("workout_sample_overflow", RingText.text(R.string.ring_workout_c44f9958d071))
        if (points.size > RingWorkoutChunker.MAX_POINTS || point.elapsedRealtimeMs - points.first().elapsedRealtimeMs >= RingWorkoutChunker.MAX_DURATION_MS)
            sealPending(updated, point.elapsedRealtimeMs)
        true
    }

    @Synchronized fun appendGap(binding: RingWorkoutBinding, startClock: RingWorkoutClock, endElapsedMs: Long, reason: String): Boolean = transaction {
        val value = load(); val s = acceptingSession(value, binding, startClock) ?: return@transaction false
        val gap = RingWorkoutGap(startClock.elapsedRealtimeMs, endElapsedMs, reason)
        val encodedEnd = journal(s.id)!!["last_encoded_end_ms"]!!.jsonPrimitive.long
        if (gap.startElapsedMs < s.startElapsedMs || gap.startElapsedMs < encodedEnd ||
            pendingGaps(s.id).lastOrNull()?.let { it.endElapsedMs > gap.startElapsedMs } == true ||
            pendingPoints(s.id).any { it.elapsedRealtimeMs >= gap.startElapsedMs && it.elapsedRealtimeMs < gap.endElapsedMs }) return@transaction false
        val payload = obj { put("start_ms", gap.startElapsedMs); put("end_ms", gap.endElapsedMs); put("reason", gap.reason) }
        val sealed = seal(payload, "gap:${sha256(s.id)}")
        ensureCapacity(sealed.size)
        writableDatabase.insertOrThrow("gaps", null, ContentValues().apply { put("session", sha256(s.id)); put("payload", sealed) })
        addUsage(1, sealed.size.toLong())
        val updated = s.copy(lastObservedElapsedMs = maxOf(s.lastObservedElapsedMs, gap.endElapsedMs - 1),
            lastObservedElapsedNanos = maxOf(s.lastObservedElapsedNanos, (gap.endElapsedMs - 1) * 1_000_000))
        writeState(value.copy(session = updated))
        if (gap.endElapsedMs - gap.startElapsedMs >= RingWorkoutChunker.MAX_DURATION_MS || pendingGaps(s.id).size >= 300)
            sealPending(updated, gap.endElapsedMs)
        true
    }
    private fun acceptingSession(value: RingWorkoutState, binding: RingWorkoutBinding, clock: RingWorkoutClock): RingWorkoutSession? {
        val s = value.session ?: return null
        return s.takeIf { value.phase in setOf("recording", "grace", "recovery") && value.pendingCommand == null &&
            !value.commandUnknown && it.heartRateRequested && !it.heartRateLost &&
            it.binding == binding && it.binding.origin == origin && it.anchor.bootId == clock.bootId }
    }

    private fun trackSession(value: RingWorkoutState, binding: RingWorkoutBinding, clock: RingWorkoutClock): RingWorkoutSession? =
        value.session?.takeIf { it.gpsRequested && value.phase in setOf("starting", "recording", "grace", "recovery", "unknown") &&
            it.binding == binding && it.binding.origin == origin && it.anchor.bootId == clock.bootId }
    @Synchronized fun appendTrackPoint(binding: RingWorkoutBinding, clock: RingWorkoutClock, point: RingTrackPoint): Boolean = transaction {
        val state = load(); val s = trackSession(state, binding, clock) ?: return@transaction false
        if (s.pausedAtElapsedMs != null) return@transaction false
        if (point.elapsedRealtimeNanos != clock.elapsedRealtimeNanos || point.elapsedRealtimeMs < s.startElapsedMs) return@transaction false
        if (s.recoveryEndElapsedMs?.let { point.elapsedRealtimeMs >= it } == true) return@transaction false
        val j = journal(s.id)!!
        val watermark = j["track_encoded_end_ms"]?.jsonPrimitive?.longOrNull ?: s.startElapsedMs
        val previous = trackPoints(s.id)
        if (point.elapsedRealtimeMs < watermark || previous.any { it.elapsedRealtimeNanos == point.elapsedRealtimeNanos } ||
            trackGaps(s.id).any { point.elapsedRealtimeMs >= it.startElapsedMs && point.elapsedRealtimeMs < it.endElapsedMs }) return@transaction false
        val payload = seal(trackPoint(point), "track-point:${sha256(s.id)}")
        ensureTrackCapacity(payload.size)
        writableDatabase.insertOrThrow("track_points", null, ContentValues().apply { put("session", sha256(s.id)); put("payload", payload) })
        addUsage(1, payload.size.toLong())
        val updated = s.copy(lastObservedElapsedMs = maxOf(s.lastObservedElapsedMs, point.elapsedRealtimeMs))
        writeState(state.copy(session = updated)) // Never advance lastObservedElapsedNanos: that is the existing HR fence.
        if (previous.size + 1 >= RingTrackChunker.MAX_POINTS ||
            point.elapsedRealtimeMs - (previous.firstOrNull()?.elapsedRealtimeMs ?: point.elapsedRealtimeMs) >= RingTrackChunker.MAX_DURATION_MS)
            sealTrackPending(updated)
        true
    }
    @Synchronized fun appendTrackGap(binding: RingWorkoutBinding, clock: RingWorkoutClock, endElapsedMs: Long, reason: String): Boolean = transaction {
        val state = load(); val s = trackSession(state, binding, clock) ?: return@transaction false
        val end = minOf(endElapsedMs, s.recoveryEndElapsedMs ?: endElapsedMs)
        if (end <= clock.elapsedRealtimeMs) return@transaction false
        val gap = RingWorkoutGap(clock.elapsedRealtimeMs, end, reason)
        require(reason.length <= 64)
        val watermark = journal(s.id)!!["track_encoded_end_ms"]?.jsonPrimitive?.longOrNull ?: s.startElapsedMs
        if (gap.startElapsedMs < watermark || gap.startElapsedMs < s.startElapsedMs ||
            trackGaps(s.id).lastOrNull()?.let { it.endElapsedMs > gap.startElapsedMs } == true ||
            trackPoints(s.id).any { it.elapsedRealtimeMs >= gap.startElapsedMs && it.elapsedRealtimeMs < gap.endElapsedMs }) return@transaction false
        val payload = seal(obj { put("start_ms", gap.startElapsedMs); put("end_ms", gap.endElapsedMs); put("reason", reason) }, "track-gap:${sha256(s.id)}")
        ensureTrackCapacity(payload.size)
        writableDatabase.insertOrThrow("track_gaps", null, ContentValues().apply { put("session", sha256(s.id)); put("payload", payload) })
        addUsage(1, payload.size.toLong())
        val updated = s.copy(lastObservedElapsedMs = maxOf(s.lastObservedElapsedMs, end - 1))
        writeState(state.copy(session = updated))
        if (trackGaps(s.id).size >= RingTrackChunker.MAX_POINTS || endElapsedMs - gap.startElapsedMs >= RingTrackChunker.MAX_DURATION_MS)
            sealTrackPending(updated)
        true
    }
    private fun trackPoint(point: RingTrackPoint) = obj {
        put("elapsed_nanos", point.elapsedRealtimeNanos); put("latitude", point.latitude); put("longitude", point.longitude)
        put("accuracy_meters", point.accuracyMeters); put("quality", point.quality); point.provider?.let { put("provider", it) }
    }
    private fun trackPoints(id: String): List<RingTrackPoint> = readableDatabase.rawQuery(
        "SELECT payload FROM track_points WHERE session=?", arrayOf(sha256(id))).use { cursor ->
        buildList { while (cursor.moveToNext()) {
            val point = open(cursor.getBlob(0), "track-point:${sha256(id)}")
            add(RingTrackPoint(point["elapsed_nanos"]!!.jsonPrimitive.long, point["latitude"]!!.jsonPrimitive.double,
                point["longitude"]!!.jsonPrimitive.double, point["accuracy_meters"]!!.jsonPrimitive.double,
                point.string("quality")!!, point.string("provider")))
        } }.sortedBy { it.elapsedRealtimeNanos }
    }
    private fun trackGaps(id: String): List<RingWorkoutGap> = readableDatabase.rawQuery(
        "SELECT payload FROM track_gaps WHERE session=? ORDER BY id", arrayOf(sha256(id))).use { cursor ->
        buildList { while (cursor.moveToNext()) {
            val gap = open(cursor.getBlob(0), "track-gap:${sha256(id)}")
            add(RingWorkoutGap(gap["start_ms"]!!.jsonPrimitive.long, gap["end_ms"]!!.jsonPrimitive.long, gap.string("reason")!!))
        } }
    }
    private fun sealTrackPending(s: RingWorkoutSession) {
        val chunks = RingTrackChunker.split(trackPoints(s.id), trackGaps(s.id))
        if (chunks.isEmpty()) return
        val j = journal(s.id)!!
        var sequence = j["track_sequence"]?.jsonPrimitive?.intOrNull ?: 0
        chunks.forEach { chunk ->
            addRecord(s, "track", obj {
                put("external_id", "phone-workout:${s.id}:gps:$sequence"); put("workout_id", s.id); put("sequence", sequence++)
                put("start_at", s.anchor.at(chunk.startElapsedMs).toString()); put("end_at", s.anchor.at(chunk.endElapsedMs).toString())
                put("time_basis", "phone_monotonic"); put("clock_anchor", clock(s.anchor, false))
                put("points", JsonArray(chunk.points.map { point -> JsonObject(trackPoint(point) - "elapsed_nanos" +
                    ("offset_ms" to JsonPrimitive(point.elapsedRealtimeMs - chunk.startElapsedMs))) }))
                put("gaps", JsonArray(chunk.gaps.map { gap -> obj {
                    put("start_offset_ms", gap.startElapsedMs - chunk.startElapsedMs); put("end_offset_ms", gap.endElapsedMs - chunk.startElapsedMs); put("reason", gap.reason)
                } }))
            })
        }
        for (table in listOf("track_points", "track_gaps")) {
            val usage = readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(LENGTH(payload)),0) FROM $table WHERE session=?", arrayOf(sha256(s.id))).use {
                it.moveToFirst(); it.getLong(0) to it.getLong(1)
            }
            writableDatabase.delete(table, "session=?", arrayOf(sha256(s.id)))
            addUsage(-usage.first, -usage.second)
        }
        writeJournal(s.id, JsonObject(j + ("track_sequence" to JsonPrimitive(sequence)) +
            ("track_encoded_end_ms" to JsonPrimitive(chunks.last().endElapsedMs))))
    }
    private fun pendingPoints(id: String): List<RingWorkoutPoint> = readableDatabase.rawQuery(
        "SELECT payload FROM points WHERE session=? ORDER BY id", arrayOf(sha256(id))).use { cursor ->
        buildList { while (cursor.moveToNext()) {
            val value = open(cursor.getBlob(0), "point:${sha256(id)}")
            add(RingWorkoutPoint(value["elapsed_ms"]!!.jsonPrimitive.long, value.number("bpm")!!, value.string("quality")!!,
                value["elapsed_nanos"]!!.jsonPrimitive.long))
        } }
    }
    private fun pendingGaps(id: String): List<RingWorkoutGap> = readableDatabase.rawQuery(
        "SELECT payload FROM gaps WHERE session=? ORDER BY id", arrayOf(sha256(id))).use { cursor ->
        buildList { while (cursor.moveToNext()) {
            val value = open(cursor.getBlob(0), "gap:${sha256(id)}")
            add(RingWorkoutGap(value["start_ms"]!!.jsonPrimitive.long, value["end_ms"]!!.jsonPrimitive.long, value.string("reason")!!))
        } }
    }
    /** Seals only committed observations. Empty time intervals never acquire synthetic samples. */
    @Synchronized fun flush(now: RingWorkoutClock) = transaction {
        val s = load().session ?: return@transaction
        if (s.anchor.bootId == now.bootId) { sealPending(s, now.elapsedRealtimeMs); sealTrackPending(s) }
    }
    private fun sealPending(s: RingWorkoutSession, closedBeforeElapsedMs: Long? = null) {
        val allPoints = pendingPoints(s.id); val allGaps = pendingGaps(s.id)
        val points = allPoints.filter { closedBeforeElapsedMs == null || it.elapsedRealtimeMs < closedBeforeElapsedMs }
        val gaps = allGaps.filter { closedBeforeElapsedMs == null || it.startElapsedMs < closedBeforeElapsedMs }.map {
            if (closedBeforeElapsedMs == null) it else it.copy(endElapsedMs = minOf(it.endElapsedMs, closedBeforeElapsedMs))
        }
        val chunks = RingWorkoutChunker.split(points, gaps)
        if (chunks.isEmpty()) return
        var j = journal(s.id)!!
        var sequence = j["next_sequence"]!!.jsonPrimitive.int
        chunks.forEach { chunk -> addRecord(s, "chunk", chunk(s, sequence++, chunk)) }
        val bufferedUsage = listOf("points", "gaps").map { table -> readableDatabase.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(LENGTH(payload)),0) FROM $table WHERE session=?", arrayOf(sha256(s.id))).use {
            it.moveToFirst(); it.getLong(0) to it.getLong(1)
        } }
        writableDatabase.delete("points", "session=?", arrayOf(sha256(s.id)))
        writableDatabase.delete("gaps", "session=?", arrayOf(sha256(s.id)))
        addUsage(-bufferedUsage.sumOf { it.first }, -bufferedUsage.sumOf { it.second })
        if (closedBeforeElapsedMs != null) {
            allPoints.filter { it.elapsedRealtimeMs >= closedBeforeElapsedMs }.forEach { point ->
                val payload = seal(obj { put("elapsed_ms", point.elapsedRealtimeMs); put("elapsed_nanos", point.elapsedRealtimeNanos)
                    put("bpm", point.bpm); put("quality", point.quality) }, "point:${sha256(s.id)}")
                writableDatabase.insertOrThrow("points", null, ContentValues().apply { put("session", sha256(s.id)); put("payload", payload) })
                addUsage(1, payload.size.toLong())
            }
            allGaps.filter { it.endElapsedMs > closedBeforeElapsedMs }.forEach { original ->
                val gap = original.copy(startElapsedMs = maxOf(original.startElapsedMs, closedBeforeElapsedMs))
                val payload = seal(obj { put("start_ms", gap.startElapsedMs); put("end_ms", gap.endElapsedMs); put("reason", gap.reason) }, "gap:${sha256(s.id)}")
                writableDatabase.insertOrThrow("gaps", null, ContentValues().apply { put("session", sha256(s.id)); put("payload", payload) })
                addUsage(1, payload.size.toLong())
            }
        }
        j = JsonObject(j + ("next_sequence" to JsonPrimitive(sequence)) + ("last_encoded_end_ms" to JsonPrimitive(chunks.last().endElapsedMs)))
        writeJournal(s.id, j)
    }
    private fun chunk(s: RingWorkoutSession, sequence: Int, chunk: RingWorkoutChunk) = obj {
        put("external_id", "phone-workout:${s.id}:hr:$sequence"); put("workout_id", s.id); put("sequence", sequence)
        put("start_at", s.anchor.at(chunk.startElapsedMs).toString()); put("end_at", s.anchor.at(chunk.endElapsedMs).toString())
        put("time_basis", "phone_monotonic"); put("clock_anchor", clock(s.anchor, false))
        put("samples", JsonArray(chunk.samples.map { point -> obj {
            put("offset_ms", point.elapsedRealtimeMs - chunk.startElapsedMs); put("bpm", point.bpm); put("quality", point.quality)
        } }))
        put("gaps", JsonArray(chunk.gaps.map { gap -> obj {
            put("start_offset_ms", gap.startElapsedMs - chunk.startElapsedMs); put("end_offset_ms", gap.endElapsedMs - chunk.startElapsedMs); put("reason", gap.reason)
        } }))
    }
    private fun terminal(s: RingWorkoutSession): JsonObject {
        val start = s.anchor.at(s.startElapsedMs)
        val endElapsed = maxOf(s.runEndElapsedMs!!, s.startElapsedMs + 1)
        val recoveryElapsed = maxOf(s.recoveryEndElapsedMs ?: endElapsed, endElapsed, s.lastObservedElapsedMs + 1)
        val end = s.anchor.at(endElapsed); val recovered = s.anchor.at(recoveryElapsed)
        val kind = if (s.sportPlan?.sportType?.let { it != "running" } == true) "workouts" else "running_workouts"
        return obj {
            put("external_id", "phone-workout:${s.id}"); put("version", RingPolicy.sourceVersion(s.binding.grantGeneration, 1))
            put("kind", kind); put("report", obj {
                put("kind", kind); put("shape", "workout_report")
                put("local_date", start.atZone(ZoneId.of(s.binding.timezone)).toLocalDate().toString()); put("timezone", s.binding.timezone)
                put("observed_at", recovered.toString()); put("start_at", start.toString()); put("end_at", end.toString())
                put("data", obj {
                    put("sport_type", s.sportPlan?.sportType ?: "running"); put("duration_seconds", (endElapsed - s.startElapsedMs) / 1000)
                    if (s.sportPlan != null || s.pauses.isNotEmpty()) {
                        s.sportPlan?.let { put("sport_plan", it.json()) }
                        put("active_duration_seconds", s.durationAt(endElapsed).activeSeconds)
                        put("pauses", JsonArray(s.pauses.mapNotNull { pause ->
                            val pauseEnd = minOf(pause.endElapsedMs, endElapsed)
                            if (pause.startElapsedMs >= pauseEnd) null else obj {
                                put("start_at", s.anchor.at(pause.startElapsedMs).toString()); put("end_at", s.anchor.at(pauseEnd).toString())
                            }
                        }))
                    }
                })
                put("origin", obj {
                    put("provider", "herzring"); put("profile", "qring-r20"); put("source", "phone_recorded")
                    put("record_id", s.id); put("date_basis", "phone_monotonic")
                    put("details", obj {
                        put("status", s.status); put("trigger", s.trigger); put("recovery_end_at", recovered.toString())
                        s.stopReason?.let { put("stop_reason", it) }
                        if (s.sportPlan != null) {
                            put("recording_source", when {
                                s.sportPlan.actualWireMode != null -> "ring_controlled"
                                s.heartRateRequested -> "phone_with_ring_hr"
                                else -> "phone_only"
                            })
                            put("gps_requested", s.gpsRequested)
                        }
                    })
                })
            })
        }
    }
    private fun addRecord(s: RingWorkoutSession, category: String, record: JsonObject) {
        val identity = sha256(record.string("external_id")!!)
        val old = readableDatabase.rawQuery("SELECT payload FROM records WHERE identity=?", arrayOf(identity)).use {
            if (it.moveToFirst()) open(it.getBlob(0), "record:$identity") else null
        }
        if (old != null) {
            if (old != record) throw RingFailure("workout_record_changed", RingText.text(R.string.ring_workout_7a9b74cbb484))
            return
        }
        val payload = seal(record, "record:$identity")
        if (category == "track") ensureTrackCapacity(payload.size) else ensureCapacity(payload.size)
        writableDatabase.insertOrThrow("records", null, ContentValues().apply {
            put("identity", identity); put("session", sha256(s.id)); put("binding", s.binding.uploadIdentity)
            put("category", category); put("payload", payload)
        })
        addUsage(1, payload.size.toLong())
    }
    /** Descriptor and chunks transfer to the immutable encrypted HTTP outbox before this queue acknowledges them. */
    @Synchronized fun pendingRecords(binding: RingWorkoutBinding, includeClosed: Boolean = false): List<JsonObject> {
        if (binding.origin != origin) return emptyList()
        val rows = mutableListOf<Pair<String, JsonObject>>()
        readableDatabase.rawQuery("SELECT identity,session,category,payload FROM records WHERE binding=? ORDER BY CASE category WHEN 'terminal' THEN 0 ELSE 1 END,id",
            arrayOf(binding.uploadIdentity)).use { cursor -> while (cursor.moveToNext()) {
            if (!includeClosed && journalIdentity(cursor.getString(1))?.bool("closed_without_transfer") == true) continue
            val record = open(cursor.getBlob(3), "record:${cursor.getString(0)}")
            rows += cursor.getString(2) to record
        } }
        val descriptors = rows.filter { it.first == "terminal" }.map { ((it.second["report"] as JsonObject)["origin"] as JsonObject).string("record_id")!! }.toSet()
        return rows.filter { (category, record) -> category == "terminal" || record.string("workout_id")?.let { id ->
            id in descriptors || journal(id)?.bool("terminal_acknowledged") == true
        } == true }.map { it.second }
    }
    @Synchronized fun acknowledge(externalIds: Set<String>, binding: RingWorkoutBinding) = transaction {
        if (binding.origin != origin) throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_d2b8a951e428))
        externalIds.forEach { external ->
            val identity = sha256(external)
            val row = readableDatabase.rawQuery("SELECT binding,category,payload,session FROM records WHERE identity=?", arrayOf(identity)).use {
                if (it.moveToFirst()) {
                    if (journalIdentity(it.getString(3))?.bool("closed_without_transfer") == true)
                        throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_ac2bc8be9535))
                    Pair(Triple(it.getString(0), it.getString(1), open(it.getBlob(2), "record:$identity")), it.getBlob(2).size)
                } else null
            } ?: throw RingFailure("invalid_batch", RingText.text(R.string.ring_workout_8c8e0afd96c8))
            if (row.first.first != binding.uploadIdentity || row.first.third.string("external_id") != external)
                throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_ac2bc8be9535))
            if (row.first.second == "terminal") {
                val id = ((row.first.third["report"] as JsonObject)["origin"] as JsonObject).string("record_id")!!
                writeJournal(id, JsonObject(journal(id)!! + ("terminal_acknowledged" to JsonPrimitive(true))))
            }
            writableDatabase.delete("records", "identity=?", arrayOf(identity))
            addUsage(-1, -row.second.toLong())
        }
    }
    private fun usage(): Pair<Long, Long> = readableDatabase.rawQuery("SELECT payload FROM metadata WHERE key='usage'", null).use {
        if (it.moveToFirst()) open(it.getBlob(0), "usage").let { value ->
            value["count"]!!.jsonPrimitive.long to value["bytes"]!!.jsonPrimitive.long
        } else 0L to 0L
    }
    private fun addUsage(count: Long, bytes: Long) {
        val old = usage(); val next = (old.first + count) to (old.second + bytes)
        require(next.first >= 0 && next.second >= 0)
        val row = writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("key", "usage"); put("payload", seal(obj { put("count", next.first); put("bytes", next.second) }, "usage"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (row == -1L) throw RingFailure("store_unavailable", RingText.text(R.string.ring_workout_fe728fdf9ff8))
    }
    private fun ensureCapacity(extraBytes: Int) {
        val usage = usage()
        if (usage.first >= RingPolicy.MAX_RECORDS || usage.second + extraBytes > RingPolicy.MAX_BYTES)
            throw RingFailure("queue_full", RingText.text(R.string.ring_workout_0c491818d5fe))
    }
    private fun ensureTrackCapacity(extraBytes: Int) {
        // GPS cannot consume the space reserved for HR and terminal receipts while offline.
        val buffered = listOf("track_points", "track_gaps").map { table -> readableDatabase.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(LENGTH(payload)),0) FROM $table", null).use { rows ->
            rows.moveToFirst(); rows.getLong(0) to rows.getLong(1)
        } }
        val sealed = readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(LENGTH(payload)),0) FROM records WHERE category='track'", null).use { rows ->
            rows.moveToFirst(); rows.getLong(0) to rows.getLong(1)
        }
        if (buffered.sumOf { it.first } + sealed.first >= RingPolicy.MAX_RECORDS / 2 ||
            buffered.sumOf { it.second } + sealed.second + extraBytes > RingPolicy.MAX_BYTES / 2)
            throw RingFailure("track_queue_full", RingText.text(R.string.ring_workout_c88aefcecc38))
        ensureCapacity(extraBytes)
    }
    private fun <T> transaction(block: () -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    /** Explicit re-enrollment closes the old lineage without erasing its offline workout evidence. */
    @Synchronized fun closeBinding(owner: RingWorkoutBinding) = transaction {
        if (owner.origin != origin) throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_a49928f1845c))
        val closed = mutableListOf<Pair<String, JsonObject>>()
        readableDatabase.rawQuery("SELECT identity,payload FROM sessions", null).use { rows ->
            while (rows.moveToNext()) {
                val value = open(rows.getBlob(1), "session:${rows.getString(0)}")
                if (binding(value["binding"] as JsonObject).uploadIdentity == owner.uploadIdentity)
                    closed += rows.getString(0) to JsonObject(value + ("closed_without_transfer" to JsonPrimitive(true)))
            }
        }
        closed.forEach { (identity, value) ->
            writableDatabase.update("sessions", ContentValues().apply { put("payload", seal(value, "session:$identity")) }, "identity=?", arrayOf(identity))
        }
    }
    /** Cleanup is authorized only for its captured native binding; another owner's journals survive. */
    @Synchronized fun clear(owner: RingWorkoutBinding? = null) {
        if (owner == null) return // An unknown binding cannot authorize erasing any journal.
        if (owner.origin != origin) throw RingFailure("invalid_binding", RingText.text(R.string.ring_workout_a49928f1845c))
        val previous = load()
        val session = previous.session
        val ownsCurrent = session != null && session.binding.identity == owner.identity && journal(session.id)?.bool("closed_without_transfer") != true
        val pending = if (ownsCurrent && session != null && (previous.pendingCommand != null ||
            previous.phase in setOf("starting", "recording", "grace", "recovery", "stopping", "unknown", "interrupted"))) {
            val interrupted = RingWorkoutReducer.recover(previous, session.anchor).state
            interrupted.copy(phase = "interrupted", pendingCommand = RingWorkoutCommand.STOP, commandUnknown = true,
                session = interrupted.session?.copy(status = "interrupted", stopReason = "binding_revoked"))
        } else null
        var retained = false
        transaction {
            val owned = mutableListOf<String>()
            readableDatabase.rawQuery("SELECT identity,payload FROM sessions", null).use { rows ->
                while (rows.moveToNext()) {
                    val value = open(rows.getBlob(1), "session:${rows.getString(0)}")
                    if (value.bool("closed_without_transfer") != true && binding(value["binding"] as JsonObject).identity == owner.identity)
                        owned += rows.getString(0)
                }
            }
            owned.forEach { identity ->
                listOf("points", "gaps", "track_points", "track_gaps", "records").forEach {
                    writableDatabase.delete(it, "session=?", arrayOf(identity))
                }
                writableDatabase.delete("sessions", "identity=?", arrayOf(identity))
            }
            if (ownsCurrent) {
                writableDatabase.delete("metadata", "key='state'", null)
                writableDatabase.delete("cleanup", null, null)
                pending?.let(::writeCleanup)
            }
            // Usage remains global to the encrypted journal, including retained closed lineages.
            val usage = listOf("points", "gaps", "track_points", "track_gaps", "records").map { table ->
                readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(LENGTH(payload)),0) FROM $table", null).use {
                    it.moveToFirst(); it.getLong(0) to it.getLong(1)
                }
            }
            writableDatabase.delete("metadata", "key='usage'", null)
            addUsage(usage.sumOf { it.first }, usage.sumOf { it.second })
            retained = listOf("sessions", "cleanup").any { table ->
                readableDatabase.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }
            } || readableDatabase.rawQuery("SELECT 1 FROM metadata WHERE key='state' LIMIT 1", null).use { it.moveToFirst() }
            if (!retained) writableDatabase.delete("metadata", null, null)
        }
        if (!retained) crypto.delete()
    }
    @Synchronized fun hasData(): Boolean = readableDatabase.rawQuery("SELECT " +
        listOf("metadata", "cleanup", "sessions", "points", "gaps", "records", "track_points", "track_gaps")
            .joinToString(" OR ") { "EXISTS(SELECT 1 FROM $it)" }, null,
    ).use { it.moveToFirst(); it.getInt(0) != 0 }

    /** Server departure erases every lineage, including closed bindings and uncertain stops. */
    @Synchronized fun clearForServerSwitch() {
        close()
        val name = "ring-workouts-$namespace.sqlite"
        check(context.deleteDatabase(name) || !context.getDatabasePath(name).exists()) {
            "Ring workout database could not be cleared"
        }
        crypto.delete()
    }
    /** Explicit reset only. A lost Keystore key must not silently drop an offline workout. */
    @Synchronized fun resetUnreadable() = clearForServerSwitch()
}
