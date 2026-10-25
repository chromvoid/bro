package assistant.mobile.android

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import assistant.mobile.core.files.ExportFile
import assistant.mobile.core.files.FileExportDestination
import assistant.mobile.core.files.FileExportPort
import assistant.mobile.platform.AndroidFileExportStorage
import assistant.mobile.platform.AndroidExportStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One system export at a time; no Activity, shared external directory or persistent URI grant. */
class FileExportBridge(
    context: Context,
    private val storage: AndroidExportStorage = AndroidFileExportStorage(context),
) : FileExportPort, AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val changed = MutableStateFlow(0L)
    private val transactions = mutableMapOf<Deferred<Boolean>, Long>()
    private var launch: ((Intent) -> Unit)? = null
    private var owner: Any? = null
    private var inFlight: SystemRequest? = null

    private class SystemRequest(val answer: CompletableDeferred<ActivityResult>)

    internal fun attach(owner: Any, launch: (Intent) -> Unit) {
        this.owner = owner
        this.launch = launch
        changed.value++
    }
    internal fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        launch = null
        // Configuration detachment is not a user cancellation. The new scene registers the
        // same ActivityResult launcher and reconnects to this runtime-owned transaction.
        changed.value++
    }
    internal fun result(result: ActivityResult) {
        val request = inFlight ?: return
        inFlight = null
        request.answer.complete(result)
        changed.value++
    }

    override suspend fun export(file: ExportFile, destination: FileExportDestination): Boolean {
        val operation = withContext(Dispatchers.Main.immediate) {
            if (launch == null) return@withContext null
            val generation = storage.generation
            // The awaiting Compose caller may disappear on rotation; it does not own export.
            val task = scope.async(start = CoroutineStart.LAZY) {
                gate.withLock { performExport(file, destination, generation) }
            }
            transactions[task] = generation
            task.invokeOnCompletion { scope.launch { transactions.remove(task) } }
            task.start()
            task
        } ?: return false
        return operation.await()
    }

    private suspend fun performExport(file: ExportFile, destination: FileExportDestination, generation: Long): Boolean {
        if (storage.generation != generation) return false
        val source = withContext(Dispatchers.IO) { storage.stage(file, generation) } ?: return false
        var shared = false
        return try {
            // A canceled old account's system result must drain before the next export starts.
            changed.first { storage.generation != generation || (launch != null && inFlight == null) }
            if (storage.generation != generation) return false
            val launcher = launch ?: return false
            val answer = CompletableDeferred<ActivityResult>()
            val request = SystemRequest(answer)
            try {
                val intent = when (destination) {
                    FileExportDestination.SAVE -> Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType(file.mimeType).putExtra(Intent.EXTRA_TITLE, file.name)
                    FileExportDestination.SHARE -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType(file.mimeType)
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = ClipData.newRawUri(file.name, uri) }, null)
                    }
                }
                inFlight = request
                try { launcher(intent) }
                catch (error: Throwable) {
                    if (inFlight === request) { inFlight = null; changed.value++ }
                    throw error
                }
                shared = destination == FileExportDestination.SHARE
                val result = answer.await()
                when (destination) {
                    FileExportDestination.SAVE -> {
                        val uri = result.data?.data
                        if (result.resultCode != Activity.RESULT_OK || uri == null) false
                        else withContext(Dispatchers.IO) { storage.save(source, uri, generation) }
                    }
                    FileExportDestination.SHARE -> true
                }
            } finally { answer.cancel() }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                if (shared) storage.retainForShare(source, generation) else storage.release(source)
            }
        }
    }

    override fun clear() {
        storage.clear()
        val currentGeneration = storage.generation
        scope.launch {
            transactions.filterValues { it < currentGeneration }.keys.toList().forEach { it.cancel() }
            changed.value++
        }
    }

    override fun close() {
        clear()
        scope.cancel()
    }
}

@Composable
fun FileExportHost(bridge: FileExportBridge) {
    val owner = remember { Any() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), bridge::result)
    DisposableEffect(bridge, owner, launcher) {
        bridge.attach(owner) { launcher.launch(it) }
        onDispose { bridge.detach(owner) }
    }
}
