package com.moatazvid.media.media3

import com.moatazvid.core.*
import com.moatazvid.media.*

/** Stable, testable boundary immediately before Media3 classes. */
data class Media3CompositionSpec(
    val sequences: List<Media3SequenceSpec>,
    val canvas: OutputCanvas,
    val hdrMode: Media3HdrMode,
    val audioMixingEnabled: Boolean,
    val overlays: List<Media3OverlaySpec> = emptyList(),
    val transitions: List<Media3TransitionSpec> = emptyList(),
)

data class Media3SequenceSpec(
    val trackId: TrackId,
    val role: SequenceRole,
    val items: List<Media3EditedItemSpec>,
)

enum class SequenceRole { PRIMARY_VIDEO, OVERLAY_VIDEO, DIALOGUE_AUDIO, MUSIC_AUDIO, OTHER_AUDIO }
enum class Media3HdrMode { KEEP_HDR, TONE_MAP_TO_SDR, FORCE_SDR }
enum class Media3OverlayKind { TEXT, CAPTION, IMAGE, GRAPHIC }

data class Media3OverlaySpec(
    val id: String,
    val kind: Media3OverlayKind,
    val startUs: Long,
    val endUs: Long,
    val transform: TransformNode,
    val opacity: Float,
    val text: String? = null,
    val styleId: String? = null,
    val assetId: AssetId? = null,
    /** Android binding resolves only trusted tokens produced by Media3InputResolver. */
    val assetResolverToken: String? = null,
    val graphicPrimitive: ShapePrimitive? = null,
    val fillArgb: Long? = null,
    val strokeArgb: Long? = null,
    val strokeWidth: Float? = null,
)

data class Media3TransitionSpec(
    val id: String,
    val fromClipId: ClipId,
    val toClipId: ClipId,
    val type: TransitionType,
    val durationUs: Long,
)

data class Media3EditedItemSpec(
    val stableId: String,
    val resolverToken: String,
    /** Intrinsic source duration before clipping, needed for CompositionPlayer. */
    val sourceDurationUs: Long,
    val sourceStartUs: Long,
    val sourceEndUs: Long,
    val removeAudio: Boolean,
    val removeVideo: Boolean,
    val speed: Double,
    val gainDb: Float,
    val transform: TransformNode?,
    val effects: List<VideoEffectNode>,
) {
    init {
        require(sourceDurationUs > 0)
        require(sourceStartUs >= 0 && sourceEndUs > sourceStartUs && sourceEndUs <= sourceDurationUs)
    }
}

interface Media3InputResolver {
    /** Returns an opaque token resolved to a MediaItem inside the Android binding only. */
    suspend fun tokenFor(input: MediaInput, preferProxy: Boolean): String

    /** Prefer a probe/cache backed duration. Null falls back to the clip's known source end. */
    suspend fun sourceDurationUs(input: MediaInput, preferProxy: Boolean): Long? = null
}

class Media3CompositionMapper(private val resolver: Media3InputResolver) {
    suspend fun map(graph: RenderGraph, preferProxy: Boolean): Media3CompositionSpec {
        val video = graph.videoLayers.groupBy { it.trackId }.map { (trackId, clips) ->
            Media3SequenceSpec(trackId, SequenceRole.PRIMARY_VIDEO, clips.sortedBy { it.placement.start.value }.map {
                val sourceDuration = maxOf(resolver.sourceDurationUs(it.input, preferProxy) ?: it.sourceRange.endExclusive.value, it.sourceRange.endExclusive.value)
                Media3EditedItemSpec(
                    stableId = it.input.stableId,
                    resolverToken = resolver.tokenFor(it.input, preferProxy),
                    sourceDurationUs = sourceDuration,
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
                val startUs = it.sourceRange?.start?.value ?: 0
                val endUs = it.sourceRange?.endExclusive?.value ?: it.placement.duration.value
                val sourceDuration = maxOf(resolver.sourceDurationUs(it.input, preferProxy) ?: endUs, endUs)
                Media3EditedItemSpec(
                    stableId = it.input.stableId,
                    resolverToken = resolver.tokenFor(it.input, preferProxy),
                    sourceDurationUs = sourceDuration,
                    sourceStartUs = startUs,
                    sourceEndUs = endUs,
                    removeAudio = false,
                    removeVideo = true,
                    speed = it.speed.constantSpeedOrNull ?: error("Variable speed requires fallback"),
                    gainDb = if (it.muted) -60f else it.gainDb,
                    transform = null,
                    effects = emptyList(),
                )
            })
        }
        val overlays = graph.overlays.sortedBy { it.range.start.value }.map { node ->
            when (node) {
                is OverlayNode.Text -> Media3OverlaySpec(node.id, Media3OverlayKind.TEXT, node.range.start.value, node.range.endExclusive.value, node.transform, node.opacity, text = node.text, styleId = node.styleId)
                is OverlayNode.Caption -> Media3OverlaySpec(node.id, Media3OverlayKind.CAPTION, node.range.start.value, node.range.endExclusive.value, node.transform, node.opacity, text = node.text, styleId = node.styleId)
                is OverlayNode.Image -> Media3OverlaySpec(
                    node.id,
                    Media3OverlayKind.IMAGE,
                    node.range.start.value,
                    node.range.endExclusive.value,
                    node.transform,
                    node.opacity,
                    assetId = node.assetId,
                    assetResolverToken = resolver.tokenFor(MediaInput.Asset(node.assetId), preferProxy = false),
                )
                is GraphicOverlayNode -> Media3OverlaySpec(
                    node.id,
                    Media3OverlayKind.GRAPHIC,
                    node.range.start.value,
                    node.range.endExclusive.value,
                    node.transform,
                    node.opacity,
                    graphicPrimitive = node.primitive,
                    fillArgb = node.fillArgb,
                    strokeArgb = node.strokeArgb,
                    strokeWidth = node.strokeWidth,
                )
            }
        }
        val transitions = graph.transitions.map {
            Media3TransitionSpec(it.id, it.outgoingClipId, it.incomingClipId, it.type, it.duration.value)
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
            overlays = overlays,
            transitions = transitions,
        )
    }
}
