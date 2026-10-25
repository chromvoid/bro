package assistant.mobile.core.journal

import androidx.room.Room
import java.io.File

/** The caller chooses a private path; this factory is also used with temporary synthetic stores. */
fun openJournalDatabase(file: File): JournalDatabase =
    buildJournalDatabase(Room.databaseBuilder<JournalDatabase>(name = file.absolutePath))
