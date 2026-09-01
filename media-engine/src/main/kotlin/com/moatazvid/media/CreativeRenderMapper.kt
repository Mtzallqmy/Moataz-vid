package com.moatazvid.media

import com.moatazvid.core.*

/** RenderGraph boundary for creative edits. UI and AI never depend on Media3 effect classes. */
class CreativeRenderMapper(
    private val boundFeatures: Set<RenderFeature> = emptySet(),
) {
    fun apply(
        base: RenderGraph,
        elements: List<CreativeElement>,
        clipEffects: Map<ClipId, List<EffectInstance>> = emptyMap(),
        transitions: List<CreativeTransition> = emptyList(),
        audio: List<CreativeAudioClip> = emptyList(),
        gainEnvelopesByTrack: Map<TrackId, List<GainEnvelopePoint>> = emptyMap(),
    ): CreativeRenderResult {
        val compatibility = mutableListOf<ElementCompatibility>()
        val overlays = base.overlays.toMutableList()
        val videos = base.videoLayers.map { layer ->
            val mappedEffects = clipEffects[layer.id].orEmpty().sortedBy { it.orderIndex }.mapNotNull(::toVideoEffectNode)
            clipEffects[layer.id].orEmpty().forEach { effect ->
                compatibility += featureCompatibility(effect.id.value, RenderFeature.CUSTOM_EFFECT, "Effect renderer is not bound")
            }
            layer.copy(effects = layer.effects + mappedEffects)
        }
        elements.sortedBy { it.zIndex }.forEach { element ->
            when (element) {
                is CaptionCreativeElement -> {
                    overlays += OverlayNode.Caption(element.id.value, element.range, element.transform.toRenderTransform(), element.transform.opacity, element.text, element.styleId)
                    compatibility += featureCompatibility(element.id.value, RenderFeature.CAPTION_BURN_IN, "Caption renderer is not bound")
                }
                is TextElement -> {
                    overlays += OverlayNode.Text(element.id.value, element.range, element.transform.toRenderTransform(), element.transform.opacity, element.text, element.styleId)
                    compatibility += featureCompatibility(element.id.value, RenderFeature.TEXT_OVERLAY, "Text overlay renderer is not bound")
                }
                is ImageOverlayElement -> {
                    overlays += OverlayNode.Image(element.id.value, element.range, element.transform.toRenderTransform(), element.transform.opacity, element.assetId)
                    compatibility += featureCompatibility(element.id.value, RenderFeature.IMAGE_OVERLAY, "Image overlay renderer is not bound")
                }
                is ShapeElement -> {
                    overlays += GraphicOverlayNode(element.id.value, element.range, element.transform.toRenderTransform(), element.transform.opacity, element.primitive, element.fillArgb, element.strokeArgb, element.strokeWidth)
                    compatibility += featureCompatibility(element.id.value, RenderFeature.CUSTOM_EFFECT, "Graphic overlay renderer is not bound")
                }
                is VideoOverlayElement -> compatibility += ElementCompatibility(
                    element.id.value,
                    if (element.enabled) BackendKind.MEDIA3 else null,
                    if (element.enabled) BackendKind.MEDIA3 else null,
                    if (element.enabled) SupportLevel.UNKNOWN else SupportLevel.UNSUPPORTED,
                    if (element.enabled) "PiP model is enabled but device composition support must be resolved" else "PiP is feature-flagged off in V1",
                )
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
            compatibility += if (it.type == CreativeTransitionType.CUT) {
                ElementCompatibility(it.id.value, BackendKind.MEDIA3, BackendKind.MEDIA3, SupportLevel.SUPPORTED)
            } else featureCompatibility(it.id.value, RenderFeature.CROSSFADE, "Transition renderer/fallback is not bound")
        }

        fun envelopeFor(trackId: TrackId, placement: TimelinePlacement): List<AudioGainPoint> = gainEnvelopesByTrack[trackId].orEmpty().mapNotNull { point ->
            val absoluteUs = point.timeMs * 1_000L
            val relativeUs = absoluteUs - placement.start.value
            if (relativeUs in 0..placement.duration.value) AudioGainPoint(relativeUs, point.gainDb) else null
        }.sortedBy { it.timeUs }

        val existingAudio = base.audioLayers.map { layer ->
            val added = envelopeFor(layer.trackId, layer.placement)
            if (added.isEmpty()) layer else layer.copy(gainAutomation = mergeAutomation(layer.gainAutomation, added))
        }
        val addedAudio = audio.map { clip ->
            val speedRange = clip.sourceRange ?: TimeRangeUs(TimeUs(0), TimeUs(clip.range.duration.value))
            val placement = TimelinePlacement(clip.range.start, clip.range.duration)
            AudioLayer(
                id = clip.id,
                trackId = clip.trackId,
                input = MediaInput.Asset(clip.assetId),
                sourceRange = clip.sourceRange,
                placement = placement,
                gainDb = clip.gainDb,
                pan = 0f,
                muted = clip.muted,
                preservePitch = true,
                speed = SpeedCurve(listOf(SpeedSegmentNode(speedRange, 1.0, 1.0, SpeedInterpolation.CONSTANT))),
                fadeIn = DurationUs(clip.fadeInMs * 1_000),
                fadeOut = DurationUs(clip.fadeOutMs * 1_000),
                role = AudioRole.MUSIC,
                gainAutomation = envelopeFor(clip.trackId, placement),
            )
        }
        return CreativeRenderResult(
            base.copy(videoLayers = videos, audioLayers = existingAudio + addedAudio, overlays = overlays.sortedBy { overlayZIndex(it, elements) }, transitions = transitionNodes),
            CompatibilityReport(compatibility),
        )
    }

    private fun mergeAutomation(first: List<AudioGainPoint>, second: List<AudioGainPoint>): List<AudioGainPoint> =
        (first + second).groupBy { it.timeUs }.map { (time, points) -> AudioGainPoint(time, points.sumOf { it.gainDb.toDouble() }.toFloat().coerceIn(-60f, 24f)) }.sortedBy { it.timeUs }

    private fun featureCompatibility(id: String, feature: RenderFeature, missingReason: String): ElementCompatibility {
        val supported = feature in boundFeatures
        return ElementCompatibility(id, if (supported) BackendKind.MEDIA3 else null, if (supported) BackendKind.MEDIA3 else null, if (supported) SupportLevel.SUPPORTED else SupportLevel.UNKNOWN, if (supported) null else missingReason)
    }

    private fun toVideoEffectNode(instance: EffectInstance): VideoEffectNode? {
        if (!instance.enabled) return null
        val params = instance.parameters.associate { it.name to it.value }
        return when (instance.type) {
            EffectType.BRIGHTNESS -> VideoEffectNode.ColorAdjustment(params["amount"]?.toFloat() ?: 0f, 1f, 1f)
            EffectType.CONTRAST -> VideoEffectNode.ColorAdjustment(0f, params["amount"]?.toFloat() ?: 1f, 1f)
            EffectType.SATURATION -> VideoEffectNode.ColorAdjustment(0f, 1f, params["amount"]?.toFloat() ?: 1f)
            EffectType.BLUR, EffectType.GRAYSCALE, EffectType.OPACITY, EffectType.CROP_TRANSFORM -> VideoEffectNode.CustomRegistered("moataz.${instance.type.name.lowercase()}", 1, params)
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
