package assistant.mobile.platform

import android.content.Context
import android.util.AtomicFile
import assistant.mobile.core.NativeJson
import assistant.mobile.core.NativeServerSelection
import assistant.mobile.core.NativeServerSelectionStore
import assistant.mobile.core.decodeNativeServerSelection
import assistant.mobile.core.validateNativeServerSelection
import java.io.File

/** One non-secret server choice, committed as a complete record. */
class AndroidNativeServerSelectionStore(
    context: Context,
    private val allowInsecureLoopback: Boolean = false,
) : NativeServerSelectionStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "native-server.json"))

    @Synchronized
    override fun read(): NativeServerSelection? = runCatching {
        decodeNativeServerSelection(file.readFully(), allowInsecureLoopback)
    }.getOrNull()

    @Synchronized
    override fun write(selection: NativeServerSelection) {
        validateNativeServerSelection(selection, allowInsecureLoopback)
        val bytes = NativeJson.encodeToString(NativeServerSelection.serializer(), selection).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }
}
