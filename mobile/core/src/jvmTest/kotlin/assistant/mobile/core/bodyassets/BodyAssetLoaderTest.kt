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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    val partials = mutableMapOf<String, ByteArray>()
    var onWrite: () -> Unit = {}
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
    override suspend fun retain(hashes: Set<String>) { files.keys.retainAll(hashes); partials.keys.retainAll(hashes) }
    override suspend fun begin(asset: BodyAsset): BodyAssetSink = object : BodyAssetSink {
        val output = ByteArrayOutputStream().apply { partials[asset.sha256]?.let { write(it) } }
        override val bytes get() = output.size().toLong()
        override suspend fun write(bytes: ByteArray, count: Int) {
            output.write(bytes, 0, count)
            partials[asset.sha256] = output.toByteArray()
            onWrite()
        }
        override suspend fun reset() { output.reset(); partials.remove(asset.sha256) }
        override suspend fun finish(): BodyAssetVerification = BodyAssetVerification(output.size().toLong(), bodyDigest(output.toByteArray()))
        override suspend fun commit() { files[asset.sha256] = output.toByteArray(); partials.remove(asset.sha256); commits++ }
        override suspend fun close() { output.close() }
        override suspend fun discard() { discarded++; partials.remove(asset.sha256); close() }
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
    fun downloadsOnlyRequestedFigureKeepsOtherHashesAndReopensTheSubsetOffline() = runTest {
        val names = setOf("male.glb", "male-lookaround.glb", "male.webp")
        val payloads = (listOf("male", "female", "male_spartan").flatMap {
            listOf("$it.glb", "$it-lookaround.glb", "$it.webp")
        } + "README.md").associateWith { name -> ByteArray(600_001) { (it % (name.length + 123)).toByte() } }
        val assets = payloads.map { (name, bytes) -> bodyAsset(name, bytes) }
        val requests = mutableListOf<String>()
        val female = assets.single { it.name == "female.glb" }
        val store = MemoryBodyAssets().apply { files[female.sha256] = payloads.getValue(female.name) }
        var online = true
        val fixture = assetFixture(store) { request ->
            requests += request.url.encodedPath
            if (!online) throw IOException("Synthetic offline network")
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
            assertEquals(assets.associateBy { it.name }, fixture.loader.load(names, onProgress = progress::add).files)
            assertEquals(4, requests.size)
            assertEquals(names, requests.drop(1).map { path -> assets.single { it.path == path }.name }.toSet())
            assertEquals(3, store.commits)
            assertTrue(progress.any { it.phase == BodyAssetPhase.Downloading && it.fraction > 0f && it.fraction < 1f })
            assertEquals(1f, progress.last().fraction)
            assertFalse(progress.last().cached)
            assertEquals(assets.filter { it.name in names }.sumOf { it.bytes }, progress.last().totalBytes)
            for (name in names) {
                val updates = progress.filter { it.phase == BodyAssetPhase.Downloading && it.filename == name }
                assertTrue(updates.zipWithNext().all { (a, b) -> b.completedBytes - a.completedBytes >= 256 * 1024 })
            }
            assets.filter { it.name in names || it == female }.forEach {
                assertContentEquals(payloads.getValue(it.name), store.files[it.sha256])
            }
            progress.clear()
            fixture.loader.load(names, revalidateManifest = false, onProgress = progress::add)
            assertEquals(4, requests.size)
            assertEquals(3, store.commits)
            assertTrue(progress.last().cached)
            fixture.loader.load(setOf("female.glb", "female-lookaround.glb", "female.webp"), revalidateManifest = false)
            assertEquals(6, requests.size)
            assertEquals(5, store.commits)
            online = false
            fixture.loader.load(names, onProgress = progress::add)
            assertEquals(7, requests.size)
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
                assertEquals(BodyAssetError.Integrity, assertFailsWith<BodyAssetFailure>(kind) { fixture.loader.load(setOf(asset.name)) }.error)
                assertEquals(0, store.commits, kind)
                assertEquals(1, store.discarded, kind)
                assertTrue(store.files.isEmpty(), kind)
                assertTrue(store.partials.isEmpty(), kind)
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
                if (cached == "complete") assertEquals(asset, fixture.loader.load(setOf(asset.name)).files[asset.name])
                else assertEquals(BodyAssetError.Network, assertFailsWith<BodyAssetFailure> { fixture.loader.load(setOf(asset.name)) }.error)
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
                assertEquals(expected, assertFailsWith<BodyAssetFailure> { fixture.loader.load(setOf("male.glb")) }.error)
                assertEquals(if (kind == "signed-out") 0 else 1, requests)
            } finally { fixture.http.close() }
        }
    }

    @Test
    fun interruptedStreamResumesWithRangeAndHashesTheWholeFile() = runTest {
        val bytes = ByteArray(100_001) { (it % 251).toByte() }
        val asset = bodyAsset("male.glb", bytes)
        val offset = 50_000
        val store = MemoryBodyAssets()
        var downloads = 0
        val fixture = assetFixture(store) { request ->
            if (request.url.encodedPath == BODY_ASSET_MANIFEST_PATH) respond(bodyManifestBytes(listOf(asset)))
            else when (++downloads) {
                1 -> {
                    assertNull(request.headers["Range"])
                    val channel = ByteChannel(autoFlush = true)
                    store.onWrite = {
                        if (store.partials[asset.sha256]?.size == offset) {
                            channel.cancel(IOException("Synthetic interrupted stream"))
                            store.onWrite = {}
                        }
                    }
                    backgroundScope.launch { channel.writeFully(bytes, 0, offset) }
                    respond(channel, headers = headersOf("Content-Length", bytes.size.toString()))
                }
                2, 3 -> {
                    assertEquals(offset, store.partials[asset.sha256]?.size, "Saved prefix before reconnect $downloads")
                    assertEquals("bytes=$offset-", request.headers["Range"])
                    throw IOException("Synthetic reconnect failure")
                }
                else -> {
                    assertEquals("bytes=$offset-", request.headers["Range"])
                    respond(bytes.copyOfRange(offset, bytes.size), HttpStatusCode.PartialContent, headersOf(
                        "Content-Range" to listOf("bytes $offset-${bytes.lastIndex}/${bytes.size}"),
                        "Content-Length" to listOf((bytes.size - offset).toString()),
                    ))
                }
            }
        }
        try {
            try { fixture.loader.load(setOf(asset.name)) }
            catch (failure: BodyAssetFailure) {
                throw AssertionError("BodyAssetError=${failure.error}, downloads=$downloads, " +
                    "partialBytes=${store.partials[asset.sha256]?.size ?: 0}", failure)
            }
            assertEquals(4, downloads)
            assertEquals(7_000L, testScheduler.currentTime)
            assertEquals(1, store.commits)
            assertTrue(store.partials.isEmpty())
            assertContentEquals(bytes, store.files[asset.sha256])
        } finally { fixture.http.close() }
    }

    @Test
    fun rangeIgnoredWith200RestartsThePartialFile() = runTest {
        val bytes = ByteArray(100_001) { (it % 251).toByte() }
        val asset = bodyAsset("male.glb", bytes)
        val offset = 50_000
        val store = MemoryBodyAssets().apply { partials[asset.sha256] = bytes.copyOf(offset) }
        var downloads = 0
        val fixture = assetFixture(store) { request ->
            if (request.url.encodedPath == BODY_ASSET_MANIFEST_PATH) respond(bodyManifestBytes(listOf(asset)))
            else {
                downloads++
                assertEquals("bytes=$offset-", request.headers["Range"])
                respond(bytes, headers = headersOf("Content-Length", bytes.size.toString()))
            }
        }
        try {
            fixture.loader.load(setOf(asset.name))
            assertEquals(1, downloads)
            assertEquals(1, store.commits)
            assertTrue(store.partials.isEmpty())
            assertContentEquals(bytes, store.files[asset.sha256])
        } finally { fixture.http.close() }
    }

    @Test
    fun unauthorizedDownloadDoesNotRetry() = runTest {
        val asset = bodyAsset("male.glb", byteArrayOf(1, 2, 3))
        var downloads = 0
        val fixture = assetFixture(MemoryBodyAssets()) { request ->
            if (request.url.encodedPath == BODY_ASSET_MANIFEST_PATH) respond(bodyManifestBytes(listOf(asset)))
            else { downloads++; respond("", HttpStatusCode.Unauthorized) }
        }
        try {
            assertEquals(BodyAssetError.Unauthorized, assertFailsWith<BodyAssetFailure> {
                fixture.loader.load(setOf(asset.name))
            }.error)
            assertEquals(1, downloads)
            assertEquals(0L, testScheduler.currentTime)
        } finally { fixture.http.close() }
    }
}
