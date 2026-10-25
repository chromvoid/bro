package assistant.mobile.core.files

data class ExportFile(val name: String, val mimeType: String, val bytes: ByteArray) {
    init {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..")
        require(mimeType.isNotBlank())
    }
}

enum class FileExportDestination { SHARE, SAVE }

/** User-initiated system share or SAF save; implementations own and clean their private temp. */
interface FileExportPort {
    suspend fun export(file: ExportFile, destination: FileExportDestination): Boolean
    fun clear()
}
