package assistant.mobile.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

const val NATIVE_UPDATE_PACKAGE = "com.chromvoid.bro"
const val MAX_UPDATE_BYTES = 512L shl 20

/** The manifest supplies no download authority: the path is constructed from the pinned channel. */
data class NativeUpdateOffer(val packageName: String, val versionCode: Long, val versionName: String, val sha256: String, val size: Long)

enum class NativeUpdatePhase {
    Idle, Checking, UpToDate, Available, Downloading, Verifying, PermissionRequired, Installing, Confirmation, Installed, Failed,
}
enum class NativeUpdateError {
    Network, Unauthorized, Forbidden, RateLimited, InvalidManifest, WrongPackage, Certificate, Integrity, Downgrade,
    PermissionRequired, Cancelled, Blocked, Incompatible, Storage, InstallFailed,
}
data class NativeUpdateState(
    val phase: NativeUpdatePhase = NativeUpdatePhase.Idle,
    val offer: NativeUpdateOffer? = null,
    val progress: Float = 0f,
    val error: NativeUpdateError? = null,
)
class NativeUpdateFailure(val error: NativeUpdateError) : Exception(error.name)

/** Files and Android archive parsing stay behind this port; credentials never enter it. */
interface NativeUpdateDownload {
    suspend fun write(bytes: ByteArray, count: Int)
    suspend fun finish(): String
    suspend fun discard()
}
data class NativeApkIdentity(val packageName: String, val versionCode: Long, val signerSha256: List<String>)
enum class NativeInstallPhase { Idle, Installing, Confirmation, Installed, Failed }
data class NativeInstallState(val phase: NativeInstallPhase = NativeInstallPhase.Idle, val error: NativeUpdateError? = null)
interface NativeUpdatePlatform {
    val installedVersionCode: Long
    val releaseCertificateSha256: String
    val installs: StateFlow<NativeInstallState>
    suspend fun createDownload(): NativeUpdateDownload
    suspend fun inspect(download: NativeUpdateDownload): NativeApkIdentity
    suspend fun install(download: NativeUpdateDownload)
}

fun decodeNativeUpdateManifest(bytes: ByteArray): NativeUpdateOffer? {
    fun invalid(): Nothing = throw NativeUpdateFailure(NativeUpdateError.InvalidManifest)
    val envelope = runCatching { NativeJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: invalid()
    val release = envelope["release"] ?: invalid()
    if (release == JsonNull) return null
    val fields = release as? JsonObject ?: invalid()
    fun string(name: String): String = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    fun number(name: String): Long = (fields[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid()
    val packageName = string("package")
    if (packageName != NATIVE_UPDATE_PACKAGE) throw NativeUpdateFailure(NativeUpdateError.WrongPackage)
    val code = number("version_code")
    val name = string("version_name")
    val sha = string("sha256")
    val size = number("size")
    if (code !in 2..2_100_000_000L || name.isEmpty() || name.length > 64 || name.any { it.isISOControl() } ||
        !updateSha256(sha) || size !in 1..MAX_UPDATE_BYTES) invalid()
    return NativeUpdateOffer(packageName, code, name, sha, size)
}

/** Repeated immediately before PackageInstaller, independently of the public manifest. */
fun validateNativeUpdateApk(offer: NativeUpdateOffer, apk: NativeApkIdentity, installedVersionCode: Long, certificatePin: String) {
    if (offer.packageName != NATIVE_UPDATE_PACKAGE || apk.packageName != NATIVE_UPDATE_PACKAGE)
        throw NativeUpdateFailure(NativeUpdateError.WrongPackage)
    if (!updateSha256(certificatePin) || apk.signerSha256 != listOf(certificatePin))
        throw NativeUpdateFailure(NativeUpdateError.Certificate)
    if (offer.versionCode <= installedVersionCode || apk.versionCode <= installedVersionCode)
        throw NativeUpdateFailure(NativeUpdateError.Downgrade)
    if (apk.versionCode != offer.versionCode) throw NativeUpdateFailure(NativeUpdateError.Integrity)
}
private fun updateSha256(value: String): Boolean = value.length == 64 && value.all { it in "0123456789abcdef" }

/** Public APK delivery has its own immutable origin and no auth, cookies, redirects or retries. */
class NativeUpdateHttpClient(
    private val engine: HttpClientEngine,
    origin: String,
    allowInsecureLoopback: Boolean = false,
) {
    private val origin = canonicalOrigin(origin, allowInsecureLoopback)
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 45_000
        }
    }

    internal suspend fun manifest(): ByteArray {
        val response = client.get(origin + NativeUpdateController.MANIFEST_PATH)
        val status = response.status.value
        if (status != 200) throw NativeUpdateFailure(if (status in 200..299) NativeUpdateError.InvalidManifest else responseError(status))
        return response.bodyAsBytes()
    }

    internal suspend fun streamApk(
        offer: NativeUpdateOffer,
        onResponse: suspend (headers: Map<String, String>, channel: ByteReadChannel) -> Unit,
    ) {
        client.prepareGet(origin + NativeUpdateController.apkPath(offer)) {
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }.execute { response ->
            if (response.status.value != 200) throw NativeUpdateFailure(responseError(response.status.value))
            val headers = response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
            onResponse(headers, response.bodyAsChannel())
        }
    }

    fun close() { client.close(); engine.close() }

    private fun responseError(status: Int): NativeUpdateError = when (status) {
        401 -> NativeUpdateError.Unauthorized
        403 -> NativeUpdateError.Forbidden
        429 -> NativeUpdateError.RateLimited
        else -> NativeUpdateError.Network
    }
}

/** One explicit public check/download at a time, independent of account or selected server. */
class NativeUpdateController(
    private val scope: CoroutineScope,
    private val http: NativeUpdateHttpClient,
    private val platform: NativeUpdatePlatform,
) {
    private val mutableState = MutableStateFlow(NativeUpdateState())
    val state: StateFlow<NativeUpdateState> = mutableState.asStateFlow()
    private var work: Job? = null
    private var pending: NativeUpdateDownload? = null
    private var installStarted = false

    init {
        scope.launch {
            platform.installs.collect { install ->
                if (!installStarted) return@collect
                if (install.phase == NativeInstallPhase.Failed) {
                    pending?.discard()
                    pending = null
                }
                val phase = when (install.phase) {
                    NativeInstallPhase.Idle -> return@collect
                    NativeInstallPhase.Installing -> NativeUpdatePhase.Installing
                    NativeInstallPhase.Confirmation -> NativeUpdatePhase.Confirmation
                    NativeInstallPhase.Installed -> NativeUpdatePhase.Installed
                    NativeInstallPhase.Failed -> NativeUpdatePhase.Failed
                }
                mutableState.value = mutableState.value.copy(phase = phase, error = install.error)
            }
        }
    }

    fun check() {
        if (busy() || pending != null) return
        work = scope.launch {
            mutableState.value = NativeUpdateState(NativeUpdatePhase.Checking)
            try {
                val offer = decodeNativeUpdateManifest(http.manifest())
                mutableState.value = NativeUpdateState(
                    if (offer == null || offer.versionCode <= platform.installedVersionCode) NativeUpdatePhase.UpToDate else NativeUpdatePhase.Available,
                    offer,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error) }
        }
    }

    fun downloadAndInstall() {
        if (busy()) return
        val offer = state.value.offer ?: return
        if (state.value.phase !in setOf(NativeUpdatePhase.Available, NativeUpdatePhase.Failed)) return
        work = scope.launch {
            var download: NativeUpdateDownload? = null
            try {
                if (offer.versionCode <= platform.installedVersionCode) throw NativeUpdateFailure(NativeUpdateError.Downgrade)
                mutableState.value = NativeUpdateState(NativeUpdatePhase.Downloading, offer)
                val target = platform.createDownload()
                download = target
                var total = 0L
                http.streamApk(offer) { headers, channel ->
                    headers["content-length"]?.let {
                        if (it.toLongOrNull() != offer.size) throw NativeUpdateFailure(NativeUpdateError.Integrity)
                    }
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > offer.size) throw NativeUpdateFailure(NativeUpdateError.Integrity)
                        target.write(buffer, count)
                        mutableState.value = NativeUpdateState(NativeUpdatePhase.Downloading, offer, total.toFloat() / offer.size)
                    }
                }
                if (total != offer.size || target.finish() != offer.sha256) throw NativeUpdateFailure(NativeUpdateError.Integrity)
                mutableState.value = NativeUpdateState(NativeUpdatePhase.Verifying, offer, 1f)
                validateNativeUpdateApk(offer, platform.inspect(target), platform.installedVersionCode, platform.releaseCertificateSha256)
                pending = target
                download = null
                installPending()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error) }
            finally { download?.discard() }
        }
    }

    /** Resumes only the already verified APK after the system's unknown-source settings return. */
    fun resumeInstall() {
        if (busy() || pending == null || state.value.phase != NativeUpdatePhase.PermissionRequired) return
        work = scope.launch {
            try { installPending() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error) }
        }
    }

    private suspend fun installPending() {
        val download = pending ?: return
        val offer = state.value.offer ?: return
        var preservePending = false
        try {
            // Never trust a stale successful check when coming back from system settings.
            validateNativeUpdateApk(offer, platform.inspect(download), platform.installedVersionCode, platform.releaseCertificateSha256)
            installStarted = true
            mutableState.value = NativeUpdateState(NativeUpdatePhase.Installing, offer, 1f)
            platform.install(download)
        } catch (error: NativeUpdateFailure) {
            if (error.error == NativeUpdateError.PermissionRequired) {
                mutableState.value = NativeUpdateState(NativeUpdatePhase.PermissionRequired, offer, 1f)
                preservePending = true
                return
            }
            throw error
        } finally {
            if (!preservePending) {
                download.discard()
                pending = null
            }
        }
    }

    private fun busy(): Boolean = work?.isActive == true || state.value.phase in setOf(NativeUpdatePhase.Installing, NativeUpdatePhase.Confirmation)
    private fun failed(error: Exception) {
        mutableState.value = state.value.copy(phase = NativeUpdatePhase.Failed,
            error = (error as? NativeUpdateFailure)?.error ?: NativeUpdateError.Network)
    }

    companion object {
        const val MANIFEST_PATH = "/v1/shell/android"
        fun apkPath(offer: NativeUpdateOffer): String = "/v1/shell/android/${offer.versionCode}.apk"
    }
}
