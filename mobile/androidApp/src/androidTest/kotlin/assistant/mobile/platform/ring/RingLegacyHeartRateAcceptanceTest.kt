package assistant.mobile.platform.ring

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit SDK legacy-history experiment; results never enter production observations or the outbox. */
@RunWith(AndroidJUnit4::class)
class RingLegacyHeartRateAcceptanceTest {
    @Test fun fixedLegacyHeartRateHistoryForTodayAndYesterday() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requestedAddress = args.getString("ringAddress")
        assumeTrue("Legacy HR needs a selected address and two explicit confirmations", requestedAddress != null &&
            args.getString("confirmReadOnlyHistory") == "yes" && args.getString("ringLegacyHeartRate") == "yes")
        val address = requireNotNull(requestedAddress)
        require(BluetoothAdapter.checkBluetoothAddress(address))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(
            if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION))
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        assertNotNull(adapter); assertTrue(adapter.isEnabled)
        val zone = ZoneId.of(args.getString("ringTimezone") ?: ZoneId.systemDefault().id)
        val today = ZonedDateTime.now(zone).toLocalDate()
        val directory = File(context.filesDir, "ring-acceptance").apply { mkdirs() }
        val log = File(directory, "legacy-heart-rate.jsonl")
        val summary = File(directory, "legacy-heart-rate-summary.json")
        require(log.length() < 32L * 1024 * 1024) { "Export and remove the 32 MiB private diagnostic before continuing" }
        val session = SystemClock.elapsedRealtime()
        val results = mutableListOf<JsonObject>()
        var firmware: String? = null
        var failure: Throwable? = null
        var loggedBytes = log.length()
        val output = FileOutputStream(log, true)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        fun capture(kind: String, value: JsonObject) {
            val line = obj {
                put("session", session); put("at_ms", SystemClock.elapsedRealtime()); put("kind", kind); put("value", value)
            }.toString() + "\n"
            loggedBytes += line.toByteArray(Charsets.UTF_8).size
            require(loggedBytes <= 32L * 1024 * 1024) { "Private diagnostic reached 32 MiB" }
            writer.write(line)
        }
        val link = RingGatt(context, adapter.getRemoteDevice(address))
        try {
            val complete = withTimeoutOrNull(60_000) {
                link.open()
                firmware = link.readVersion("firmware")
                capture("session", obj {
                    put("address", address); put("timezone", zone.id); put("local_date", today.toString())
                    put("firmware", firmware?.let(::JsonPrimitive) ?: JsonNull)
                    put("request_time_basis", "local_calendar_midnight_encoded_as_UTC_per_SDK")
                })
                for (day in 0..1) {
                    if (day > 0) {
                        // A completed data stream may be followed by an FF tail. Do not assign it to yesterday.
                        withTimeoutOrNull(250) {
                            while (true) {
                                val late = link.notices.receive()
                                capture("between_days_rx", obj { put("channel", late.channel); put("bytes_hex", late.bytes.hex()) })
                            }
                        }
                    }
                    val date = today.minusDays(day.toLong())
                    // SDK encodes local calendar fields as a UTC-shaped epoch; this is not a proven measurement timestamp.
                    val requestTime = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
                    require(requestTime in 0..0xffff_ffffL)
                    val request = ByteArray(16)
                    request[0] = 0x15
                    for (index in 0..3) request[index + 1] = (requestTime shr (8 * index)).toByte()
                    request[15] = request.take(15).sumOf { it.toInt() and 255 }.toByte()
                    capture("tx", obj {
                        put("day", day); put("local_date", date.toString()); put("request_time", requestTime)
                        put("channel", "core"); put("response", true); put("bytes_hex", request.hex())
                    })
                    link.write("core", request, true)
                    var frames = 0
                    var invalidChecksums = 0
                    var otherFrames = 0
                    var terminal: Int? = null
                    var declaredFrameCount: Int? = null
                    var declaredRange: Int? = null
                    var nonzero = 0
                    var zero = 0
                    var tail = ByteArray(0)
                    val indices = mutableListOf<JsonElement>()
                    val finished = withTimeoutOrNull(8_000) {
                        while (terminal == null) {
                            val notice = link.notices.receive()
                            capture("rx", obj { put("day", day); put("channel", notice.channel); put("bytes_hex", notice.bytes.hex()) })
                            if (notice.channel != "core") continue
                            require(tail.size + notice.bytes.size <= 32_768)
                            tail += notice.bytes
                            while (tail.size >= 16) {
                                val frame = tail.copyOfRange(0, 16)
                                tail = tail.copyOfRange(16, tail.size)
                                if ((frame.take(15).sumOf { it.toInt() and 255 } and 255) != (frame[15].toInt() and 255)) {
                                    invalidChecksums++; continue
                                }
                                if ((frame[0].toInt() and 0x7f) != 0x15) { otherFrames++; continue }
                                frames++
                                val index = frame[1].toInt() and 255
                                indices += JsonPrimitive(index)
                                capture("legacy_frame", obj { put("day", day); put("index", index); put("bytes_hex", frame.hex()) })
                                if (index == 0xff) { terminal = index; break }
                                if (index == 0) {
                                    declaredFrameCount = frame[2].toInt() and 255
                                    declaredRange = frame[3].toInt() and 255
                                }
                                if (index > 0) {
                                    val samples = if (index == 1) frame.copyOfRange(6, 15) else frame.copyOfRange(2, 15)
                                    nonzero += samples.count { it != 0.toByte() }
                                    zero += samples.count { it == 0.toByte() }
                                }
                                if (declaredFrameCount?.let { it > 0 && index == it - 1 } == true) { terminal = index; break }
                            }
                        }
                        true
                    } == true
                    val result = obj {
                        put("day", day); put("local_date", date.toString()); put("request_time", requestTime)
                        put("frames", frames); put("frame_indices", JsonArray(indices)); put("invalid_checksums", invalidChecksums)
                        put("declared_frame_count", declaredFrameCount?.let(::JsonPrimitive) ?: JsonNull)
                        put("declared_range", declaredRange?.let(::JsonPrimitive) ?: JsonNull)
                        put("other_core_frames", otherFrames); put("nonzero_sample_bytes", nonzero); put("zero_sample_bytes", zero)
                        put("terminal_index", terminal?.let(::JsonPrimitive) ?: JsonNull); put("timed_out", !finished)
                        put("incomplete_tail_bytes", tail.size); put("validation_status", "unverified_legacy_candidate")
                    }
                    results += result; capture("day_summary", result)
                    writer.flush(); output.fd.sync()
                }
                true
            } == true
            if (!complete) throw RingFailure("legacy_budget", "Legacy diagnostic exceeded its overall 60-second budget")
        } catch (error: Throwable) {
            failure = error
        } finally {
            try { link.close() }
            finally {
                try {
                    writer.flush(); output.fd.sync()
                    val result = obj {
                        put("session", session); put("address", address); put("model", Build.MODEL)
                        put("timezone", zone.id); put("local_date", today.toString()); put("firmware", firmware?.let(::JsonPrimitive) ?: JsonNull)
                        put("days", JsonArray(results)); put("elapsed_ms", SystemClock.elapsedRealtime() - session)
                        put("failure", failure?.let { obj { put("code", (it as? RingFailure)?.code ?: it.javaClass.simpleName) } } ?: JsonNull)
                        put("validation_status", "unverified_requires_soul_comparison_no_import")
                    }
                    val temporary = File(directory, summary.name + ".tmp")
                    FileOutputStream(temporary).use { stream -> stream.write(result.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
                    check(temporary.renameTo(summary))
                } finally { writer.close() }
            }
        }
        failure?.let { throw it }
        assertTrue("No checksum-valid legacy HR response; inspect the private diagnostic", results.any { (it.number("frames") ?: 0) > 0 })
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
