package com.moatazvid.media

import com.moatazvid.core.ProjectColorMode

enum class RenderFeature {
    TRIM, CONCATENATE, CROP, SCALE, ROTATE, TRANSLATE, CONSTANT_SPEED, VARIABLE_SPEED,
    AUDIO_MIX, AUDIO_FADE, AUDIO_GAIN_AUTOMATION, TEXT_OVERLAY, IMAGE_OVERLAY, CAPTION_BURN_IN,
    CROSSFADE, CUSTOM_EFFECT, KEEP_HDR, TONE_MAP_HDR, PROXY
}

data class CodecCapability(
    val mime: String,
    val decoder: Boolean,
    val encoder: Boolean,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val maxFps: Double?,
    val hdr: Boolean,
)

data class EngineCapabilities(
    val media3Features: Set<RenderFeature>,
    val ffmpegFeatures: Set<RenderFeature>,
    val codecs: List<CodecCapability>,
)

data class BackendDecision(
    val backend: BackendKind,
    val reasons: List<String>,
    val unsupported: Set<RenderFeature>,
)

class CapabilityResolver {
    fun resolve(graph: RenderGraph, capabilities: EngineCapabilities): BackendDecision {
        val required = requiredFeatures(graph)
        val media3Missing = required - capabilities.media3Features
        if (media3Missing.isEmpty()) return BackendDecision(BackendKind.MEDIA3, listOf("all_features_supported"), emptySet())

        val ffmpegMissing = required - capabilities.ffmpegFeatures
        if (ffmpegMissing.isEmpty()) {
            return BackendDecision(BackendKind.FFMPEG, media3Missing.map { "media3_missing:${it.name}" }, emptySet())
        }
        return BackendDecision(BackendKind.FFMPEG, listOf("no_backend_supports_complete_graph"), ffmpegMissing)
    }

    fun requiredFeatures(graph: RenderGraph): Set<RenderFeature> = buildSet {
        add(RenderFeature.TRIM)
        if (graph.videoLayers.size > 1) add(RenderFeature.CONCATENATE)
        if (graph.videoLayers.any { it.transform.cropLeft != 0f || it.transform.cropTop != 0f || it.transform.cropRight != 1f || it.transform.cropBottom != 1f }) add(RenderFeature.CROP)
        if (graph.videoLayers.any { it.transform.scaleX != 1f || it.transform.scaleY != 1f }) add(RenderFeature.SCALE)
        if (graph.videoLayers.any { it.transform.rotationDegrees != 0f }) add(RenderFeature.ROTATE)
        if (graph.videoLayers.any { it.transform.positionX != 0.5f || it.transform.positionY != 0.5f }) add(RenderFeature.TRANSLATE)
        if (graph.videoLayers.any { it.opacity != 1f || it.effects.isNotEmpty() }) add(RenderFeature.CUSTOM_EFFECT)
        if (graph.videoLayers.any { it.speed.constantSpeedOrNull != 1.0 }) add(RenderFeature.CONSTANT_SPEED)
        if (graph.videoLayers.any { !it.speed.isConstant }) add(RenderFeature.VARIABLE_SPEED)
        if (graph.audioLayers.size > 1) add(RenderFeature.AUDIO_MIX)
        if (graph.audioLayers.any { it.fadeIn.value > 0 || it.fadeOut.value > 0 }) add(RenderFeature.AUDIO_FADE)
        if (graph.audioLayers.any { it.gainDb != 0f || it.muted || it.gainAutomation.isNotEmpty() }) add(RenderFeature.AUDIO_GAIN_AUTOMATION)
        if (graph.overlays.any { it is OverlayNode.Text }) add(RenderFeature.TEXT_OVERLAY)
        if (graph.overlays.any { it is OverlayNode.Caption }) add(RenderFeature.CAPTION_BURN_IN)
        if (graph.overlays.any { it is OverlayNode.Image }) add(RenderFeature.IMAGE_OVERLAY)
        if (graph.overlays.any { it is GraphicOverlayNode }) add(RenderFeature.CUSTOM_EFFECT)
        if (graph.transitions.any { it.type == TransitionType.CROSSFADE || it.type == TransitionType.DIP_TO_COLOR }) add(RenderFeature.CROSSFADE)
        if (graph.canvas.colorMode == ProjectColorMode.HDR_KEEP) add(RenderFeature.KEEP_HDR)
        if (graph.canvas.colorMode == ProjectColorMode.HDR_TO_SDR) add(RenderFeature.TONE_MAP_HDR)
    }
}
