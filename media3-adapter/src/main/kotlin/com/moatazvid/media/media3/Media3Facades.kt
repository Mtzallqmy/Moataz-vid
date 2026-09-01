package com.moatazvid.media.media3

import com.moatazvid.core.TimeUs
import com.moatazvid.media.*
import kotlinx.coroutines.flow.Flow

/** Wraps Transformer. The binding owns Media3 objects, callbacks, and cancellation. */
interface TransformerFacade {
    suspend fun export(jobId: String, composition: Media3CompositionSpec, outputUri: String, settings: ExportSettings): MediaResult<MediaJobHandle>
    suspend fun cancel(jobId: String)
    fun progress(jobId: String): Flow<MediaEngineProgress>
}

/** Wraps CompositionPlayer and supports replacing a composition at a known revision. */
interface CompositionPlayerFacade {
    suspend fun prepare(sessionId: String, composition: Media3CompositionSpec, surface: PreviewSurface)
    suspend fun replace(sessionId: String, composition: Media3CompositionSpec)
    suspend fun seek(sessionId: String, position: TimeUs)
    suspend fun play(sessionId: String)
    suspend fun pause(sessionId: String)
    suspend fun setMuted(sessionId: String, muted: Boolean)
    suspend fun currentPosition(sessionId: String): TimeUs
    suspend fun isPlaying(sessionId: String): Boolean
    suspend fun release(sessionId: String)
}

interface CodecCapabilityDetector {
    suspend fun detect(): List<CodecCapability>
    suspend fun canEncode(settings: ExportSettings): Boolean
    suspend fun canDecode(probe: MediaProbe): Boolean
}
