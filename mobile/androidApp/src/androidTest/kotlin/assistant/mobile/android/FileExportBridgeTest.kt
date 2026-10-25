package assistant.mobile.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assistant.mobile.core.files.ExportFile
import assistant.mobile.core.files.FileExportDestination
import assistant.mobile.platform.AndroidExportStorage
import assistant.mobile.platform.AndroidFileExportStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Fake system launchers and isolated private files; never opens a real picker or share target. */
@RunWith(AndroidJUnit4::class)
class FileExportBridgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val directory = File(instrumentation.targetContext.cacheDir, "export-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = directory
    }
    private val storage = AndroidFileExportStorage(context)
    private val callers = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bridge: FileExportBridge? = null
    private val bytes = byteArrayOf(10, 20, 30, 40)
    private val file = ExportFile("synthetic.bin", "application/octet-stream", bytes)

    @After fun cleanup() {
        onMain { bridge?.close() }
        callers.cancel()
        directory.deleteRecursively()
    }

    @Test fun safTransactionSurvivesSceneRecreationAndCanceledUiWaiter() {
        val launched = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val released = CountDownLatch(1)
        val launchCount = AtomicInteger()
        val recording = object : AndroidExportStorage by storage {
            override fun save(source: File, destination: Uri, expectedGeneration: Long): Boolean =
                storage.save(source, destination, expectedGeneration).also { saved.countDown() }
            override fun release(file: File) { storage.release(file); released.countDown() }
        }
        val export = FileExportBridge(context, recording).also { bridge = it }
        val firstScene = Any()
        onMain { export.attach(firstScene) { launchCount.incrementAndGet(); launched.countDown() } }
        val waitingUi = callers.async { export.export(file, FileExportDestination.SAVE) }
        assertTrue(launched.await(10, TimeUnit.SECONDS))
        onMain {
            export.detach(firstScene)
            waitingUi.cancel()
            export.attach(Any()) { launchCount.incrementAndGet() }
        }
        val destination = File(directory, "saved.bin")
        onMain { export.result(ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))) }
        assertTrue(saved.await(10, TimeUnit.SECONDS))
        assertTrue(released.await(10, TimeUnit.SECONDS))
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(1, launchCount.get())
        assertTrue(waitingUi.isCancelled)
        assertNoStagedFiles()
    }

    @Test fun explicitSystemCancellationRemovesStagedFile() = runBlocking {
        val launched = CountDownLatch(1)
        val export = FileExportBridge(context, storage).also { bridge = it }
        onMain { export.attach(Any()) { launched.countDown() } }
        val result = callers.async { export.export(file, FileExportDestination.SAVE) }
        assertTrue(launched.await(10, TimeUnit.SECONDS))
        onMain { export.result(ActivityResult(Activity.RESULT_CANCELED, null)) }
        assertFalse(withTimeout(10_000) { result.await() })
        assertNoStagedFiles()
    }

    @Test fun accountClearRejectsAnOldGenerationStillStaging() {
        val entered = CountDownLatch(1)
        val continueStage = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val rejected = AtomicBoolean()
        val launchCount = AtomicInteger()
        val blocked = object : AndroidExportStorage by storage {
            override fun stage(file: ExportFile, expectedGeneration: Long): File? {
                entered.countDown()
                check(continueStage.await(10, TimeUnit.SECONDS))
                return storage.stage(file, expectedGeneration).also {
                    rejected.set(it == null)
                    finished.countDown()
                }
            }
        }
        val export = FileExportBridge(context, blocked).also { bridge = it }
        onMain { export.attach(Any()) { launchCount.incrementAndGet() } }
        callers.async { export.export(file, FileExportDestination.SAVE) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            onMain { export.clear() }
        } finally { continueStage.countDown() }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertTrue(rejected.get())
        assertEquals(0, launchCount.get())
        assertNoStagedFiles()
    }

    private fun assertNoStagedFiles() {
        val staging = File(directory, AndroidFileExportStorage.DIRECTORY)
        assertTrue(!staging.exists() || staging.walkTopDown().none { it.isFile })
    }

    private fun onMain(action: () -> Unit) { instrumentation.runOnMainSync { action() } }
}
