package com.moatazvid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MoatazVidApplication : Application() {
    @Volatile
    private var cachedRepository: Result<ProductionProjectRepository>? = null

    /** Nullable so a Room/native failure can be shown in Compose instead of crashing cold start. */
    val projects: ProductionProjectRepository?
        get() = repositoryResult().getOrNull()

    @Synchronized
    fun repositoryResult(): Result<ProductionProjectRepository> {
        cachedRepository?.let { return it }
        return runCatching { ProductionProjectRepository.create(this) }.also { cachedRepository = it }
    }

    /**
     * Room opens lazily. Force one real query off the main thread so migrations/schema validation are
     * completed before the home Composable starts collecting database flows.
     */
    suspend fun verifiedRepositoryResult(): Result<ProductionProjectRepository> = withContext(Dispatchers.IO) {
        val result = repositoryResult()
        val repository = result.getOrElse { return@withContext Result.failure(it) }
        try {
            repository.database.projectDao().all()
            Result.success(repository)
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    suspend fun retryVerifiedRepository(): Result<ProductionProjectRepository> {
        retryRepository()
        return verifiedRepositoryResult()
    }

    @Synchronized
    fun retryRepository(): Result<ProductionProjectRepository> {
        cachedRepository?.getOrNull()?.let { repository -> runCatching { repository.database.close() } }
        cachedRepository = null
        return repositoryResult()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CHANNEL_EXPORT, "Video export", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_TRANSCRIPTION, "Local transcription", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_MODELS, "Model downloads", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_PROXY, "Proxy generation", NotificationManager.IMPORTANCE_LOW),
        ).forEach(manager::createNotificationChannel)
    }

    companion object {
        const val CHANNEL_EXPORT = "export"
        const val CHANNEL_TRANSCRIPTION = "transcription"
        const val CHANNEL_MODELS = "models"
        const val CHANNEL_PROXY = "proxy"
    }
}
