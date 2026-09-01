package com.moatazvid.media.media3

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Result from the persistent export kernel. The kernel owns Room job reconciliation and rendering. */
sealed interface BackgroundExportOutcome {
    data object Completed : BackgroundExportOutcome
    data class Retry(val reasonCode: String) : BackgroundExportOutcome
    data class Failed(val reasonCode: String) : BackgroundExportOutcome
}

/**
 * Implemented by the application composition root. run() must reload the job by id from persistent
 * storage; no RenderGraph or media URI is stored in transient Worker input Data.
 */
interface PersistentExportWorkKernel {
    suspend fun run(jobId: String, progress: suspend (percent: Int?) -> Unit): BackgroundExportOutcome
    suspend fun cancel(jobId: String)
}

fun interface ExportForegroundNotificationFactory {
    fun create(context: Context, jobId: String, progress: Int?): Notification
}

/** Long-running media-processing worker. Cancellation is propagated to MediaEngine. */
class MoatazExportWorker(
    appContext: Context,
    params: WorkerParameters,
    private val kernel: PersistentExportWorkKernel,
    private val notificationFactory: ExportForegroundNotificationFactory,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure(workDataOf(KEY_ERROR to "MISSING_JOB_ID"))
        setForeground(foreground(jobId, null))
        return try {
            when (val outcome = kernel.run(jobId) { percent ->
                val normalized = percent?.coerceIn(0, 100)
                setProgress(workDataOf(KEY_PROGRESS to (normalized ?: -1)))
                setForeground(foreground(jobId, normalized))
            }) {
                BackgroundExportOutcome.Completed -> Result.success(workDataOf(KEY_JOB_ID to jobId))
                is BackgroundExportOutcome.Retry -> Result.retry()
                is BackgroundExportOutcome.Failed -> Result.failure(workDataOf(KEY_JOB_ID to jobId, KEY_ERROR to outcome.reasonCode))
            }
        } catch (cancelled: CancellationException) {
            kernel.cancel(jobId)
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(workDataOf(KEY_JOB_ID to jobId, KEY_ERROR to failure.javaClass.simpleName))
        }
    }

    private fun foreground(jobId: String, progress: Int?): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID_BASE + (jobId.hashCode() and 0x7FFF),
        notificationFactory.create(applicationContext, jobId, progress),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
    )

    companion object {
        const val KEY_JOB_ID = "moataz.export.job_id"
        const val KEY_PROGRESS = "moataz.export.progress"
        const val KEY_ERROR = "moataz.export.error"
        private const val NOTIFICATION_ID_BASE = 41_000
    }
}

/** Must be installed in WorkManager Configuration so the worker can receive persistent dependencies. */
class MoatazExportWorkerFactory(
    private val kernel: PersistentExportWorkKernel,
    private val notificationFactory: ExportForegroundNotificationFactory,
) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == MoatazExportWorker::class.java.name) {
            MoatazExportWorker(appContext, workerParameters, kernel, notificationFactory)
        } else null
}

class ExportWorkScheduler(private val context: Context) {
    fun enqueue(jobId: String) {
        require(jobId.isNotBlank())
        val request = OneTimeWorkRequestBuilder<MoatazExportWorker>()
            .setInputData(Data.Builder().putString(MoatazExportWorker.KEY_JOB_ID, jobId).build())
            .addTag(TAG_EXPORT)
            .addTag("$TAG_EXPORT:$jobId")
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("moataz-export-$jobId", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("moataz-export-$jobId")
    }

    companion object { const val TAG_EXPORT = "moataz-export" }
}
