package com.moatazvid.media

import com.moatazvid.core.*

data class RenderGraph(
    val projectId: ProjectId,
    val sequenceId: SequenceId,
    val timelineRevision: Long,
    val canvas: OutputCanvas,
    val videoLayers: List<VideoLayer>,
    val audioLayers: List<AudioLayer>,
    val overlays: List<OverlayNode>,
    val transitions: List<TransitionNode>,
    val duration: DurationUs,
) {
    init {
        require(timelineRevision >= 0)
        require(duration.value > 0)
        require(videoLayers.map { it.id }.distinct().size == videoLayers.size)
    }
}

data class OutputCanvas(
    val width: Int,
    val height: Int,
    val frameRate: Rational,
    val colorMode: ProjectColorMode,
    val backgroundArgb: Long,
) {
    init { require(width > 0 && height > 0) }
}

sealed interface MediaInput {
    val stableId: String
    data class Original(val sourceId: SourceId, override val stableId: String = sourceId.value) : MediaInput
    data class Proxy(val sourceId: SourceId, val proxyId: String, override val stableId: String = proxyId) : MediaInput
    data class Asset(val assetId: AssetId, override val stableId: String = assetId.value) : MediaInput
}

data class TimelinePlacement(val start: TimeUs, val duration: DurationUs)

data class VideoLayer(
    val id: ClipId,
    val trackId: TrackId,
    val input: MediaInput,
    val sourceRange: TimeRangeUs,
    val placement: TimelinePlacement,
    val transform: TransformNode,
    val opacity: Float,
    val speed: SpeedCurve,
    val effects: List<VideoEffectNode>,
    val includeSourceAudio: Boolean,
) {
    init { require(opacity in 0f..1f) }
}

data class AudioLayer(
    val id: ClipId,
    val trackId: TrackId,
    val input: MediaInput,
    val sourceRange: TimeRangeUs?,
    val placement: TimelinePlacement,
    val gainDb: Float,
    val pan: Float,
    val muted: Boolean,
    val preservePitch: Boolean,
    val speed: SpeedCurve,
    val fadeIn: DurationUs,
    val fadeOut: DurationUs,
    val role: AudioRole,
) {
    init {
        require(gainDb in -60f..24f)
        require(pan in -1f..1f)
        require(fadeIn.value + fadeOut.value <= placement.duration.value)
    }
}

enum class AudioRole { DIALOGUE, MUSIC, VOICE_OVER, AMBIENCE, SFX }

data class TransformNode(
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
) {
    init {
        require(positionX.isFinite() && positionY.isFinite())
        require(scaleX > 0 && scaleY > 0)
        require(cropLeft in 0f..1f && cropRight in 0f..1f && cropLeft < cropRight)
        require(cropTop in 0f..1f && cropBottom in 0f..1f && cropTop < cropBottom)
    }
}

data class SpeedSegmentNode(
    val sourceRange: TimeRangeUs,
    val speedStart: Double,
    val speedEnd: Double,
    val interpolation: SpeedInterpolation,
) {
    init { require(speedStart in 0.25..4.0 && speedEnd in 0.25..4.0) }
}

enum class SpeedInterpolation { CONSTANT, LINEAR }

data class SpeedCurve(val segments: List<SpeedSegmentNode>) {
    init { require(segments.isNotEmpty()) }
    val isConstant: Boolean get() = segments.size == 1 && segments.single().speedStart == segments.single().speedEnd
    val constantSpeedOrNull: Double? get() = if (isConstant) segments.single().speedStart else null
}

sealed interface VideoEffectNode {
    data class ColorAdjustment(val brightness: Float, val contrast: Float, val saturation: Float) : VideoEffectNode
    data class CustomRegistered(val registryKey: String, val schemaVersion: Int, val parameters: Map<String, Double>) : VideoEffectNode
}

sealed interface OverlayNode {
    val id: String
    val range: TimeRangeUs
    val transform: TransformNode
    val opacity: Float

    data class Text(
        override val id: String,
        override val range: TimeRangeUs,
        override val transform: TransformNode,
        override val opacity: Float,
        val text: String,
        val styleId: String,
    ) : OverlayNode

    data class Caption(
        override val id: String,
        override val range: TimeRangeUs,
        override val transform: TransformNode,
        override val opacity: Float,
        val text: String,
        val styleId: String,
    ) : OverlayNode

    data class Image(
        override val id: String,
        override val range: TimeRangeUs,
        override val transform: TransformNode,
        override val opacity: Float,
        val assetId: AssetId,
    ) : OverlayNode
}

data class TransitionNode(
    val id: String,
    val outgoingClipId: ClipId,
    val incomingClipId: ClipId,
    val type: TransitionType,
    val duration: DurationUs,
)

enum class TransitionType { CUT, CROSSFADE, DIP_TO_COLOR }

