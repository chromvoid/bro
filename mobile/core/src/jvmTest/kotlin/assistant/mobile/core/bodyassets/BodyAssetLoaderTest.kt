package assistant.mobile.core.bodyassets

import assistant.mobile.core.FEATURE_NOW
import assistant.mobile.core.FEATURE_ORIGIN
import assistant.mobile.core.FeatureSessionSecrets
import assistant.mobile.core.NativeHttpClient
import assistant.mobile.core.SessionManager
import assistant.mobile.core.featureRecord
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryBodyAssets : BodyAssetStore {
    var manifestBytes: ByteArray? = null
    val files = mutableMapOf<String, ByteArray>()
    var commits = 0
    var discarded = 0
    override suspend fun readManifest(): ByteArray? = manifestBytes
    override suspend fun writeManifest(bytes: ByteArray) { manifestBytes = bytes.copyOf() }
    override suspend fun contains(asset: BodyAsset): Boolean {
        val bytes = files[asset.sha256] ?: return false
        val valid = bytes.size.toLong() == asset.bytes && bodyDigest(bytes) == asset.sha256
        if (!valid) files.remove(asset.sha256)
        return valid
    }
    override suspend fun retain(hashes: Set<String>) { files.keys.retainAll(hashes) }
    override suspend fun begin(asset: BodyAsset): BodyAssetSink = object : BodyAssetSink {
        val output = ByteArrayOutputStream()
        override suspend fun write(bytes: ByteArray, count: Int) { output.write(bytes, 0, count) }
        override suspend fun finish(): BodyAssetVerification = BodyAssetVerification(output.size().toLong(), bodyDigest(output.toByteArray()))
        override suspend fun commit() { files[asset.sha256] = output.toByteArray(); commits++ }
        override suspend fun discard() { discarded++; output.close() }
    }
}

private class AssetFixture(val sessions: SessionManager, val http: NativeHttpClient, val loader: BodyAssetLoader)

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.assetFixture(
    store: MemoryBodyAssets,
    signedIn: Boolean = true,
    handler: MockRequestHandler,
): AssetFixture {
    val secrets = FeatureSessionSecrets(if (signedIn) featureRecord() else null)
    val sessions = SessionManager("synthetic", FEATURE_ORIGIN, secrets, {}, { FEATURE_NOW })
    val engine = MockEngine(MockEngineConfig().apply {
        dispatcher = UnconfinedTestDispatcher(testScheduler)
        addHandler(handler)
    })
    val http = NativeHttpClient(engine, FEATURE_ORIGIN, sessions, { FEATURE_NOW })
    sessions.restore()
    return AssetFixture(sessions, http, BodyAssetLoader(http, sessions, store))
}

@OptIn(ExperimentalCoroutinesApi::class)
class BodyAssetLoaderTest {
    @Test
    fun authenticatesEveryDownloadReportsProgressAndReopensWithoutAnyNetwork() = runTest {
        val payloads = listOf("male", "female", "male_spartan").associate { name ->
            "$name.glb" to ByteArray(131_073) { (it % (name.length + 123)).toByte() }
        }
        val assets = payloads.map { (name, bytes) -> bodyAsset(name, bytes) }
        val requests = mutableListOf<String>()
        val store = MemoryBodyAssets()
        val fixture = assetFixture(store) { request ->
            requests += request.url.encodedPath
            assertEquals("Bearer ${featureRecord().credential}", request.headers["Authorization"])
            assertEquals(featureRecord().namespace.userId, request.headers["X-Expected-User"])
            assertNull(request.headers["Cookie"])
            if (request.url.encodedPath == BODY_ASSET_MANIFEST_PATH) respond(bodyManifestBytes(assets))
            else {
                assertEquals("identity", request.headers["Accept-Encoding"])
                val asset = assets.single { it.path == request.url.encodedPath }
                respond(payloads.getValue(asset.name), headers = headersOf("Content-Length", asset.bytes.toString()))
            }
        }
        try {
            val progress = mutableListOf<BodyAssetProgress>()
            assertEquals(assets.associateBy { it.name }, fixture.loader.load(onProgress = progress::add).files)
            assertEquals(4, requests.size)
            assertEquals(3, store.commits)
            assertTrue(progress.any { it.phase == BodyAssetPhase.Downloading && it.fraction > 0f && it.fraction < 1f })
            assertEquals(1f, progress.last().fraction)
            assertFalse(progress.last().cached)
            assets.forEach { assertContentEquals(payloads.getValue(it.name), store.files[it.sha256]) }
            progress.clear()
            fixture.loader.load(revalidateManifest = false, onProgress = progress::add)
            assertEquals(4, requests.size)
            assertEquals(3, store.commits)
            assertTrue(progress.last().cached)
        } finally { fixture.http.close() }
    }

    @Test
    fun hashShortLongAndDeclaredLengthFailuresNeverExposePartialFiles() = runTest {
        val expected = ByteArray(100_001) { (it % 251).toByte() }
        val asset = bodyAsset("male.glb", expected)
        for (kind in listOf("hash", "short", "long", "content-length")) {
            val store = MemoryBodyAssets()
            val fixture = assetFixture(store) { request ->
                if (request.url.encodedPath == BODY_ASSET_MANIFEST_PATH) respond(bodyManifestBytes(listOf(asset)))
                else {
                    val bytes = when (kind) {
                        "hash" -> expected.copyOf().also { it[0] = 99 }
                        "short" -> expected.copyOf(expected.size - 1)
                        "long" -> expected + byteArrayOf(0)
                        else -> expected
                    }
                    respond(bytes, headers = if (kind == "content-length") headersOf("Content-Length", "1") else headersOf())
                }
            }
            try {
                assertEquals(BodyAssetError.Integrity, assertFailsWith<BodyAssetFailure>(kind) { fixture.loader.load() }.error)
                assertEquals(0, store.commits, kind)
                assertEquals(1, store.discarded, kind)
                assertTrue(store.files.isEmpty(), kind)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun completeVerifiedCacheWorksOfflineButIncompleteOrCorruptCacheReportsNetworkFailure() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val asset = bodyAsset("male.glb", bytes)
        for (cached in listOf("complete", "missing", "corrupt")) {
            val store = MemoryBodyAssets().apply {
                manifestBytes = bodyManifestBytes(listOf(asset))
                if (cached != "missing") files[asset.sha256] = if (cached == "complete") bytes else byteArrayOf(4, 5, 6)
            }
            val fixture = assetFixture(store) { throw IOException("Synthetic offline network") }
            try {
                if (cached == "complete") assertEquals(asset, fixture.loader.load().files[asset.name])
                else assertEquals(BodyAssetError.Network, assertFailsWith<BodyAssetFailure> { fixture.loader.load() }.error)
                assertEquals(0, store.commits)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun redirectsAndMalformedManifestAreRejectedAndNoSessionNeverTouchesNetwork() = runTest {
        for (kind in listOf("redirect", "invalid", "signed-out")) {
            var requests = 0
            val fixture = assetFixture(MemoryBodyAssets(), signedIn = kind != "signed-out") {
                requests++
                if (kind == "redirect") respond("", HttpStatusCode.Found, headersOf("Location", "https://other.invalid/body-assets.json"))
                else respond("{}")
            }
            try {
                val expected = when (kind) { "signed-out" -> BodyAssetError.Unauthorized; "redirect" -> BodyAssetError.Network; else -> BodyAssetError.InvalidManifest }
                assertEquals(expected, assertFailsWith<BodyAssetFailure> { fixture.loader.load() }.error)
                assertEquals(if (kind == "signed-out") 0 else 1, requests)
            } finally { fixture.http.close() }
        }
    }
}
