package assistant.mobile.platform

import android.content.Context
import androidx.room.Room
import assistant.mobile.core.journal.JournalDatabase
import assistant.mobile.core.journal.buildJournalDatabase
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File

/** Platform factories for the future application composition root; no UI or network on creation. */
object NativeCorePlatform {
    fun httpEngine(): HttpClientEngine = OkHttp.create {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
    }

    fun journal(context: Context): JournalDatabase {
        val directory = File(context.applicationContext.noBackupFilesDir, "native-core")
        check(directory.isDirectory || directory.mkdirs()) { "Journal storage is unavailable" }
        return buildJournalDatabase(
            Room.databaseBuilder<JournalDatabase>(
                context.applicationContext, File(directory, "journal.db").absolutePath,
            ),
        )
    }
}
