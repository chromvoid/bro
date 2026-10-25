package assistant.mobile.platform.ring

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-ring diagnostic. It neither opens RingStore nor sends measurements to a server. */
@RunWith(AndroidJUnit4::class)
class RingHardwareAcceptanceTest {
    @Test fun selectedRingHistoryThroughProductionGattAndJni() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requestedAddress = args.getString("ringAddress")
        assumeTrue("Real-ring acceptance needs an explicit address and confirmation", requestedAddress != null &&
            args.getString("confirmReadOnlyHistory") == "yes")
        val address = requireNotNull(requestedAddress)
        require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) { "Invalid explicitly selected ring address" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Bluetooth permission must be granted before this test", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION))
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assertNotNull("Bluetooth is unavailable", adapter)
        assertTrue("Bluetooth must be enabled before this test", adapter.isEnabled)
        val zone = ZoneId.of(args.getString("ringTimezone") ?: ZoneId.systemDefault().id)
        val historyDays = (args.getString("ringHistoryDays") ?: "30").toInt()
        require(historyDays in 1..30)
        val local = ZonedDateTime.now(zone)
        val directory = File(context.filesDir, "ring-acceptance").apply { mkdirs() }
        val log = File(directory, "offlinehardware.jsonl")
        val summaryFile = File(directory, "summary.json")
        val checkpointFile = File(directory, "checkpoint.json")
        // Repeated invocations resume only the same ring/day/timezone/window. No user queue is opened.
        val saved = if (checkpointFile.exists()) Json.parseToJsonElement(checkpointFile.readText()) as JsonObject else obj {}
        val matching = saved.string("address") == address && saved.string("local_date") == local.toLocalDate().toString() &&
            saved.string("timezone") == zone.id && saved.number("history_days") == historyDays && saved.bool("completed") != true
        var checkpoint = if (matching) saved["checkpoint"] as? JsonObject ?: obj {} else obj {}
        val generation = SystemClock.elapsedRealtime().coerceAtLeast(1)
        val config = obj {
            put("generation", generation); put("timezone", zone.id); put("local_date", local.toLocalDate().toString())
            put("history_days", historyDays); put("completed_queries", checkpoint["completed_queries"] ?: JsonArray(emptyList()))
            put("clock", obj {
                put("year", local.year); put("month", local.monthValue); put("day", local.dayOfMonth)
                put("hour", local.hour); put("minute", local.minute); put("second", local.second); put("language", 10)
            })
            put("android", obj { put("sdk", Build.VERSION.SDK_INT); put("model", Build.MODEL) })
        }
        assertEquals(1, NativeRing.apiVersion())
        val handle = NativeRing.create(config.toString().toByteArray(Charsets.UTF_8))
        assertNotEquals("Native session rejected configuration", 0L, handle)
        val link = RingGatt(context, adapter.getRemoteDevice(address))
        val pending = ArrayDeque<JsonObject>()
        val rawCounts = linkedMapOf<String, Int>()
        val convertedCounts = linkedMapOf<String, Int>()
        val qualities = linkedMapOf<String, Int>()
        val queryStatuses = linkedMapOf<String, String>()
        val diagnostics = mutableListOf<JsonObject>()
        var firmware: String? = null
        var hardware: String? = null
        var battery: Int? = null
        var charging: Boolean? = null
        var nextDeadline: Long? = null
        var completed = false
        var completedStatus: String? = null
        var failure: Throwable? = null
        val started = SystemClock.elapsedRealtime()
        val output = FileOutputStream(log, true)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        var loggedBytes = log.length()
        fun capture(kind: String, value: JsonObject) {
            val line = obj {
                put("session", generation); put("at_ms", SystemClock.elapsedRealtime()); put("kind", kind); put("value", value)
            }.toString() + "\n"
            loggedBytes += line.toByteArray(Charsets.UTF_8).size
            require(loggedBytes <= 32L * 1024 * 1024) { "Private acceptance capture reached 32 MiB; export and remove it before continuing" }
            writer.write(line)
        }
        fun input(type: String, fields: JsonObject = obj {}) = obj {
            put("type", type); put("generation", generation); fields.forEach { (key, value) -> put(key, value) }
        }
        fun dispatch(event: JsonObject, now: Long = SystemClock.elapsedRealtime()) {
            val result = NativeRing.dispatch(handle, event.toString().toByteArray(Charsets.UTF_8), now)
            val effects = Json.parseToJsonElement(result.toString(Charsets.UTF_8)) as JsonArray
            effects.forEach { pending.addLast(it as JsonObject) }
        }
        fun saveCheckpoint() {
            writer.flush(); output.fd.sync()
            atomicWrite(checkpointFile, obj {
                put("address", address); put("local_date", local.toLocalDate().toString()); put("timezone", zone.id)
                put("history_days", historyDays); put("checkpoint", checkpoint); put("completed", completed)
            })
        }
        try {
            capture("session", config)
            withTimeoutOrNull(RingPolicy.SESSION_MS) {
                link.open()
                dispatch(input("connected")); dispatch(input("sync"))
                while (!completed) {
                    while (pending.isNotEmpty()) {
                        val effect = pending.removeFirst()
                        capture("effect", effect)
                        when (effect.string("type")) {
                            "read_version" -> {
                                val kind = effect.string("kind")!!
                                val value = link.readVersion(kind) ?: ""
                                if (kind == "firmware") firmware = value.ifEmpty { null } else hardware = value.ifEmpty { null }
                                capture("version", obj { put("kind", kind); put("value", value) })
                                dispatch(input("version", obj { put("kind", kind); put("value", value) }))
                            }
                            "write" -> {
                                val channel = effect.string("channel")!!
                                val hex = effect.string("bytes_hex")!!
                                require(hex.length % 2 == 0 && hex.length <= 131_082)
                                val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                                val opcode = if (channel == "core") bytes.first().toInt() and 255 else bytes.getOrNull(1)?.toInt()?.and(255)
                                require(if (channel == "core") opcode in setOf(0x01, 0x03, 0x04, 0x15, 0x37, 0x39, 0x3c, 0x43)
                                    else channel == "bulk" && opcode in setOf(0x27, 0x75, 0x5f, 0x74, 0x2a, 0x25)) {
                                    "Acceptance allows only initialization, battery, declarations and fixed history profile queries"
                                }
                                capture("tx", effect)
                                RingProtocolWrite.execute(effect.string("query_id"),
                                    write = { link.write(channel, bytes, effect.bool("response") ?: true) },
                                    complete = { success -> dispatch(input("write_complete", obj { put("success", success) })) })
                            }
                            "deadline" -> nextDeadline = effect["at_ms"]?.jsonPrimitive?.longOrNull
                            "battery" -> { battery = effect.number("percent"); charging = effect.bool("charging") }
                            "declared_capabilities" -> capture("declared_capabilities", effect)
                            "observation" -> {
                                val raw = effect["observation"] as JsonObject
                                val metric = raw.string("metric") ?: "unknown"
                                rawCounts[metric] = (rawCounts[metric] ?: 0) + 1
                                val quality = "$metric:${raw.string("quality") ?: "unknown"}"
                                qualities[quality] = (qualities[quality] ?: 0) + 1
                                val converted = RingObservation.convert(raw, zone, firmware, hardware)
                                if (converted != null) {
                                    convertedCounts[metric] = (convertedCounts[metric] ?: 0) + 1
                                    capture("converted", converted)
                                }
                            }
                            "progress" -> {
                                queryStatuses[effect.string("query_id") ?: "unknown"] = effect.string("status") ?: "unknown"
                                (effect["checkpoint"] as? JsonObject)?.let { checkpoint = it; saveCheckpoint() }
                            }
                            "diagnostic" -> diagnostics += effect
                            "error" -> throw RingFailure(effect.string("code") ?: "protocol_error", "Native protocol rejected the hardware session")
                            "completed" -> { completed = true; completedStatus = effect.string("status"); saveCheckpoint() }
                        }
                    }
                    if (completed) break
                    val wait = (nextDeadline?.minus(SystemClock.elapsedRealtime()) ?: 8_000).coerceIn(1, 8_000)
                    val notice = withTimeoutOrNull(wait) { link.notices.receive() }
                    if (notice == null) dispatch(input("deadline"))
                    else {
                        val fields = obj { put("channel", notice.channel); put("bytes_hex", notice.bytes.joinToString("") { "%02x".format(it) }) }
                        capture("rx", obj {
                            fields.forEach { (key, value) -> put(key, value) }
                            put("observed_at", notice.observedAt.toString()); put("observed_monotonic_ms", notice.observedElapsed)
                        })
                        dispatch(input("notification", fields), notice.observedElapsed)
                    }
                }
            }
            if (battery == null) failure = RingFailure("battery_unavailable", "No verified battery response within the 60-second session")
        } catch (error: Throwable) {
            failure = error
        } finally {
            try { link.close() } finally { NativeRing.destroy(handle) }
            try {
                saveCheckpoint()
                atomicWrite(summaryFile, obj {
                    put("session", generation); put("address", address); put("model", Build.MODEL); put("android_sdk", Build.VERSION.SDK_INT)
                    put("timezone", zone.id); put("local_date", local.toLocalDate().toString()); put("history_days", historyDays)
                    put("resumed", matching); put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    put("battery", battery?.let(::JsonPrimitive) ?: JsonNull); put("charging", charging?.let(::JsonPrimitive) ?: JsonNull)
                    put("firmware", firmware?.let(::JsonPrimitive) ?: JsonNull); put("hardware", hardware?.let(::JsonPrimitive) ?: JsonNull)
                    put("completed", completed); put("completed_status", completedStatus?.let(::JsonPrimitive) ?: JsonNull)
                    put("raw_counts", JsonObject(rawCounts.mapValues { JsonPrimitive(it.value) }))
                    put("converted_counts", JsonObject(convertedCounts.mapValues { JsonPrimitive(it.value) }))
                    put("quality_counts", JsonObject(qualities.mapValues { JsonPrimitive(it.value) }))
                    put("query_status", JsonObject(queryStatuses.mapValues { JsonPrimitive(it.value) }))
                    put("diagnostics", JsonArray(diagnostics)); put("checkpoint", checkpoint)
                    put("failure", failure?.let { obj {
                        put("code", (it as? RingFailure)?.code ?: it.javaClass.simpleName)
                        put("message", "Hardware diagnostic failed; inspect the private capture")
                    } } ?: JsonNull)
                    put("validation_status", "unverified_requires_soul_comparison")
                })
            } finally { writer.close() }
        }
        failure?.let { throw it }
        assertNotNull("No verified battery response was received in the 60-second session; inspect private summary", battery)
    }

    private fun atomicWrite(destination: File, value: JsonObject) {
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(value.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        check(temporary.renameTo(destination)) { "Could not persist private acceptance result" }
    }
}
