package assistant.mobile.core.assistant

import assistant.mobile.core.GeneratedStrings
import assistant.mobile.core.LocalizedString
import assistant.mobile.core.NativeJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Public provider measurements. Missing facts remain unknown; no totals are inferred. */
@Serializable
data class AssistantUsage(
    val calls: Long? = null,
    @SerialName("input_total_tokens") val inputTotalTokens: Long? = null,
    @SerialName("input_uncached_tokens") val inputUncachedTokens: Long? = null,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("model_duration_ms") val modelDurationMs: Double? = null,
    @SerialName("first_public_text_ms") val firstPublicTextMs: Double? = null,
) {
    init {
        require(listOf(calls, inputTotalTokens, inputUncachedTokens, cacheReadTokens, cacheWriteTokens,
            outputTokens, totalTokens).all { it == null || it in 0..MAX_USAGE_COUNT })
        require(listOf(modelDurationMs, firstPublicTextMs).all {
            it == null || it.isFinite() && it >= 0 && it <= MAX_USAGE_COUNT.toDouble()
        })
    }
}

fun parseAssistantUsage(value: JsonElement?): AssistantUsage? = runCatching {
    val document = value as? JsonObject ?: return null
    // JSON's numeric primitive type matters: quoted numbers are not provider measurements.
    for (field in usageCountFields) {
        val item = document[field] ?: continue
        if (item == JsonNull) continue
        require(item is JsonPrimitive && !item.isString && item.longOrNull != null)
    }
    for (field in usageDurationFields) {
        val item = document[field] ?: continue
        if (item == JsonNull) continue
        require(item is JsonPrimitive && !item.isString && item.doubleOrNull != null)
    }
    NativeJson.decodeFromJsonElement(AssistantUsage.serializer(), document)
}.getOrNull()

data class AssistantUsageRow(val label: LocalizedString, val value: Number?, val milliseconds: Boolean = false)

fun AssistantUsage.rows(): List<AssistantUsageRow> = listOf(
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.calls, calls),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.inputTotal, inputTotalTokens),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.inputUncached, inputUncachedTokens),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.cacheRead, cacheReadTokens),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.cacheWrite, cacheWriteTokens),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.output, outputTokens),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.total, totalTokens),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.modelDuration, modelDurationMs, milliseconds = true),
    AssistantUsageRow(GeneratedStrings.Assistant.Usage.firstPublicText, firstPublicTextMs, milliseconds = true),
)

private const val MAX_USAGE_COUNT = 9_007_199_254_740_991L

private val usageCountFields = setOf("calls", "input_total_tokens", "input_uncached_tokens", "cache_read_tokens",
    "cache_write_tokens", "output_tokens", "total_tokens")
private val usageDurationFields = setOf("model_duration_ms", "first_public_text_ms")
