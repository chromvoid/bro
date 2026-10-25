package assistant.mobile.core.bodyassets

import assistant.mobile.core.NativeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

const val BODY_ASSET_MANIFEST_PATH = "/body-assets.json"
const val MAX_BODY_ASSET_BYTES = 128L * 1024 * 1024
const val MAX_BODY_ASSET_SET_BYTES = 256L * 1024 * 1024

data class BodyAsset(
    val name: String,
    val path: String,
    val sha256: String,
    val bytes: Long,
    val contentType: String,
)

data class BodyAssetManifest(val files: Map<String, BodyAsset>) {
    val totalBytes: Long get() = files.values.sumOf { it.bytes }
    val hashes: Set<String> get() = files.values.mapTo(mutableSetOf()) { it.sha256 }
}

enum class BodyAssetError { Network, Unauthorized, Forbidden, InvalidManifest, Integrity, Storage }
class BodyAssetFailure(val error: BodyAssetError) : Exception(error.name)

/** Public build output is data, never authority to download from an arbitrary URL or path. */
fun decodeBodyAssetManifest(bytes: ByteArray): BodyAssetManifest {
    fun invalid(): Nothing = throw BodyAssetFailure(BodyAssetError.InvalidManifest)
    if (bytes.size !in 1..262_144) invalid()
    val root = runCatching { NativeJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: invalid()
    val version = (root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    if (version != 1) invalid()
    val files = root["files"] as? JsonObject ?: invalid()
    if (files.size !in 1..64) invalid()
    val decoded = files.mapValues { (name, value) ->
        if (name.isEmpty() || name.length > 96 || name.any { it !in BODY_ASSET_NAME_CHARACTERS } || name.startsWith('.')) invalid()
        val fields = value as? JsonObject ?: invalid()
        fun string(field: String): String = (fields[field] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        val sha = string("sha256")
        val path = string("path")
        val type = string("content_type")
        val size = (fields["bytes"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid()
        if (!isBodyAssetSha256(sha) || path != "/assets/body-$sha-$name" ||
            size !in 1..MAX_BODY_ASSET_BYTES || type != bodyAssetContentType(name)) invalid()
        BodyAsset(name, path, sha, size, type)
    }
    val manifest = BodyAssetManifest(decoded)
    if (manifest.totalBytes > MAX_BODY_ASSET_SET_BYTES) invalid()
    return manifest
}

/** The platform streams into its native digest; the size/hash decision is shared by every target. */
data class BodyAssetVerification(val bytes: Long, val sha256: String)

fun verifyBodyAssetHash(asset: BodyAsset, actual: BodyAssetVerification) {
    if (actual.bytes != asset.bytes || actual.sha256 != asset.sha256 || !isBodyAssetSha256(actual.sha256))
        throw BodyAssetFailure(BodyAssetError.Integrity)
}

private const val BODY_ASSET_NAME_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
private fun isBodyAssetSha256(value: String): Boolean = value.length == 64 && value.all { it in "0123456789abcdef" }
private fun bodyAssetContentType(name: String): String? = when (name.substringAfterLast('.', "")) {
    "glb" -> "model/gltf-binary"
    "webp" -> "image/webp"
    "png" -> "image/png"
    "json" -> "application/json"
    "md" -> "text/markdown; charset=utf-8"
    else -> null
}
