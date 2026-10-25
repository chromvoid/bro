package assistant.mobile.core.bodyassets

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun bodyDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun bodyAsset(name: String, bytes: ByteArray): BodyAsset {
    val digest = bodyDigest(bytes)
    val type = when (name.substringAfterLast('.')) {
        "glb" -> "model/gltf-binary"
        "webp" -> "image/webp"
        "json" -> "application/json"
        else -> "text/markdown; charset=utf-8"
    }
    return BodyAsset(name, "/assets/body-$digest-$name", digest, bytes.size.toLong(), type)
}

internal fun bodyManifestBytes(assets: List<BodyAsset>): ByteArray = JsonObject(mapOf(
    "version" to JsonPrimitive(1),
    "files" to JsonObject(assets.associate { asset -> asset.name to JsonObject(mapOf(
        "path" to JsonPrimitive(asset.path), "sha256" to JsonPrimitive(asset.sha256),
        "bytes" to JsonPrimitive(asset.bytes), "content_type" to JsonPrimitive(asset.contentType),
    )) }),
)).toString().encodeToByteArray()

class BodyAssetManifestTest {
    @Test
    fun parsesAllThreeVariantsAnimationsPostersAndExactProvenanceKeys() {
        val names = listOf("male", "female", "male_spartan").flatMap { listOf("$it.glb", "$it-lookaround.glb", "$it.webp") } +
            listOf("manifest.json", "rokoko-lookaround.json", "LICENSE.MPFB.md", "LICENSE.CC0.md", "ROKOKO_ANIMATION_NOTICE.md")
        val assets = names.mapIndexed { index, name -> bodyAsset(name, ByteArray(index + 1) { index.toByte() }) }
        val manifest = decodeBodyAssetManifest(bodyManifestBytes(assets))
        assertEquals(assets.associateBy { it.name }, manifest.files)
        assertEquals(assets.sumOf { it.bytes }, manifest.totalBytes)
        assertEquals(assets.map { it.sha256 }.toSet(), manifest.hashes)
    }

    @Test
    fun rejectsForeignOrUnhashedPathsMalformedHashesUnsafeNamesAndInvalidSizes() {
        val asset = bodyAsset("male.glb", byteArrayOf(1, 2, 3))
        val invalid = listOf(
            asset.copy(path = "https://other.invalid/male.glb"),
            asset.copy(path = "//other.invalid/male.glb"),
            asset.copy(path = "/assets/male.glb"),
            asset.copy(path = asset.path + "?token=secret"),
            asset.copy(path = asset.path + "#fragment"),
            asset.copy(path = "/assets/body-${asset.sha256}-../male.glb", name = "../male.glb"),
            asset.copy(name = ".male.glb"),
            asset.copy(name = "male/figure.glb"),
            asset.copy(sha256 = "A".repeat(64)),
            asset.copy(sha256 = "a".repeat(63)),
            asset.copy(bytes = 0),
            asset.copy(bytes = MAX_BODY_ASSET_BYTES + 1),
            asset.copy(contentType = "image/webp"),
        )
        for (value in invalid) {
            assertEquals(BodyAssetError.InvalidManifest, assertFailsWith<BodyAssetFailure>(value.toString()) {
                decodeBodyAssetManifest(bodyManifestBytes(listOf(value)))
            }.error)
        }
    }

    @Test
    fun actualDigestAndByteCountBothMustMatchBeforeAFileCanBePublished() {
        val bytes = "synthetic-body-asset".encodeToByteArray()
        val asset = bodyAsset("male.glb", bytes)
        verifyBodyAssetHash(asset, BodyAssetVerification(bytes.size.toLong(), bodyDigest(bytes)))
        for (verification in listOf(
            BodyAssetVerification(bytes.size.toLong() - 1, asset.sha256),
            BodyAssetVerification(bytes.size.toLong() + 1, asset.sha256),
            BodyAssetVerification(bytes.size.toLong(), bodyDigest(bytes + byteArrayOf(1))),
            BodyAssetVerification(bytes.size.toLong(), asset.sha256.uppercase()),
        )) assertEquals(BodyAssetError.Integrity, assertFailsWith<BodyAssetFailure> {
            verifyBodyAssetHash(asset, verification)
        }.error)
    }
}
