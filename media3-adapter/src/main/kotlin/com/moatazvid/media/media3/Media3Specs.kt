package com.moatazvid.media.media3

import com.moatazvid.core.*
import com.moatazvid.media.*

/** Stable, testable boundary immediately before Media3 classes. */
data class Media3CompositionSpec(
    val sequences: List<Media3SequenceSpec>,
    val canvas: OutputCanvas,
    val hdrMode: Media3HdrMode,
    val audioMixingEnabled: Boolean,
)

data class Media3SequenceSpec(
    val trackId: TrackId,
    val role: SequenceRole,
    val items: List<Media3EditedItemSpec>,
)

enum class SequenceRole { PRIMARY_VIDEO, OVERLAY_VIDEO, DIALOGUE_AUDIO, MUSIC_AUDIO, OTHER_AUDIO }
enum class Media3HdrMode { KEEP_HDR, TONE_MAP_TO_SDR, FORCE_SDR }

data class Media3EditedItemSpec(
    val stableId: String,
    val resolverToken: String,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val removeAudio: Boolean,
    val removeVideo: Boolean,
    val speed: Double,
    val gainDb: Float,
    val transform: TransformNode?,
    val effects: List<VideoEffectNode>,
)

interface Media3InputResolver {
    /** Returns an opaque token resolved to a MediaItem inside the Android binding only. */
    suspend fun tokenFor(input: MediaInput, preferProxy: Boolean): String
}

class Media3CompositionMapper(private val resolver: Media3InputResolver) {
    suspend fun map(graph: RenderGraph, preferProxy: Boolean): Media3CompositionSpec {
        val video = graph.videoLayers.groupBy { it.trackId }.map { (trackId, clips) ->
            Media3SequenceSpec(trackId, SequenceRole.PRIMARY_VIDEO, clips.sortedBy { it.placement.start.value }.map {
                Media3EditedItemSpec(
                    stableId = it.input.stableId,
                    resolverToken = resolver.tokenFor(it.input, preferProxy),
                    sourceStartUs = it.sourceRange.start.value,
                    sourceEndUs = it.sourceRange.endExclusive.value,
                    removeAudio = !it.includeSourceAudio,
                    removeVideo = false,
                    speed = it.speed.constantSpeedOrNull ?: error("Variable speed requires fallback"),
                    gainDb = 0f,
                    transform = it.transform,
                    effects = it.effects,
                )
            })
        }
        val audio = graph.audioLayers.groupBy { it.trackId }.map { (trackId, clips) ->
            val role = when (clips.first().role) {
                AudioRole.DIALOGUE, AudioRole.VOICE_OVER -> SequenceRole.DIALOGUE_AUDIO
                AudioRole.MUSIC -> SequenceRole.MUSIC_AUDIO
                else -> SequenceRole.OTHER_AUDIO
            }
            Media3SequenceSpec(trackId, role, clips.sortedBy { it.placement.start.value }.map {
                Media3EditedItemSpec(
                    stableId = it.input.stableId,
                    resolverToken = resolver.tokenFor(it.input, preferProxy),
                    sourceStartUs = it.sourceRange?.start?.value ?: 0,
                    sourceEndUs = it.sourceRange?.endExclusive?.value ?: it.placement.duration.value,
                    removeAudio = false,
                    removeVideo = true,
                    speed = it.speed.constantSpeedOrNull ?: error("Variable speed requires fallback"),
                    gainDb = if (it.muted) -60f else it.gainDb,
                    transform = null,
                    effects = emptyList(),
                )
            })
        }
        return Media3CompositionSpec(
            sequences = video + audio,
            canvas = graph.canvas,
            hdrMode = when (graph.canvas.colorMode) {
                ProjectColorMode.HDR_KEEP -> Media3HdrMode.KEEP_HDR
                ProjectColorMode.HDR_TO_SDR -> Media3HdrMode.TONE_MAP_TO_SDR
                ProjectColorMode.SDR -> Media3HdrMode.FORCE_SDR
            },
            audioMixingEnabled = graph.audioLayers.size > 1,
        )
    }
}

