package assistant.mobile.android.whoop

import android.content.Context
import androidx.work.*
import assistant.mobile.platform.AndroidNativeOrigins
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidWhoopWorkScheduler(context: Context, private val origin: String) : WhoopWorkScheduler {
    private val work = WorkManager.getInstance(context)
    override fun schedule(collector: String) {
        val request = PeriodicWorkRequestBuilder<WhoopCollectWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .addTag(TAG).setInputData(workDataOf("collector" to collector, "origin" to origin)).build()
        work.enqueueUniquePeriodicWork(collectName(collector), ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    override fun continueHistory(collector: String) {
        val request = OneTimeWorkRequestBuilder<WhoopCollectWorker>()
            .addTag(TAG).setInitialDelay(1, TimeUnit.MINUTES)
            .setInputData(workDataOf("collector" to collector, "origin" to origin, "continuation" to true)).build()
        work.enqueueUniqueWork(continuationName(collector), ExistingWorkPolicy.REPLACE, request)
    }
    override fun upload(collector: String) {
        val request = OneTimeWorkRequestBuilder<WhoopUploadWorker>()
            .addTag(TAG).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf("collector" to collector, "origin" to origin)).build()
        work.enqueueUniqueWork("native.whoop.upload.$collector", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    override fun cancelCollect(collector: String) {
        work.cancelUniqueWork(collectName(collector))
        work.cancelUniqueWork(continuationName(collector))
    }
    override fun cancelAll() { work.cancelAllWorkByTag(TAG) }
    override suspend fun cancelAllForServerSwitch() {
        withContext(Dispatchers.IO) { work.cancelAllWorkByTag(TAG).result.get() }
    }
    override fun revoke() {
        val request = OneTimeWorkRequestBuilder<WhoopRevokeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        // Revocation survives cancellation of the old collector's work and later account changes.
        work.enqueueUniqueWork("native.whoop.revoke", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    private fun collectName(id: String) = "native.whoop.collect.$id"
    private fun continuationName(id: String) = "native.whoop.continue.$id"
    private companion object { const val TAG = "assistant.native.whoop" }
}

class WhoopCollectWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val home = AndroidNativeOrigins.homeOrigin(applicationContext)
        val origin = inputData.getString("origin") ?: home
        if (origin != home) {
            WorkManager.getInstance(applicationContext).cancelWorkById(id)
            return Result.success()
        }
        val collector = inputData.getString("collector") ?: return Result.success()
        WhoopRepository.get(applicationContext, home)
            .collect(collector, inputData.getBoolean("continuation", false))
        return Result.success()
    }
}

class WhoopUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val home = AndroidNativeOrigins.homeOrigin(applicationContext)
        val origin = inputData.getString("origin") ?: home
        if (origin != home) {
            WorkManager.getInstance(applicationContext).cancelWorkById(id)
            return Result.success()
        }
        val collector = inputData.getString("collector") ?: return Result.success()
        return if (WhoopRepository.get(applicationContext, home).upload(collector)) Result.success() else Result.retry()
    }
}

class WhoopRevokeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        // Upgrade the old home-only journal before discovering queues from every server.
        AndroidWhoopRevokeQueue(applicationContext, AndroidNativeOrigins.homeOrigin(applicationContext)).all()
        val complete = drainWhoopRevocations(
            AndroidWhoopRevokeQueue.origins(applicationContext),
            queue = { AndroidWhoopRevokeQueue(applicationContext, it) },
            serverOrigin = { origin -> AndroidNativeOrigins.serverOrigin(applicationContext, origin) },
        )
        if (complete) Result.success() else Result.retry()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
