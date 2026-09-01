package com.moatazvid.media.media3

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.moatazvid.media.AtomicOutputTarget
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Target that can expose the actual published Uri after commit without changing the core contract. */
interface AndroidPublishedOutputTarget : AtomicOutputTarget {
    val publishedUri: String?
}

class AndroidAtomicOutputTargetFactory(
    private val context: Context,
    private val tempDirectory: File = File(context.cacheDir, "verified-exports"),
) {
    init { tempDirectory.mkdirs() }

    fun appFile(destination: File, overwrite: Boolean = false): AndroidPublishedOutputTarget =
        AppFileTarget(newTempFile(destination.extension.ifBlank { "mp4" }), destination, overwrite)

    /**
     * SAF providers do not universally expose an atomic rename primitive. Moataz vid therefore renders
     * and verifies locally first, then performs the single destination copy only during commit.
     */
    fun saf(destination: Uri): AndroidPublishedOutputTarget =
        SafTarget(context, newTempFile(destination.lastPathSegment?.substringAfterLast('.', "mp4") ?: "mp4"), destination)

    /** MediaStore rows remain IS_PENDING until the already-verified local file has copied completely. */
    fun mediaStoreVideo(
        displayName: String,
        mimeType: String = "video/mp4",
        relativePath: String = "Movies/Moataz vid",
    ): AndroidPublishedOutputTarget {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Pending MediaStore publication requires Android 10+" }
        return MediaStoreTarget(context, newTempFile(displayName.substringAfterLast('.', "mp4")), displayName, mimeType, relativePath)
    }

    fun cleanupStaleTemps(maxAgeMs: Long, activeTemporaryUris: Set<String> = emptySet(), nowMs: Long = System.currentTimeMillis()): Int {
        if (maxAgeMs <= 0) return 0
        var deleted = 0
        tempDirectory.listFiles().orEmpty().forEach { file ->
            val active = file.absolutePath in activeTemporaryUris || file.toURI().toString() in activeTemporaryUris
            if (!active && nowMs - file.lastModified() >= maxAgeMs && file.delete()) deleted++
        }
        return deleted
    }

    private fun newTempFile(extension: String): File {
        tempDirectory.mkdirs()
        return File(tempDirectory, "${UUID.randomUUID()}-export.${extension.ifBlank { "mp4" }}")
    }

    private class AppFileTarget(
        private val temp: File,
        private val destination: File,
        private val overwrite: Boolean,
    ) : AndroidPublishedOutputTarget {
        override val temporaryUri: String = temp.absolutePath
        override val finalUri: String = destination.toURI().toString()
        override var publishedUri: String? = null
            private set

        override suspend fun commit(): Boolean {
            if (!temp.isFile || temp.length() <= 0L) return false
            destination.parentFile?.mkdirs()
            if (destination.exists() && !overwrite) return false
            val staging = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.publishing")
            if (!copyFile(temp, staging)) return false
            if (destination.exists() && overwrite) {
                val backup = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.backup")
                if (!destination.renameTo(backup)) { staging.delete(); return false }
                if (!staging.renameTo(destination)) {
                    backup.renameTo(destination)
                    staging.delete()
                    return false
                }
                backup.delete()
            } else if (!staging.renameTo(destination)) {
                staging.delete()
                return false
            }
            temp.delete()
            publishedUri = destination.toURI().toString()
            return true
        }

        override suspend fun abort() { temp.delete() }
    }

    private class SafTarget(
        private val context: Context,
        private val temp: File,
        private val destination: Uri,
    ) : AndroidPublishedOutputTarget {
        override val temporaryUri: String = temp.absolutePath
        override val finalUri: String = destination.toString()
        override var publishedUri: String? = null
            private set

        override suspend fun commit(): Boolean {
            if (!temp.isFile || temp.length() <= 0L) return false
            val ok = runCatching {
                context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    FileInputStream(temp).use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    output.flush()
                } ?: error("Destination is not writable")
                true
            }.getOrDefault(false)
            if (ok) {
                temp.delete()
                publishedUri = destination.toString()
            }
            return ok
        }

        override suspend fun abort() { temp.delete() }
    }

    private class MediaStoreTarget(
        private val context: Context,
        private val temp: File,
        private val displayName: String,
        private val mimeType: String,
        private val relativePath: String,
    ) : AndroidPublishedOutputTarget {
        override val temporaryUri: String = temp.absolutePath
        override val finalUri: String = "mediastore://video/$relativePath/$displayName"
        override var publishedUri: String? = null
            private set

        override suspend fun commit(): Boolean {
            if (!temp.isFile || temp.length() <= 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val copied = runCatching {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    FileInputStream(temp).use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    output.flush()
                } ?: error("MediaStore row not writable")
                val finalize = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, finalize, null, null) == 1
            }.getOrDefault(false)
            if (!copied) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            temp.delete()
            publishedUri = uri.toString()
            return true
        }

        override suspend fun abort() { temp.delete() }
    }

    companion object {
        private fun copyFile(source: File, destination: File): Boolean = runCatching {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            destination.length() == source.length() && destination.length() > 0L
        }.getOrDefault(false)
    }
}
