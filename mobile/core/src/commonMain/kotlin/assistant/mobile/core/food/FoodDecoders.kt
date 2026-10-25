package assistant.mobile.core.food

import assistant.mobile.core.NativeJson
import assistant.mobile.core.journal.Submission
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

const val FOOD_OPERATIONS = "/v1/nutrition/operations"
const val FOOD_LOOKUP = "$FOOD_OPERATIONS/by-key/"

fun decodeFoodDay(bytes: ByteArray): FoodDay {
    val row = foodObject(bytes)
    require(row.containsKey("goal") && row.containsKey("remaining_kcal"))
    val date = row.date("date")
    return FoodDay(date, row.revision("day_revision", allowZero = true), row.boolean("completed"),
        row.array("entries").map { decodeFoodEntry(it.jsonObject) },
        row.array("items").map { element ->
            val item = element.jsonObject
            when (item.optionalText("kind")) {
                "dish" -> decodeFoodDish(item)
                null, "entry" -> decodeFoodEntry(item)
                else -> error("Unsupported food item kind")
            }
        }, row.obj("totals").mapValues { (_, element) ->
            val total = element.jsonObject
            FoodTotal(total.decimal("known"), total.boolean("complete"))
        }, row.optionalObject("goal")?.let { goal ->
            FoodGoal(goal.text("id"), goal.decimal("calorie_target"), goal.date("effective_local_date"),
                goal.revision("revision"), goal.optionalObject("macro_targets")?.mapValues { (_, amount) -> decimalOrNull(amount) } ?: emptyMap())
        }, row.nullableDecimal("remaining_kcal"), row.boolean("remaining_complete"),
        row.array("drafts").map { element ->
            val draft = element.jsonObject
            FoodUnresolvedDraft(draft.text("id"), draft.text("label"), draft.text("status"),
                draft.array("missing_fields").map(::string), draft.revision("revision"), draft.mealKind())
        })
}

private fun decodeFoodEntry(row: JsonObject): FoodEntry = FoodEntry(
    id = row.text("id"), revision = row.revision("revision"), quantity = row.decimal("quantity"),
    unit = row.text("unit"), date = row.date("local_date"),
    name = row.optionalText("name_ru")?.takeIf { it.isNotEmpty() } ?: row.optionalText("name") ?: "—",
    nutrients = nutrients(row), estimated = row.optionalBoolean("estimated") ?: false,
    mealKind = row.mealKind(), mealId = row.optionalText("meal_id"), dishId = row.optionalText("dish_id"),
    foodId = row.optionalText("food_id"), snapshotId = row.optionalText("snapshot_id"),
    loggedAt = row.optionalText("logged_at")?.also { Instant.parse(it) },
    estimationEvidence = row.optionalText("estimation_evidence"), sourceProvider = row.optionalText("source_provider"),
)

private fun decodeFoodDish(row: JsonObject): FoodDish = FoodDish(
    row.text("id"), row.revision("revision"), row.decimal("quantity"), row.text("unit"),
    row.optionalText("local_date")?.also { LocalDate.parse(it) }, row.text("name"),
    row.array("components").map { decodeFoodEntry(it.jsonObject) }, nutrients(row), row.optionalBoolean("estimated") ?: false,
)

private fun nutrients(row: JsonObject): Map<String, String?> =
    row.optionalObject("nutrients")?.filterKeys { it in FOOD_NUTRIENTS }?.mapValues { (_, amount) -> decimalOrNull(amount) } ?: emptyMap()

fun decodeFoodWeek(bytes: ByteArray): List<FoodWeekDay> = foodObject(bytes).array("days").map { element ->
    val row = element.jsonObject
    FoodWeekDay(row.date("date"), row.decimal("energy_kcal"), row.boolean("completed"), row.nullableDecimal("target"))
}

fun decodeFoodItemTarget(bytes: ByteArray): FoodItemTarget = foodObject(bytes).let { row ->
    FoodItemTarget(row.text("kind").also { require(it in setOf("entry", "dish")) }, row.text("id"),
        row.date("date"), row.revision("revision"), row.text("status").also { require(it in setOf("active", "deleted")) })
}

fun decodeFoodReceipt(bytes: ByteArray): FoodReceipt = foodObject(bytes).let { row ->
    row.optionalText("status")?.let { require(it == "applied") { "Unsupported nutrition receipt status" } }
    FoodReceipt(
        operationId = row.text("operation_id").also { require(it.isNotBlank()) }, kind = row.text("kind"),
        entryId = row.optionalText("entry_id"), dishId = row.optionalText("dish_id"), revision = row.optionalRevision("revision"),
        expectedRevisions = row.optionalObject("expected_revisions")?.mapValues { (_, value) -> revision(value) } ?: emptyMap(),
        appliedEntries = row.optionalArray("applied_entries")?.map { element ->
            val entry = element.jsonObject
            FoodAppliedEntry(entry.text("entry_id"), entry.optionalRevision("revision"))
        } ?: emptyList(),
        appliedDishes = row.optionalArray("applied_dishes")?.associate { element ->
            val dish = element.jsonObject
            dish.text("dish_id") to dish.revision("revision")
        } ?: emptyMap(),
        undoStatus = row.optionalText("undo_status")?.also { require(it in setOf("available", "undone", "changed", "unsupported")) },
        undoneOperationId = row.optionalText("undone_operation_id"),
        previousMeals = row.optionalArray("meals")?.map { element ->
            val meal = element.jsonObject
            FoodPreviousMeal(meal.text("meal_id"), meal.optionalText("previous_kind")?.let(FoodMealKind::fromWire))
        } ?: emptyList(),
        foodId = row.optionalText("food_id"), snapshotId = row.optionalText("snapshot_id"),
        compositionId = row.optionalText("composition_id"),
    )
}

/** Called inside SubmissionCoordinator before the receipt can settle durable delivery state. */
fun validateFoodSubmissionReceipt(entry: Submission, bytes: ByteArray) {
    if (entry.endpoint != FOOD_OPERATIONS) return
    val command = foodObject(entry.body)
    val receipt = decodeFoodReceipt(bytes)
    require(receipt.kind == command.text("kind")) { "Nutrition receipt kind differs from submission" }
    val payload = command.obj("payload")
    val expected = command.obj("expected_revisions")
    when (receipt.kind) {
        "entry.update", "entry.delete" -> {
            val id = payload.text("entry_id")
            require(receipt.entryId == id && receipt.revision != null && receipt.revision > expected.revision(id))
        }
        "dish.update", "dish.delete" -> {
            val id = payload.text("dish_id")
            require(receipt.dishId == id && receipt.revision != null && receipt.revision > expected.revision(id))
        }
        "dish.group" -> {
            require(receipt.dishId != null && receipt.revision != null)
            require(receipt.appliedEntries.map { it.id }.toSet() == payload.array("entry_ids").map(::string).toSet())
        }
        "entry.add_many", "dish.log", "composition.log" -> {
            require(receipt.appliedEntries.isNotEmpty() && receipt.appliedEntries.all { it.revision != null })
            require(receipt.appliedEntries.map { it.id }.distinct().size == receipt.appliedEntries.size)
            if (receipt.kind != "composition.log") require(receipt.appliedEntries.size == payload.array("items").size)
            if (receipt.kind != "entry.add_many") require(receipt.dishId != null && receipt.revision != null)
            val row = foodObject(bytes)
            row.optionalArray("unresolved")?.let { require(it.isEmpty()) }
            row.optionalText("date")?.let { require(it == payload.text("date")) }
        }
        "food.favorite" -> {
            val row = foodObject(bytes)
            val id = payload.text("food_id")
            require(receipt.foodId == id && row.boolean("favorite") == payload.boolean("favorite") &&
                receipt.revision != null && receipt.revision > expected.revision(id, allowZero = true))
        }
        "composition.save" -> require(receipt.compositionId?.isNotBlank() == true)
        "food.create" -> require(receipt.foodId?.isNotBlank() == true && receipt.snapshotId?.isNotBlank() == true)
        "operation.undo" -> require(receipt.undoneOperationId == payload.text("operation_id") &&
            receipt.appliedEntries.isNotEmpty() && receipt.appliedEntries.all { it.revision != null })
        "meal.set_kind" -> require(receipt.previousMeals.map { it.id }.toSet() == payload.array("meal_ids").map(::string).toSet())
        "day.complete" -> {
            val row = foodObject(bytes)
            require(row.date("date") == payload.date("date") && row.boolean("completed") &&
                row.revision("revision") > payload.revision("revision", allowZero = true))
        }
        else -> error("Unsupported nutrition operation receipt")
    }
}

internal fun foodObject(bytes: ByteArray): JsonObject =
    NativeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
internal fun string(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: error("Expected a string")
internal fun JsonObject.text(key: String): String = string(requireNotNull(this[key]))
internal fun JsonObject.optionalText(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.let(::string)
internal fun JsonObject.date(key: String): String = text(key).also { require(LocalDate.parse(it).toString() == it) }
internal fun JsonObject.array(key: String): JsonArray = requireNotNull(this[key] as? JsonArray)
internal fun JsonObject.optionalArray(key: String): JsonArray? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonArray ?: error("Expected array") }
internal fun JsonObject.obj(key: String): JsonObject = requireNotNull(this[key] as? JsonObject)
internal fun JsonObject.optionalObject(key: String): JsonObject? = this[key]?.takeUnless { it == JsonNull }?.let { it as? JsonObject ?: error("Expected object") }
internal fun JsonObject.boolean(key: String): Boolean = requireNotNull((this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull)
internal fun JsonObject.optionalBoolean(key: String): Boolean? = this[key]?.takeUnless { it == JsonNull }?.let {
    requireNotNull((it as? JsonPrimitive)?.takeUnless { primitive -> primitive.isString }?.booleanOrNull)
}
internal fun JsonObject.revision(key: String, allowZero: Boolean = false): Long = revision(requireNotNull(this[key]), allowZero)
internal fun JsonObject.optionalRevision(key: String): Long? = this[key]?.takeUnless { it == JsonNull }?.let { revision(it) }
private fun revision(value: JsonElement, allowZero: Boolean = false): Long =
    requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull).also { require(it >= if (allowZero) 0 else 1) }
internal fun JsonObject.decimal(key: String): String = requireNotNull(decimalOrNull(requireNotNull(this[key])))
internal fun JsonObject.nullableDecimal(key: String): String? = decimalOrNull(this[key])
internal fun decimalOrNull(value: JsonElement?): String? {
    if (value == null || value == JsonNull) return null
    val text = string(value)
    require(plainFoodDecimal(text) != null) { "Expected decimal string" }
    return text
}
private fun JsonObject.mealKind(): FoodMealKind? = optionalText("meal_kind")?.let(FoodMealKind::fromWire)
