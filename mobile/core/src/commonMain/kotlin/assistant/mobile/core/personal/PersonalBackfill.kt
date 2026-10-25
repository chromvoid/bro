package assistant.mobile.core.personal

import assistant.mobile.core.journal.Submission
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun personalBackfillOperation(from: String, to: String, timezone: String): PersonalOperation {
    val start = LocalDate.parse(from)
    val end = LocalDate.parse(to)
    require(start.toString() == from && end.toString() == to && start.daysUntil(end) in 0..365)
    TimeZone.of(timezone)
    return PersonalOperation("personal.derived.backfill", buildJsonObject {
        put("from", from); put("to", to); put("time_zone", timezone)
    })
}

fun decodePersonalBackfillReceipt(bytes: ByteArray): PersonalBackfillReceipt = personalObject(bytes).let { row ->
    require(row.hText("kind") == "personal.derived.backfill" && row.hText("operation_id").isNotBlank())
    require(row.hText("state") == "queued" && row.hInt("results_saved") == 0 && row.hBoolean("pending"))
    PersonalBackfillReceipt(row.hText("derivation_run_id").also { require(it.length == 64) }, row.hDate("from"), row.hDate("to"),
        row.hInstant("as_of"), row.hInt("scheduled_days").also { require(it in 1..366) })
}

fun validatePersonalBackfillSubmissionReceipt(entry: Submission, bytes: ByteArray) {
    val command = personalOperationObject(entry.body)
    require(command.hText("kind") == "personal.derived.backfill")
    val payload = command.hObject("payload")
    val receipt = decodePersonalBackfillReceipt(bytes)
    require(command.hObject("expected_revisions").isEmpty())
    require(receipt.from == payload.hDate("from") && receipt.to == payload.hDate("to"))
    require(receipt.scheduledDays == LocalDate.parse(receipt.from).daysUntil(LocalDate.parse(receipt.to)) + 1)
    TimeZone.of(payload.hText("time_zone"))
}

fun decodePersonalBackfillRun(bytes: ByteArray): PersonalBackfillRun = personalObject(bytes).let { row ->
    PersonalBackfillRun(row.hText("run_id").also { require(it.length == 64) },
        row.hText("state").also { require(it in setOf("queued", "succeeded", "failed", "cancelled")) },
        row.hDate("from"), row.hDate("to"), row.hDate("next_date"), row.hInstant("as_of"), row.hZone("time_zone"),
        row.hOptionalText("source_scope"), row.hText("trigger"), row.hInt("processed_days"),
        row.hInt("scheduled_days").also { require(it in 1..366) }, row.hInt("results_saved"), row.hInt("results_unchanged"),
        row.hOptionalText("error"), row.hInstant("created_at"), row.hOptionalText("finished_at")).also {
        require(it.processedDays <= it.scheduledDays && it.from <= it.to)
    }
}
