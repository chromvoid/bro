package assistant.mobile.core.personal

import assistant.mobile.core.journal.Submission
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun personalSensorExclusionOperation(sample: PersonalSensorSample): PersonalOperation = PersonalOperation(
    if (sample.excluded) "personal.sensor.restore" else "personal.sensor.exclude",
    buildJsonObject { put("sample_id", sample.id); put("annotation_revision", sample.annotationRevision) },
    mapOf(sample.id to sample.revision),
)

fun validatePersonalSensorSubmissionReceipt(entry: Submission, bytes: ByteArray) {
    val command = personalOperationObject(entry.body)
    val kind = command.hText("kind")
    require(kind in setOf("personal.sensor.exclude", "personal.sensor.restore"))
    val payload = command.hObject("payload")
    val expected = command.hObject("expected_revisions")
    val row = personalObject(bytes)
    require(row.hText("kind") == kind && row.hText("operation_id").isNotBlank())
    val id = payload.hText("sample_id")
    require(row.hText("sample_id") == id && expected.keys == setOf(id) && expected.hLong(id) > 0)
    require(row.hBoolean("excluded") == (kind == "personal.sensor.exclude"))
    require(row.hLong("annotation_revision") > payload.hLong("annotation_revision") && row.hText("annotation_id").isNotBlank())
}
