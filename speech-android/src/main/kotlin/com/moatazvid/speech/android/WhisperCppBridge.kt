package com.moatazvid.speech.android

import com.moatazvid.speech.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JNI boundary only. Native memory is represented by an opaque handle and never exposed to AI code. */
class WhisperCppBridge : WhisperNativeBridge {
    override suspend fun loadModel(absolutePath: String, threads: Int): SpeechResult<Long> = withContext(Dispatchers.IO) {
        nativeLoadResult.exceptionOrNull()?.let { failure ->
            return@withContext SpeechResult.Failure(
                SpeechError.NativeRuntimeFailure("Native speech engine unavailable: ${failure.message ?: failure.javaClass.simpleName}")
            )
        }
        runCatching { nativeLoadModel(absolutePath, threads) }.fold(
            onSuccess = { if (it == 0L) SpeechResult.Failure(SpeechError.NativeRuntimeFailure(nativeLastError())) else SpeechResult.Success(it) },
            onFailure = { SpeechResult.Failure(SpeechError.NativeRuntimeFailure(it.message ?: "Unable to load model")) },
        )
    }

    override suspend fun transcribe(modelHandle: Long, mono16Khz: FloatArray, language: String, wordTimestamps: Boolean, cancelled: () -> Boolean): SpeechResult<NativeTranscription> = withContext(Dispatchers.Default) {
        if (cancelled()) return@withContext SpeechResult.Failure(SpeechError.Cancelled)
        nativeLoadResult.exceptionOrNull()?.let { failure ->
            return@withContext SpeechResult.Failure(
                SpeechError.NativeRuntimeFailure("Native speech engine unavailable: ${failure.message ?: failure.javaClass.simpleName}")
            )
        }
        val rows = runCatching { nativeTranscribe(modelHandle, mono16Khz, language, wordTimestamps) }
            .getOrElse { failure ->
                return@withContext SpeechResult.Failure(
                    SpeechError.NativeRuntimeFailure(failure.message ?: "Native transcription failed")
                )
            }
            ?: return@withContext SpeechResult.Failure(SpeechError.NativeRuntimeFailure(nativeLastError()))
        val segments = rows.map { row ->
            val fields = row.split('\u001f')
            val start = fields.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = fields.getOrNull(1)?.toLongOrNull() ?: start + 1
            val text = fields.getOrNull(2).orEmpty()
            NativeSegment(text, start, end, null, listOf(NativeWord(text, start, end, null)))
        }
        SpeechResult.Success(NativeTranscription(language, null, segments))
    }

    override suspend fun unloadModel(modelHandle: Long) = withContext(Dispatchers.IO) {
        if (nativeLoadResult.isSuccess) runCatching { nativeFreeModel(modelHandle) }
        Unit
    }

    private external fun nativeLoadModel(path: String, threads: Int): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, wordTimestamps: Boolean): Array<String>?
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeLastError(): String

    companion object {
        private val nativeLoadResult: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            runCatching { System.loadLibrary("moataz_whisper") }
        }
    }
}
