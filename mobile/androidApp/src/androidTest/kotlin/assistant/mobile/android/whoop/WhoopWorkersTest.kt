package assistant.mobile.android.whoop

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real WorkManager entry points; unknown collectors cannot open BLE or issue account requests. */
@RunWith(AndroidJUnit4::class)
class WhoopWorkersTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyJobsWithoutOriginStillCompleteAndExplicitForeignJobsAreCanceled() {
        val tag = "synthetic-whoop-workers-${UUID.randomUUID()}"
        val collector = "synthetic-missing-collector-${UUID.randomUUID()}"
        val homeInputs = workDataOf("collector" to collector)
        val foreignInputs = workDataOf("collector" to collector, "origin" to "https://synthetic-foreign.invalid")
        fun requests(input: Data): List<OneTimeWorkRequest> = listOf(
            OneTimeWorkRequestBuilder<WhoopCollectWorker>().addTag(tag).setInputData(input).build(),
            OneTimeWorkRequestBuilder<WhoopCollectWorker>().addTag(tag)
                .setInputData(Data.Builder().putAll(input).putBoolean("continuation", true).build()).build(),
            OneTimeWorkRequestBuilder<WhoopUploadWorker>().addTag(tag).setInputData(input).build(),
        )
        val legacy = requests(homeInputs)
        val foreign = requests(foreignInputs)
        val work = WorkManager.getInstance(context)
        try {
            work.enqueue(legacy + foreign).result.get(10, TimeUnit.SECONDS)
            for (request in legacy) awaitState(work, request.id, WorkInfo.State.SUCCEEDED)
            for (request in foreign) awaitState(work, request.id, WorkInfo.State.CANCELLED)
        } finally { work.cancelAllWorkByTag(tag).result.get(10, TimeUnit.SECONDS) }
    }

    private fun awaitState(work: WorkManager, id: UUID, expected: WorkInfo.State) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var actual: WorkInfo.State? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            actual = work.getWorkInfoById(id).get(10, TimeUnit.SECONDS)?.state
            if (actual == expected) return
            SystemClock.sleep(25)
        }
        assertEquals("WHOOP work $id", expected, actual)
    }
}
