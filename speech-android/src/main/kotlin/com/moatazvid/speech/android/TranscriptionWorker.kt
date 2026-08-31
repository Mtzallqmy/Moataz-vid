package com.moatazvid.speech.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** App composition installs a runner; WorkManager then resumes the persisted job after process death. */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        return when (TranscriptionWorkerRegistry.runner?.invoke(jobId)) {
            WorkerOutcome.SUCCESS -> Result.success()
            WorkerOutcome.RETRY -> Result.retry()
            else -> Result.failure()
        }
    }
    companion object { const val KEY_JOB_ID = "transcription_job_id" }
}

enum class WorkerOutcome { SUCCESS, RETRY, FAILURE }
object TranscriptionWorkerRegistry { @Volatile var runner: (suspend (String) -> WorkerOutcome)? = null }
