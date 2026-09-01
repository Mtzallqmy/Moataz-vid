package com.moatazvid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MoatazVidApplication : Application() {
    val projects: ProductionProjectRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProductionProjectRepository.create(this)
    }

    val aiProviders: ProductionAiProviderRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProductionAiProviderRuntime(this, projects.database)
    }

    val speech: ProductionSpeechRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ProductionSpeechRuntime(this, projects)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Worker processes need their persisted transcription runner registered before WorkManager executes.
        speech
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
