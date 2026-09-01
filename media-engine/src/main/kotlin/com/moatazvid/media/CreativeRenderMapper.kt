package com.moatazvid.media

import com.moatazvid.core.*

/** RenderGraph boundary for creative edits. UI and AI never depend on Media3 effect classes. */
class CreativeRenderMapper {
    fun apply(
        base: RenderGraph,
        elements: List<CreativeElement>,
        clipEffects: Map<ClipId, List<EffectInstance>> = emptyMap(),
        transitions: List<CreativeTransition> = emptyList(),
        audio: List<CreativeAudioClip> = emptyList(),
    ): CreativeRenderResult {
        val compatibility = mutableListOf<ElementCompatibility>()
        val overlays = base.overlays.toMutableList()
        val videos = base.videoLayers.map { layer ->
            val mappedEffects = clipEffects[layer.id].orEmpty().sortedBy { it.orderIndex }.mapNotNull(::toVideoEffectNode)
            layer.copy(effects = layer.effects + mappedEffects)
        }
        elements.sortedBy { it.zIndex }.forEach { element ->
            when (element) {
                is CaptionCreativeElement -> {
                    overlays += OverlayNode.Caption(
                        id = element.id.value,
                        range = element.range,
                        transform = element.transform.toRenderTransform(),
                        opacity = element.transform.opacity,
                        text = element.text,
                        styleId = element.styleId,
                    )
                    compatibility += ElementCompatibility(element.id.value, BackendKind.MEDIA3, BackendKind.MEDIA3, SupportLevel.SUPPORTED)
                }
                is TextElement -> {
                    overlays += OverlayNode.Text(
                        id = element.id.value,
                        range = element.range,
                        transform = element.transform.toRenderTransform(),
                        opacity = element.transform.opacity,
                        text = element.text,
                        styleId = element.styleId,
                    )
                    compatibility += ElementCompatibility(element.id.value, BackendKind.MEDIA3, BackendKind.MEDIA3, SupportLevel.SUPPORTED)
                }
                is ImageOverlayElement -> {
                    overlays += OverlayNode.Image(
                        id = element.id.value,
                        range = element.range,
                        transform = element.transform.toRenderTransform(),
                        opacity = element.transform.opacity,
                        assetId = element.assetId,
                    )
                    compatibility += ElementCompatibility(element.id.value, BackendKind.MEDIA3, BackendKind.MEDIA3, SupportLevel.SUPPORTED)
                }
                is ShapeElement -> {
                    overlays += GraphicOverlayNode(
                        id = element.id.value,
                        range = element.range,
                        transform = element.transform.toRenderTransform(),
                        opacity = element.transform.opacity,
                        primitive = element.primitive,
                        fillArgb = element.fillArgb,
                        strokeArgb = element.strokeArgb,
                        strokeWidth = element.strokeWidth,
                    )
                    compatibility += ElementCompatibility(element.id.value, BackendKind.MEDIA3, BackendKind.MEDIA3, SupportLevel.SUPPORTED)
                }
                is VideoOverlayElement -> {
                    compatibility += ElementCompatibility(
                        element.id.value,
                        if (element.enabled) BackendKind.MEDIA3 else null,
                        if (element.enabled) BackendKind.MEDIA3 else null,
                        if (element.enabled) SupportLevel.UNKNOWN else SupportLevel.UNSUPPORTED,
                        if (element.enabled) "PiP model is enabled but device composition support must be resolved" else "PiP is feature-flagged off in V1",
                    )
                }
            }
        }
        val transitionNodes = base.transitions + transitions.map { transition ->
            TransitionNode(
                transition.id.value,
                transition.fromClipId,
                transition.toClipId,
                when (transition.type) {
                    CreativeTransitionType.CUT -> TransitionType.CUT
                    CreativeTransitionType.FADE, CreativeTransitionType.CROSS_DISSOLVE -> TransitionType.CROSSFADE
                    CreativeTransitionType.DIP_TO_COLOR -> TransitionType.DIP_TO_COLOR
                    CreativeTransitionType.SLIDE, CreativeTransitionType.PUSH -> TransitionType.CROSSFADE
                },
                DurationUs(transition.durationMs * 1_000),
            )
        }
        transitions.forEach {
            val support = if (it.type in setOf(CreativeTransitionType.CUT, CreativeTransitionType.FADE, CreativeTransitionType.CROSS_DISSOLVE)) SupportLevel.SUPPORTED else SupportLevel.UNKNOWN
            compatibility += ElementCompatibility(it.id.value, BackendKind.MEDIA3, BackendKind.MEDIA3, support, if (support == SupportLevel.UNKNOWN) "Requires fallback/capability resolution" else null)
        }
        val audioLayers = base.audioLayers + audio.map { clip ->
            AudioLayer(
                id = clip.id,
                trackId = clip.trackId,
                input = MediaInput.Asset(clip.assetId),
                sourceRange = clip.sourceRange,
                placement = TimelinePlacement(clip.range.start, clip.range.duration),
                gainDb = clip.gainDb,
                pan = 0f,
                muted = clip.muted,
                preservePitch = true,
                speed = SpeedCurve(listOf(SpeedSegmentNode(clip.sourceRange ?: TimeRangeUs(TimeUs.ZERO, TimeUs(clip.range.duration.value)), 1.0, 1.0, SpeedInterpolation.CONSTANT))),
                fadeIn = DurationUs(clip.fadeInMs * 1_000),
                fadeOut = DurationUs(clip.fadeOutMs * 1_000),
                role = AudioRole.MUSIC,
            )
        }
        return CreativeRenderResult(
            base.copy(videoLayers = videos, audioLayers = audioLayers, overlays = overlays.sortedBy { overlayZIndex(it, elements) }, transitions = transitionNodes),
            CompatibilityReport(compatibility),
        )
    }

    private fun toVideoEffectNode(instance: EffectInstance): VideoEffectNode? {
        if (!instance.enabled) return null
        val params = instance.parameters.associate { it.name to it.value }
        return when (instance.type) {
            EffectType.BRIGHTNESS -> VideoEffectNode.ColorAdjustment(params["amount"]?.toFloat() ?: 0f, 1f, 1f)
            EffectType.CONTRAST -> VideoEffectNode.ColorAdjustment(0f, params["amount"]?.toFloat() ?: 1f, 1f)
            EffectType.SATURATION -> VideoEffectNode.ColorAdjustment(0f, 1f, params["amount"]?.toFloat() ?: 1f)
            EffectType.BLUR, EffectType.GRAYSCALE, EffectType.OPACITY, EffectType.CROP_TRANSFORM ->
                VideoEffectNode.CustomRegistered("moataz.${instance.type.name.lowercase()}", 1, params)
            else -> VideoEffectNode.CustomRegistered("moataz.${instance.type.name.lowercase()}", 1, params)
        }
    }

    private fun overlayZIndex(node: OverlayNode, elements: List<CreativeElement>): Int = elements.firstOrNull { it.id.value == node.id }?.zIndex ?: 0
}

data class CreativeRenderResult(val graph: RenderGraph, val compatibility: CompatibilityReport)

data class GraphicOverlayNode(
    override val id: String,
    override val range: TimeRangeUs,
    override val transform: TransformNode,
    override val opacity: Float,
    val primitive: ShapePrimitive,
    val fillArgb: Long,
    val strokeArgb: Long?,
    val strokeWidth: Float,
) : OverlayNode
