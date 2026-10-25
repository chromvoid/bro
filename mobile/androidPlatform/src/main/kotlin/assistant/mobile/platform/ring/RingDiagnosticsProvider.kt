package assistant.mobile.platform.ring

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

/** Owner-authorized ADB diagnostics for release builds; no WebView or third-party app access. */
class RingDiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true

    private fun requireCaller() {
        // ContentProvider.call does not enforce read/write permissions itself. Check every entry,
        // including call/getType, in addition to the manifest's platform DUMP permission.
        RingDiagnosticsAccess.requireCaller(Binder.getCallingUid(), Process.myUid())
    }

    private fun requirePath(uri: Uri, path: String) {
        require(uri.scheme == "content" && uri.authority == "${requireNotNull(context).packageName}.ringdiagnostics" &&
            uri.path == path && uri.query == null && uri.fragment == null) { "Unknown diagnostics URI" }
    }

    private fun <T> withRepository(block: (RingRepository) -> T): T {
        val app = requireNotNull(context)
        val origin = RingRepository.configuredOrigin(app)
            ?: throw IllegalStateException("Application origin is not configured")
        val identity = Binder.clearCallingIdentity()
        try {
            return block(RingRepository.get(requireNotNull(context), origin.trimEnd('/')))
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        requireCaller(); requirePath(uri, "/status")
        require(projection == null && selection == null && selectionArgs == null && sortOrder == null)
        val status = withRepository { it.diagnosticStatus().toString() }
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(status)) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        requireCaller(); requirePath(uri, "/journal")
        if (mode != "r") throw FileNotFoundException("Diagnostics are read-only")
        val snapshot = withRepository { it.diagnosticJournal() }
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "ring-diagnostics-export", isDaemon = true) {
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(snapshot) } }
            catch (_: IOException) { /* The authorized reader may close its pipe early. */ }
        }
        return pipe[0]
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireCaller()
        require(arg == null && (extras == null || extras.isEmpty)) { "Diagnostics take no external configuration" }
        val result = when (method) {
            "sync" -> withRepository { it.diagnosticSync(freshHistory = false) }
            "reread" -> withRepository { it.diagnosticSync(freshHistory = true) }
            else -> throw IllegalArgumentException("Unknown diagnostics method")
        }
        return Bundle().apply { putString("json", result.toString()) }
    }

    override fun getType(uri: Uri): String {
        requireCaller()
        return when (uri.path) {
            "/journal" -> { requirePath(uri, "/journal"); "application/x-ndjson" }
            "/status" -> { requirePath(uri, "/status"); "vnd.android.cursor.item/vnd.bro.ring-diagnostics" }
            else -> throw IllegalArgumentException("Unknown diagnostics URI")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? { requireCaller(); error("Read-only provider") }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        requireCaller(); error("Read-only provider")
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        requireCaller(); error("Read-only provider")
    }
}
