package assistant.mobile.core.personal

import assistant.mobile.core.journal.Submission
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

val PERSONAL_SOURCE_OPERATION_KINDS = setOf("personal.resolution.policy.set", "personal.resolution.policies.set",
    "personal.source.sync.request", "personal.source.automatic.update", "personal.source.update", "personal.source.create",
    "personal.resolution.source.link", "personal.resolution.source.unlink")

fun sourcePolicyPersonalOperation(draft: PersonalSourcePolicyDraft, sources: List<PersonalSourceEntry>,
    policies: List<PersonalSourcePolicy>): PersonalOperation {
    require(LocalDate.parse(draft.effectiveFrom).toString() == draft.effectiveFrom)
    val row = requireNotNull(PERSONAL_SOURCE_ROWS.find { it.key == draft.rowKey })
    val source = draft.sourceId?.let { id -> requireNotNull(sources.find { it.id == id && it.active }) }
    val metrics = row.metrics.filter { metric ->
        if (source != null) metric in source.metricKeys
        else personalPolicyOn(policies, metric, draft.effectiveFrom) != null || sources.any { metric in it.metricKeys }
    }
    require(metrics.isNotEmpty())
    val expected = mutableMapOf<String, Long>()
    val orders = metrics.map { metric ->
        val existing = policies.find { it.metric == metric && it.effectiveFrom == draft.effectiveFrom }
        existing?.id?.let { expected[it] = existing.revision }
        buildJsonObject { put("metric", metric); put("revision", existing?.revision ?: 0)
            put("source_ids", JsonArray(draft.sourceId?.let { listOf(JsonPrimitive(it)) } ?: emptyList())) }
    }
    return if (orders.size == 1) PersonalOperation("personal.resolution.policy.set",
        JsonObject(orders.single() + ("effective_from" to JsonPrimitive(draft.effectiveFrom))), expected)
    else PersonalOperation("personal.resolution.policies.set", buildJsonObject {
        put("effective_from", draft.effectiveFrom); put("policies", JsonArray(orders))
    }, expected)
}

/** Server receipts must prove the exact policy, source and revision admitted to the journal. */
fun validatePersonalSourceReceipt(entry: Submission, bytes: ByteArray) {
    val command = personalOperationObject(entry.body)
    val kind = command.sText("kind")
    require(kind in PERSONAL_SOURCE_OPERATION_KINDS)
    val payload = requireNotNull(command.sObjectOrNull("payload"))
    val expected = requireNotNull(command.sObjectOrNull("expected_revisions"))
    val receipt = personalOperationObject(bytes)
    require(receipt.sText("kind") == kind && receipt.sText("operation_id").isNotBlank())
    when (kind) {
        "personal.resolution.policy.set", "personal.resolution.policies.set" -> {
            val proposals = if (kind.endsWith("policies.set")) payload.sArray("policies").map { it.jsonObject } else listOf(payload)
            val saved = if (kind.endsWith("policies.set")) receipt.sArray("policies").map { it.jsonObject } else listOf(requireNotNull(receipt.sObjectOrNull("policy")))
            require(saved.size == proposals.size && saved.map { it.sText("metric") }.toSet().size == saved.size)
            for (proposal in proposals) {
                val policy = decodePersonalSourcePolicy(saved.single { it.sText("metric") == proposal.sText("metric") })
                require(policy.id != null && policy.effectiveFrom == payload.sText("effective_from") &&
                    policy.sourceIds == proposal.sArray("source_ids").map { if (it == JsonNull) null else (it as JsonPrimitive).content } &&
                    policy.revision == proposal.sLong("revision") + 1)
                expected[policy.id]?.let { require((it as JsonPrimitive).content.toLong() + 1 == policy.revision) }
            }
        }
        "personal.source.sync.request" -> require(receipt.sText("source_id") == payload.sText("source_id") &&
            receipt.sText("run_id").isNotBlank() && receipt.sText("state") in setOf("queued", "running"))
        "personal.source.automatic.update" -> {
            val id = payload.sText("source_id")
            require(receipt.sText("source_id") == id && receipt.sBoolean("automatic") == payload.sBoolean("automatic") &&
                receipt.sLong("revision") == (expected.getValue(id) as JsonPrimitive).content.toLong() + 1)
        }
        "personal.source.create", "personal.source.update" -> {
            val saved = requireNotNull(receipt.sObjectOrNull("source"))
            val id = saved.sText("id")
            if (kind.endsWith("update")) require(id == payload.sText("source_id") &&
                saved.sLong("revision") == (expected.getValue(id) as JsonPrimitive).content.toLong() + 1)
            else require(saved.sLong("revision") > 0 && saved.sStrings("kinds").toSet() == payload.sStrings("kinds").toSet())
            for (field in listOf("name", "status").filter { it in payload }) require(saved[field] == payload[field])
        }
        "personal.resolution.source.link" -> {
            val ids = payload.sStrings("source_ids")
            val saved = receipt.sArray("source_identities").map { it.jsonObject }
            val revisions = requireNotNull(payload.sObjectOrNull("identity_revisions"))
            require(saved.map { it.sText("source_id") }.toSet() == ids.toSet())
            require(saved.map { it.sText("physical_id") }.toSet().size == 1)
            for (row in saved) require(row.sBoolean("confirmed") && row.sLong("revision") ==
                (revisions.getValue(row.sText("source_id")) as JsonPrimitive).content.toLong() + 1)
        }
        "personal.resolution.source.unlink" -> {
            val row = requireNotNull(receipt.sObjectOrNull("source_identity"))
            require(row.sText("source_id") == payload.sText("source_id") && row.sTextOrNull("physical_id") == null &&
                !row.sBoolean("confirmed") && row.sLong("revision") == payload.sLong("identity_revision") + 1)
        }
    }
}
