package com.moatazvid.speech

enum class ModelPackStatus { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, CORRUPT, PENDING_DELETE }

data class WhisperModelPack(
    val id: ModelPackId,
    val displayName: String,
    val version: String,
    val sizeBytes: Long,
    val requiredRamBytes: Long,
    val multilingual: Boolean,
    val languages: Set<LanguageCode>,
    val sha256: String,
    val relativePath: String,
    val license: String,
    val sourceUrl: String,
    val status: ModelPackStatus,
    val activeLeaseCount: Int = 0,
) {
    init {
        require(sizeBytes > 0 && requiredRamBytes > 0)
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(!relativePath.startsWith('/'))
        require(activeLeaseCount >= 0)
    }
}

interface ModelManager {
    suspend fun list(): List<WhisperModelPack>
    suspend fun get(id: ModelPackId): WhisperModelPack?
    suspend fun acquire(id: ModelPackId): SpeechResult<ModelLease>
    suspend fun release(lease: ModelLease)
    suspend fun markCorrupt(id: ModelPackId, reason: String)
    suspend fun requestDelete(id: ModelPackId): SpeechResult<Unit>
}

data class ModelLease(val pack: WhisperModelPack, val token: String)

interface ModelInstaller {
    suspend fun install(request: ModelInstallRequest, progress: (ModelInstallProgress) -> Unit): SpeechResult<WhisperModelPack>
    suspend fun cancel(modelPackId: ModelPackId)
}

data class ModelInstallRequest(
    val pack: WhisperModelPack,
    val expectedAvailableBytes: Long,
    val resumeAllowed: Boolean = true,
)

data class ModelInstallProgress(val modelPackId: ModelPackId, val downloadedBytes: Long, val totalBytes: Long, val stage: InstallStage)
enum class InstallStage { DOWNLOADING, VERIFYING, COMMITTING }

data class DeviceCapabilities(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCores: Int,
    val abi: String,
    val thermalStatus: ThermalStatus,
    val batteryLow: Boolean,
)
enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, UNKNOWN }

class DeviceCapabilityEstimator {
    fun selectModel(packs: List<WhisperModelPack>, device: DeviceCapabilities): WhisperModelPack? =
        packs.filter { it.status == ModelPackStatus.INSTALLED }
            .filter { it.requiredRamBytes <= (device.availableRamBytes * 0.65).toLong() }
            .filterNot { device.thermalStatus in setOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL) && it.requiredRamBytes > 900_000_000 }
            .maxByOrNull { it.requiredRamBytes }
}

