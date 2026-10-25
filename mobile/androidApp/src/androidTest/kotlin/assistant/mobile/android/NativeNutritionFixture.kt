package assistant.mobile.android

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Synthetic diary with the same entries/items overlap and nullable nutrients as FoodView.test.ts. */
internal class NativeNutritionFixture {
    val date: String = LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
    private val days = mutableMapOf<String, JSONObject>()
    private val receipts = mutableMapOf<String, JSONObject>()
    private val bodies = mutableMapOf<String, String>()
    private val before = mutableMapOf<String, JSONObject>()
    private val undone = mutableSetOf<String>()
    private var favorite = false
    private var preferenceRevision = 0
    @Volatile var conflictNext = false
    @Volatile var lookupOnline = true
    @Volatile var disconnectAdmission = false

    @Synchronized fun writeCount(): Int = receipts.size

    @Synchronized fun search(path: String): JSONObject {
        val query = requireNotNull(Uri.parse(path).getQueryParameter("q"))
        val values = listOf(food())
            .filter { query.isBlank() || it.getString("name").contains(query, ignoreCase = true) }
        return JSONObject().put("foods", JSONArray(values))
    }

    @Synchronized fun memory(path: String): JSONObject {
        check(Uri.parse(path).getQueryParameter("date") != null)
        return JSONObject().put("foods", JSONArray().put(JSONObject()
            .put("food_id", FOOD_ID).put("snapshot_id", SNAPSHOT_ID).put("name", FOOD_NAME)
            .put("favorite", favorite).put("preference_revision", preferenceRevision)
            .put("uses_90_days", 3).put("last_eaten", date).put("last_quantity", "150.00")
            .put("last_unit", "g").put("basis_quantity", "100.00").put("basis_unit", "g")
            .put("energy_kcal", "52.00")))
    }

    @Synchronized fun compositions(path: String): JSONObject {
        val query = requireNotNull(Uri.parse(path).getQueryParameter("q"))
        val values = JSONArray()
        if (query.isBlank() || COMPOSITION_NAME.contains(query, ignoreCase = true)) values.put(JSONObject()
            .put("id", COMPOSITION_ID).put("name", COMPOSITION_NAME)
            .put("components", JSONArray().put(JSONObject().put("label", FOOD_NAME)
                .put("quantity", "150.00").put("unit", "g"))))
        return JSONObject().put("compositions", values)
    }

    /** These are typed calculator fields; descriptive text is deliberately unusable as ingredients. */
    @Synchronized fun estimate(request: NativeLoopbackServer.Request): JSONObject {
        val items = request.json().getJSONArray("items")
        check(items.length() == 1)
        val item = items.getJSONObject(0)
        check(item.getString("snapshot_id") == SNAPSHOT_ID)
        check(item.getString("label") == FOOD_NAME)
        val quantity = if (item.has("quantity")) {
            check(item.get("quantity") is String)
            check(item.getString("unit") == "g")
            BigDecimal(item.getString("quantity"))
        } else {
            check(item.getString("reference_portion_id") == REFERENCE_PORTION_ID)
            check(item.get("portion_count") is String)
            BigDecimal("75.00").multiply(BigDecimal(item.getString("portion_count")))
        }
        fun amount(per100: String): String = BigDecimal(per100).multiply(quantity)
            .divide(BigDecimal("100"), 2, RoundingMode.HALF_UP).toPlainString()
        val energy = amount("52.00")
        return JSONObject().put("recorded", false).put("message", "No client-readable ingredients here")
            .put("items", JSONArray().put(JSONObject().put("label", FOOD_NAME)
                .put("energy_kcal", energy).put("estimated", true)))
            .put("totals", JSONObject().put("energy_kcal", total(energy, true))
                .put("protein_g", total(amount("0.20"), true)).put("fat_g", total("0", false))
                .put("carbohydrate_g", total(amount("14.00"), true)).put("fiber_g", total("0", false)))
    }

    /** Daily server totals mirror StatsView.test.ts, including its empty day. */
    @Synchronized fun range(path: String): JSONObject {
        val query = Uri.parse(path)
        val start = LocalDate.parse(requireNotNull(query.getQueryParameter("start")))
        val end = LocalDate.parse(requireNotNull(query.getQueryParameter("end")))
        check(!end.isBefore(start) && start.plusDays(30) >= end)
        val incompleteDay = start.plusDays(2)
        val values = JSONArray()
        var current = start
        var index = 0
        while (!current.isAfter(end)) {
            val kcal = listOf("1950", "2500", "1500").getOrNull(index)
            values.put(JSONObject().put("date", current.toString()).put("logged", kcal != null)
                .put("energy_kcal", kcal ?: "0").put("energy_complete", current != incompleteDay)
                .put("protein_g", if (kcal != null) "80" else "0").put("protein_complete", current != incompleteDay)
                .put("fat_g", "0").put("carbohydrate_g", "0").put("completed", index == 0)
                .put("target", if (kcal == null) JSONObject.NULL else "2000")
                .put("macro_targets", JSONObject.NULL).put("kcal_by_kind", JSONObject().also {
                    if (kcal != null) it.put("lunch", kcal)
                }))
            current = current.plusDays(1)
            index++
        }
        return JSONObject().put("days", values)
    }

    @Synchronized fun export(path: String): Pair<String, String> = when (Uri.parse(path).getQueryParameter("format")) {
        "csv" -> "text/csv; charset=utf-8" to EXPORT_CSV
        "json" -> "application/json" to "[{\"name\":\"$FOOD_NAME\",\"quantity\":\"75.00\",\"unit\":\"g\"}]"
        else -> error("Unexpected nutrition export format")
    }

    @Synchronized fun day(requested: String): JSONObject = copy(days.getOrPut(requested) { seed(requested) })

    @Synchronized fun week(path: String): JSONObject {
        val query = Uri.parse(path)
        val start = LocalDate.parse(requireNotNull(query.getQueryParameter("start")))
        val end = LocalDate.parse(requireNotNull(query.getQueryParameter("end")))
        check(!end.isBefore(start) && start.plusDays(30) >= end)
        val result = JSONArray()
        var current = start
        while (!current.isAfter(end)) {
            val value = day(current.toString())
            result.put(JSONObject().put("date", current.toString()).put("completed", false)
                .put("energy_kcal", value.getJSONObject("totals").getJSONObject("energy_kcal").getString("known"))
                .put("target", "2000.00"))
            current = current.plusDays(1)
        }
        return JSONObject().put("days", result)
    }

    @Synchronized fun item(kind: String, id: String): JSONObject? {
        if (kind != "entry" && kind != "dish") return null
        val entry = findEntry(id) ?: return null
        return JSONObject().put("id", id).put("kind", kind).put("date", entry.getString("local_date"))
            .put("revision", entry.getInt("revision")).put("status", "active")
    }

    @Synchronized fun lookup(key: String): Pair<Int, JSONObject> = when {
        !lookupOnline -> 503 to JSONObject()
        receipts[key] == null -> 404 to JSONObject()
        else -> 200 to copy(receipts.getValue(key))
    }

    /** Returns null only after durable admission, deliberately losing the HTTP response. */
    @Synchronized fun operate(request: NativeLoopbackServer.Request): Pair<Int, JSONObject>? {
        val key = requireNotNull(request.headers["idempotency-key"])
        UUID.fromString(key)
        receipts[key]?.let {
            check(bodies.getValue(key) == request.body) { "A nutrition key changed its body" }
            return 200 to copy(it)
        }
        val body = request.json()
        val payload = body.getJSONObject("payload")
        val expected = body.getJSONObject("expected_revisions")
        val kind = body.getString("kind")
        if (kind in setOf("entry.add_many", "composition.log", "dish.log", "food.favorite")) {
            val receipt = addOperation(kind, payload, expected)
            bodies[key] = request.body
            receipts[key] = receipt
            return 201 to copy(receipt)
        }
        val previousOperation = if (kind == "operation.undo") payload.getString("operation_id") else null
        val previous = previousOperation?.let { operation -> receipts.values.single { it.getString("operation_id") == operation } }
        val id = previous?.getString("entry_id") ?: payload.getString("entry_id")
        val entry = requireNotNull(findEntry(id))
        val revision = entry.getInt("revision")
        if (conflictNext) {
            conflictNext = false
            update(id, "105.00", revision + 1)
            return 409 to JSONObject().put("error", "conflict").put("message", "Entry revision changed")
        }
        if (expected.optInt(id, -1) != revision) {
            return 409 to JSONObject().put("error", "conflict").put("message", "Entry revision changed")
        }
        val operation = UUID.randomUUID().toString()
        before[operation] = copy(entry)
        when (kind) {
            "entry.update" -> {
                check(payload.get("quantity") is String) { "Decimal quantities must stay strings" }
                update(id, payload.getString("quantity"), revision + 1)
            }
            "operation.undo" -> {
                val originalOperation = requireNotNull(previousOperation)
                check(originalOperation !in undone)
                val original = requireNotNull(before[originalOperation])
                update(id, original.getString("quantity"), revision + 1)
                undone += originalOperation
            }
            else -> error("Unexpected synthetic nutrition operation: $kind")
        }
        val receipt = JSONObject().put("operation_id", operation).put("kind", kind)
            .put("event_seq", receipts.size + 1)
        if (previousOperation == null) {
            val current = requireNotNull(findEntry(id))
            receipt.put("entry_id", id).put("revision", revision + 1)
                .put("date", current.getString("local_date")).put("name", current.getString("name"))
                .put("quantity", current.getString("quantity")).put("unit", current.getString("unit"))
                .put("energy_kcal", current.getJSONObject("nutrients").get("energy_kcal"))
                .put("estimated", false).put("meal_id", current.getString("meal_id"))
                .put("meal_kind", current.getString("meal_kind"))
        } else {
            receipt.put("undone_operation_id", previousOperation)
                .put("applied_entries", JSONArray().put(JSONObject().put("entry_id", id).put("revision", revision + 1)))
                .put("applied_dishes", JSONArray())
        }
        bodies[key] = request.body
        receipts[key] = receipt
        if (disconnectAdmission) {
            disconnectAdmission = false
            return null
        }
        return 201 to copy(receipt)
    }

    /** A change made elsewhere, announced by the shared account SSE in smoke tests. */
    @Synchronized fun externalChange() {
        val entry = requireNotNull(findEntry(ENTRY_ID))
        update(ENTRY_ID, "175.00", entry.getInt("revision") + 1)
    }

    private fun findEntry(id: String): JSONObject? {
        days.getOrPut(date) { seed(date) }
        return days.values.asSequence().flatMap { value ->
            val entries = value.getJSONArray("entries")
            (0 until entries.length()).asSequence().map(entries::getJSONObject)
        }.firstOrNull { it.getString("id") == id }
    }

    private fun update(id: String, quantity: String, revision: Int) {
        days.values.forEach { value ->
            var changed = false
            listOf("entries", "items").forEach { field ->
                val entries = value.getJSONArray(field)
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    if (entry.getString("id") == id) {
                        entry.put("quantity", quantity).put("revision", revision)
                        changed = true
                    }
                }
            }
            if (changed) {
                value.put("day_revision", value.getInt("day_revision") + 1)
                // The test server owns the new total; the client cannot infer it from the portion.
                value.getJSONObject("totals").getJSONObject("energy_kcal").put("known", "512.25")
            }
        }
    }

    private fun seed(day: String): JSONObject {
        fun entry(id: String, name: String, meal: String) = JSONObject().put("id", id).put("name", name)
            .put("revision", 4).put("quantity", "100.00").put("unit", "g").put("local_date", day)
            .put("meal_id", "synthetic-$meal").put("meal_kind", meal)
            .put("logged_at", "${day}T09:00:00Z")
            .put("nutrients", JSONObject().put("energy_kcal", "195.25").put("protein_g", "7.50")
                .put("fat_g", JSONObject.NULL).put("carbohydrate_g", "20.00").put("fiber_g", JSONObject.NULL))
        val main = entry(if (day == date) ENTRY_ID else "entry-$day", "Synthetic porridge", "breakfast")
        val component = entry("component-$day", "Synthetic soup component", "lunch")
            .put("dish_id", "dish-$day").put("snapshot_id", SOUP_SNAPSHOT_ID)
        val dish = JSONObject().put("id", "dish-$day").put("kind", "dish").put("name", "Synthetic soup")
            .put("revision", 2).put("quantity", "300.00").put("unit", "g").put("local_date", day)
            .put("components", JSONArray().put(copy(component)))
            .put("nutrients", JSONObject().put("energy_kcal", "232.50").put("protein_g", "10.00")
                .put("fat_g", "5.00").put("carbohydrate_g", JSONObject.NULL).put("fiber_g", JSONObject.NULL))
        return JSONObject().put("date", day).put("day_revision", 9).put("completed", false)
            .put("entries", JSONArray().put(copy(main)).put(copy(component)))
            .put("items", JSONArray().put(copy(main)).put(dish)).put("drafts", JSONArray())
            .put("totals", JSONObject().put("energy_kcal", total("427.75", false))
                .put("protein_g", total("17.50", true)).put("fat_g", total("5.00", false))
                .put("carbohydrate_g", total("20.00", false)).put("fiber_g", total("0", false)))
            .put("goal", JSONObject().put("id", "synthetic-goal").put("calorie_target", "2000.00")
                .put("effective_local_date", day).put("revision", 1)
                .put("macro_targets", JSONObject().put("protein_g", "100.00").put("fat_g", "60.00")))
            .put("remaining_kcal", "1572.25").put("remaining_complete", false)
    }

    private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())

    private fun total(known: String, complete: Boolean) = JSONObject().put("known", known).put("complete", complete)

    private fun food(): JSONObject = JSONObject().put("food_id", FOOD_ID).put("snapshot_id", SNAPSHOT_ID)
        .put("name", FOOD_NAME).put("basis_quantity", "100.00").put("basis_unit", "g")
        .put("energy_kcal", "52.00").put("protein_g", "0.20").put("fat_g", JSONObject.NULL)
        .put("carbohydrate_g", "14.00").put("fiber_g", JSONObject.NULL)
        .put("portions", JSONArray().put(JSONObject().put("id", REFERENCE_PORTION_ID)
            .put("name", "Synthetic half apple").put("quantity", "75.00").put("unit", "g")))
        .put("saved_portions", JSONArray())

    private fun addOperation(kind: String, payload: JSONObject, expected: JSONObject): JSONObject {
        val receipt = JSONObject().put("operation_id", UUID.randomUUID().toString()).put("kind", kind)
            .put("event_seq", receipts.size + 1)
        if (kind == "food.favorite") {
            check(payload.getString("food_id") == FOOD_ID)
            check(expected.getInt(FOOD_ID) == preferenceRevision)
            favorite = payload.getBoolean("favorite")
            preferenceRevision++
            return receipt.put("food_id", FOOD_ID).put("name", FOOD_NAME).put("favorite", favorite)
                .put("revision", preferenceRevision)
        }
        check(expected.length() == 0) { "A repeated dish or added food must be a new operation" }
        val day = payload.getString("date")
        val value = days.getOrPut(day) { seed(day) }
        val entryId = UUID.randomUUID().toString()
        val item = if (kind == "entry.add_many" || kind == "dish.log") {
            val items = payload.getJSONArray("items")
            check(items.length() == 1)
            items.getJSONObject(0).also {
                check(it.getString("snapshot_id") == if (kind == "dish.log") SOUP_SNAPSHOT_ID else SNAPSHOT_ID)
                check(it.getString("label") == if (kind == "dish.log") "Synthetic soup component" else FOOD_NAME)
                if (kind == "dish.log") check(payload.getString("name") == "Synthetic soup")
            }
        } else {
            check(payload.getString("composition_id") == COMPOSITION_ID)
            check(payload.get("count") is String)
            JSONObject().put("label", FOOD_NAME).put("quantity", "150.00").put("unit", "g")
        }
        val quantity = if (item.has("quantity")) item.getString("quantity") else {
            check(item.getString("reference_portion_id") == REFERENCE_PORTION_ID)
            check(item.get("portion_count") is String)
            "75.00"
        }
        val dishId = if (kind != "entry.add_many") UUID.randomUUID().toString() else null
        val entry = JSONObject().put("id", entryId).put("revision", 1).put("name", item.getString("label"))
            .put("food_id", FOOD_ID).put("snapshot_id", if (kind == "dish.log") SOUP_SNAPSHOT_ID else SNAPSHOT_ID)
            .put("quantity", quantity).put("unit", "g")
            .put("local_date", day).put("logged_at", "${day}T12:00:00Z")
            .put("meal_id", "new-meal-$entryId").put("meal_kind", payload.optString("meal_kind", "lunch"))
            .put("nutrients", JSONObject().put("energy_kcal", "39.00").put("protein_g", "0.15")
                .put("fat_g", JSONObject.NULL).put("carbohydrate_g", "10.50").put("fiber_g", JSONObject.NULL))
        if (dishId != null) entry.put("dish_id", dishId)
        value.getJSONArray("entries").put(copy(entry))
        if (kind == "entry.add_many") value.getJSONArray("items").put(copy(entry))
        else {
            value.getJSONArray("items").put(JSONObject().put("id", dishId).put("kind", "dish")
                .put("name", if (kind == "dish.log") payload.getString("name") else COMPOSITION_NAME)
                .put("revision", 1).put("quantity", if (kind == "dish.log") payload.getString("quantity") else "150.00")
                .put("unit", "g")
                .put("local_date", day).put("components", JSONArray().put(copy(entry)))
                .put("nutrients", copy(entry.getJSONObject("nutrients"))))
            receipt.put("dish_id", dishId).put("revision", 1)
        }
        value.put("day_revision", value.getInt("day_revision") + 1)
        // Deliberately use an authoritative server total, not a sum of the rendered rows.
        value.getJSONObject("totals").getJSONObject("energy_kcal").put("known", "466.75")
        return receipt.put("date", day).put("applied_entries", JSONArray().put(JSONObject()
            .put("entry_id", entryId).put("revision", 1).put("name", FOOD_NAME).put("quantity", quantity)
            .put("unit", "g").put("energy_kcal", "39.00")))
    }

    companion object {
        const val ENTRY_ID = "99999999-9999-4999-8999-999999999999"
        const val FOOD_ID = "88888888-8888-4888-8888-888888888888"
        const val SNAPSHOT_ID = "77777777-7777-4777-8777-777777777777"
        const val SOUP_SNAPSHOT_ID = "77777777-7777-4777-8777-000000000001"
        const val REFERENCE_PORTION_ID = "66666666-6666-4666-8666-666666666666"
        const val COMPOSITION_ID = "55555555-5555-4555-8555-555555555555"
        const val FOOD_NAME = "Synthetic apple"
        const val COMPOSITION_NAME = "Synthetic fruit bowl"
        const val EXPORT_CSV = "name,quantity,unit\r\nSynthetic apple,75.00,g\r\n"
    }
}
