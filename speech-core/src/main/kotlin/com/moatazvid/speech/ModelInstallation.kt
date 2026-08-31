package com.moatazvid.speech

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

interface ResumableModelSource {
    suspend fun open(url: String, offset: Long): InputStream
}

/** Downloads into a .part file, verifies SHA-256, then atomically publishes the model. */
class FileModelInstaller(
    private val root: Path,
    private val source: ResumableModelSource,
) : ModelInstaller {
    private val cancelled = ConcurrentHashMap.newKeySet<ModelPackId>()

    override suspend fun install(request: ModelInstallRequest, progress: (ModelInstallProgress) -> Unit): SpeechResult<WhisperModelPack> {
        val pack = request.pack
        if (request.expectedAvailableBytes < pack.sizeBytes * 11 / 10)
            return SpeechResult.Failure(SpeechError.InsufficientStorage(pack.sizeBytes * 11 / 10, request.expectedAvailableBytes))
        val target = root.resolve(pack.relativePath).normalize()
        if (!target.startsWith(root.normalize())) return SpeechResult.Failure(SpeechError.NativeRuntimeFailure("Unsafe model path"))
        val partial = target.resolveSibling("${target.fileName}.part")
        return try {
            Files.createDirectories(target.parent)
            var offset = if (request.resumeAllowed && Files.exists(partial)) Files.size(partial) else 0L
            source.open(pack.sourceUrl, offset).use { input ->
                Files.newOutputStream(partial, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled.remove(pack.id)) return SpeechResult.Failure(SpeechError.Cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); offset += count
                        progress(ModelInstallProgress(pack.id, offset, pack.sizeBytes, InstallStage.DOWNLOADING))
                    }
                }
            }
            progress(ModelInstallProgress(pack.id, offset, pack.sizeBytes, InstallStage.VERIFYING))
            if (offset != pack.sizeBytes || sha256(partial) != pack.sha256.lowercase()) {
                Files.deleteIfExists(partial)
                SpeechResult.Failure(SpeechError.CorruptedModel(pack.id))
            } else {
                progress(ModelInstallProgress(pack.id, offset, pack.sizeBytes, InstallStage.COMMITTING))
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                SpeechResult.Success(pack.copy(status = ModelPackStatus.INSTALLED))
            }
        } catch (failure: Throwable) {
            SpeechResult.Failure(SpeechError.NativeRuntimeFailure(failure.message ?: "Model installation failed"))
        }
    }

    override suspend fun cancel(modelPackId: ModelPackId) { cancelled += modelPackId }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
