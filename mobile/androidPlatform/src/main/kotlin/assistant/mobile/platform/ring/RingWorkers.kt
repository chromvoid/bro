package assistant.mobile.platform.ring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import assistant.mobile.platform.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private suspend fun CoroutineWorker.inactiveBinding(origin: String): Boolean {
    if (!BuildConfig.PLAY_STORE && origin == RingRepository.configuredOrigin(applicationContext)) return false
    withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).cancelWorkById(id).result.get() }
    return true
}

class RingCollectWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val collector = inputData.getString("collector") ?: return Result.success()
        val origin = inputData.getString("origin")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return Result.success()
        if (inactiveBinding(origin)) return Result.success()
        return if (RingRepository.get(applicationContext, origin).collect(collector,
            inputData.getBoolean("continuation", false))) Result.success() else Result.retry()
    }
}
class RingUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val collector = inputData.getString("collector") ?: return Result.success()
        val origin = inputData.getString("origin")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return Result.success()
        if (inactiveBinding(origin)) return Result.success()
        return if (RingRepository.get(applicationContext, origin).upload(collector)) Result.success() else Result.retry()
    }
}
class RingWorkoutTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val collector = inputData.getString("collector") ?: return Result.success()
        val identity = inputData.getString("upload_identity") ?: return Result.success()
        val attemptId = inputData.getString("retry_id") ?: return Result.success()
        val origin = inputData.getString("origin")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return Result.success()
        if (inactiveBinding(origin)) return Result.success()
        return try {
            if (RingRepository.get(applicationContext, origin).transferWorkoutRecords(collector, identity, attemptId))
                Result.success() else Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
