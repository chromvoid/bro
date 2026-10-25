package assistant.mobile.android.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import assistant.mobile.android.BuildConfig
import assistant.mobile.core.NativeApkIdentity
import assistant.mobile.core.NativeInstallPhase
import assistant.mobile.core.NativeInstallState
import assistant.mobile.core.NativeUpdateDownload
import assistant.mobile.core.NativeUpdateError
import assistant.mobile.core.NativeUpdateFailure
import assistant.mobile.core.NativeUpdatePlatform
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** APK delivery belongs to the build, independently of the selected application server. */
internal fun androidUpdateOrigin(context: Context): String {
    val application = context.applicationContext
    val info = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
    return requireNotNull(info.metaData?.getString("assistant.origin")) {
        "The application must declare assistant.origin"
    }
}

/** Application-owned PackageInstaller callbacks survive rotation and retain no person credential. */
class AndroidUpdatePlatform private constructor(private val context: Context) : NativeUpdatePlatform {
    override val releaseCertificateSha256: String = BuildConfig.RELEASE_CERTIFICATE_SHA256
    override val installedVersionCode: Long
        get() = versionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    private val mutableInstalls = MutableStateFlow(NativeInstallState())
    override val installs: StateFlow<NativeInstallState> = mutableInstalls.asStateFlow()
    private var visible = WeakReference<Activity>(null)
    private var confirmation: Intent? = null

    override suspend fun createDownload(): NativeUpdateDownload = withContext(Dispatchers.IO) {
        // Each attempt needs its own terminal event, even when Android refuses it identically.
        mutableInstalls.value = NativeInstallState()
        try {
            val directory = File(context.cacheDir, "native-updates")
            check(directory.isDirectory || directory.mkdirs())
            directory.listFiles()?.forEach { check(it.delete()) }
            AndroidDownload(File.createTempFile("update-", ".apk", directory))
        } catch (_: Exception) { throw NativeUpdateFailure(NativeUpdateError.Storage) }
    }

    override suspend fun inspect(download: NativeUpdateDownload): NativeApkIdentity = withContext(Dispatchers.IO) {
        val file = own(download).file
        try {
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: throw NativeUpdateFailure(NativeUpdateError.Incompatible)
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.toList().orEmpty()
                else @Suppress("DEPRECATION") info.signatures?.toList().orEmpty()
            NativeApkIdentity(info.packageName, versionCode(info), signatures.map { sha256(it.toByteArray()) })
        } catch (error: NativeUpdateFailure) { throw error }
        catch (_: Exception) { throw NativeUpdateFailure(NativeUpdateError.Incompatible) }
    }

    override suspend fun install(download: NativeUpdateDownload): Unit = withContext(Dispatchers.IO) {
        if (!context.packageManager.canRequestPackageInstalls()) throw NativeUpdateFailure(NativeUpdateError.PermissionRequired)
        val file = own(download).file
        val installer = context.packageManager.packageInstaller
        val coroutine = currentCoroutineContext()
        var sessionId: Int? = null
        try {
            coroutine.ensureActive()
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("native.apk", 0, file.length()).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        session.fsync(output)
                    }
                }
                coroutine.ensureActive()
                // The system fills in status and confirmation. An explicit, non-exported receiver owns it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = Intent(context, NativeUpdateReceiver::class.java)
                mutableInstalls.value = NativeInstallState(NativeInstallPhase.Installing)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (error: Exception) {
            sessionId?.let { runCatching { installer.abandonSession(it) } }
            if (error is CancellationException) throw error
            if (error is SecurityException && !context.packageManager.canRequestPackageInstalls())
                throw NativeUpdateFailure(NativeUpdateError.PermissionRequired)
            throw NativeUpdateFailure(NativeUpdateError.InstallFailed)
        }
    }

    /** User initiated settings, scoped to this package. No update can bypass Android's consent. */
    fun requestInstallPermission(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } catch (_: Exception) {
            mutableInstalls.value = NativeInstallState(NativeInstallPhase.Failed, NativeUpdateError.Blocked)
        }
    }

    fun visible(activity: Activity?) {
        visible = WeakReference(activity)
        activity?.let(::showConfirmation)
    }

    internal fun status(status: Int, intent: Intent?) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && intent != null) {
            synchronized(this) { confirmation = intent }
            mutableInstalls.value = NativeInstallState(NativeInstallPhase.Confirmation)
            visible.get()?.let(::showConfirmation)
            return
        }
        mutableInstalls.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> NativeInstallState(NativeInstallPhase.Failed, NativeUpdateError.InstallFailed)
            PackageInstaller.STATUS_SUCCESS -> NativeInstallState(NativeInstallPhase.Installed)
            else -> NativeInstallState(NativeInstallPhase.Failed, installError(status))
        }
    }

    private fun showConfirmation(activity: Activity) {
        val intent = synchronized(this) { confirmation.also { confirmation = null } } ?: return
        try { activity.startActivity(intent) }
        catch (_: Exception) { mutableInstalls.value = NativeInstallState(NativeInstallPhase.Failed, NativeUpdateError.InstallFailed) }
    }

    private fun own(download: NativeUpdateDownload): AndroidDownload = download as? AndroidDownload
        ?: throw NativeUpdateFailure(NativeUpdateError.Integrity)

    companion object {
        @Volatile private var instance: AndroidUpdatePlatform? = null
        fun get(context: Context): AndroidUpdatePlatform = instance ?: synchronized(this) {
            instance ?: AndroidUpdatePlatform(context.applicationContext).also { instance = it }
        }

        fun installError(status: Int): NativeUpdateError = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> NativeUpdateError.Cancelled
            PackageInstaller.STATUS_FAILURE_BLOCKED -> NativeUpdateError.Blocked
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> NativeUpdateError.Incompatible
            PackageInstaller.STATUS_FAILURE_STORAGE -> NativeUpdateError.Storage
            else -> NativeUpdateError.InstallFailed
        }
    }
}

private class AndroidDownload(val file: File) : NativeUpdateDownload {
    private var output: FileOutputStream? = FileOutputStream(file)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished: String? = null

    override suspend fun write(bytes: ByteArray, count: Int): Unit = withContext(Dispatchers.IO) {
        try {
            val stream = output ?: throw NativeUpdateFailure(NativeUpdateError.Integrity)
            stream.write(bytes, 0, count)
            digest.update(bytes, 0, count)
        } catch (error: NativeUpdateFailure) { throw error }
        catch (_: Exception) { throw NativeUpdateFailure(NativeUpdateError.Storage) }
    }

    override suspend fun finish(): String = withContext(Dispatchers.IO) {
        finished ?: try {
            output?.fd?.sync()
            output?.close()
            output = null
            digest.digest().hex().also { finished = it }
        } catch (_: Exception) { throw NativeUpdateFailure(NativeUpdateError.Storage) }
    }

    override suspend fun discard(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { output?.close() }
        output = null
        file.delete()
        Unit
    }
}

class NativeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        AndroidUpdatePlatform.get(context).status(
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE), confirmation,
        )
    }
}

private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
    else @Suppress("DEPRECATION") info.versionCode.toLong()
private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
