package assistant.mobile.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import assistant.mobile.design.GeneratedColors
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Synthetic owner/device replies follow devices/api.test.ts and display/routes.py. */
internal class NativeDevicesFixture {
    private var name = DISPLAY_NAME
    private var revision = 2
    private var settings = defaultSettings()
    private var revoked = false
    private var paired = false
    private var pairedName = ""
    private var frame = 1

    @Synchronized fun listing(): JSONObject = JSONObject().put("devices", JSONArray().also { devices ->
        devices.put(device("synthetic-device", "android", "Emulator").put("current", true))
        devices.put(device(BROWSER_ID, "browser", "Synthetic browser"))
        devices.put(device(AGENT_ID, "agent", "Synthetic agent").put("grants", JSONObject()
            .put("writes", JSONArray(listOf("nutrition.write"))).put("diagnostics", false)))
        if (!revoked) devices.put(displayDevice())
        if (paired) devices.put(device(PAIRED_ID, "display", pairedName).put("revision", 0))
    })

    @Synchronized fun describe(id: String): Pair<Int, JSONObject> {
        if ((id == DISPLAY_ID && revoked) || id !in setOf(DISPLAY_ID, PAIRED_ID) || (id == PAIRED_ID && !paired))
            return 404 to JSONObject()
        return 200 to JSONObject()
            .put("device", if (id == DISPLAY_ID) displayDevice() else device(PAIRED_ID, "display", pairedName).put("revision", 0))
            .put("settings", copy(settings)).put("default", defaultSettings()).put("online", id == DISPLAY_ID)
            .put("cards", JSONArray()
                .put(card("clock", "Время", true))
                .put(card("subscriptions", "Подписки", true))
                .put(card("device", "Устройство", true))
                .put(card("portfolio", "Портфель", false)))
            .put("layouts", JSONObject().put("single", 1).put("split", 2).put("grid", 4))
    }

    @Synchronized fun save(id: String, body: JSONObject): Pair<Int, JSONObject> {
        if (id != DISPLAY_ID || revoked) return 404 to JSONObject()
        if (body.getInt("revision") != revision) return 409 to JSONObject()
        settings = copy(body.getJSONObject("settings"))
        check(settings.getJSONArray("pages").length() in 1..8)
        val pages = settings.getJSONArray("pages")
        repeat(pages.length()) { index ->
            val page = pages.getJSONObject(index)
            check(page.getJSONArray("cards").length() == when (page.getString("layout")) {
                "single" -> 1
                "split" -> 2
                "grid" -> 4
                else -> error("Unknown display layout")
            })
        }
        if (body.has("name")) name = body.getString("name")
        revision += 1
        frame += 1
        return describe(id)
    }

    @Synchronized fun approve(body: JSONObject): Pair<Int, JSONObject> {
        if (body.getString("code") != PAIR_CODE) return 404 to JSONObject()
        if (paired) return 409 to JSONObject()
        paired = true
        pairedName = body.optString("name").ifBlank { "Экран" }
        return 200 to JSONObject().put("device", device(PAIRED_ID, "display", pairedName).put("revision", 0))
    }

    @Synchronized fun revoke(id: String): Pair<Int, JSONObject> {
        when (id) {
            DISPLAY_ID -> {
                if (revoked) return 404 to JSONObject()
                revoked = true
            }
            PAIRED_ID -> paired = false
            else -> return 404 to JSONObject()
        }
        return 200 to JSONObject().put("revoked", true)
    }

    @Synchronized fun input(id: String, body: JSONObject): Pair<Int, JSONObject> {
        if (id != DISPLAY_ID || revoked) return 404 to JSONObject()
        check(body.length() == 1)
        check(body.getString("event") in setOf("short", "long"))
        frame += 1
        return 200 to JSONObject().put("etag", etag())
    }

    @Synchronized fun etag(): String = "\"synthetic-display-$frame\""

    /** Actual PNG bytes exercise Bitmap decoding; each save/input changes the rendered pixels. */
    @Synchronized fun preview(): ByteArray {
        val palette = GeneratedColors.light
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(palette.getValue("background").toArgb())
        val canvas = Canvas(bitmap)
        val ink = Paint().apply { color = palette.getValue("foreground").toArgb() }
        canvas.drawRect((frame * 19 % 300).toFloat(), 30f, (frame * 19 % 300 + 60).toFloat(), 270f, ink)
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun displayDevice() = device(DISPLAY_ID, "display", name)
        .put("revision", revision)
        .put("status", JSONObject().put("rssi", -60).put("firmware", "0.1.0").put("uptime", 93784).put("heap", 120000))

    private fun device(id: String, kind: String, name: String) = JSONObject()
        .put("id", id).put("kind", kind).put("name", name).put("created_at", "2026-09-26T10:00:00Z")
        .put("last_seen_at", if (id == PAIRED_ID) JSONObject.NULL else "2026-10-17T10:00:00Z")
        .put("status", JSONObject.NULL)

    private fun card(id: String, title: String, available: Boolean) = JSONObject()
        .put("id", id).put("title", title).put("available", available).put("brief", true)

    private fun defaultSettings() = JSONObject().put("home_after_s", 300)
        .put("pages", JSONArray()
            .put(JSONObject().put("layout", "single").put("cards", JSONArray(listOf("clock")))))

    private fun copy(value: JSONObject) = JSONObject(value.toString())

    companion object {
        const val DISPLAY_ID = "77777777-7777-4777-8777-777777777777"
        const val PAIRED_ID = "88888888-8888-4888-8888-888888888888"
        const val BROWSER_ID = "99999999-9999-4999-8999-999999999999"
        const val AGENT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val DISPLAY_NAME = "Кухня"
        const val PAIR_CODE = "4K7Q-2MXA"
    }
}
