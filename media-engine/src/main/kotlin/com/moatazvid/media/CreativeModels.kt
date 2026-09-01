package com.moatazvid.media

import com.moatazvid.core.*

@JvmInline value class CreativeElementId(val value: String)
@JvmInline value class EffectId(val value: String)
@JvmInline value class TransitionId(val value: String)
@JvmInline value class FontAssetId(val value: String)

enum class KeyframeInterpolation { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, HOLD }
data class Keyframe<T>(val timeMs: Long, val value: T, val interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR) {
    init { require(timeMs >= 0) }
}

data class CreativeTransform(
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val opacity: Float = 1f,
    val positionKeyframes: List<Keyframe<Pair<Float, Float>>> = emptyList(),
    val scaleKeyframes: List<Keyframe<Pair<Float, Float>>> = emptyList(),
    val rotationKeyframes: List<Keyframe<Float>> = emptyList(),
    val opacityKeyframes: List<Keyframe<Float>> = emptyList(),
) {
    init {
        require(positionX.isFinite() && positionY.isFinite())
        require(scaleX > 0f && scaleY > 0f)
        require(anchorX in 0f..1f && anchorY in 0f..1f)
        require(opacity in 0f..1f)
    }

    fun toRenderTransform(): TransformNode = TransformNode(positionX, positionY, scaleX, scaleY, rotationDegrees)
}

enum class CaptionTextAlignment { START, CENTER, END }
enum class CaptionHighlightMode { NONE, ACTIVE_WORD, CURRENT_PHRASE, WORD_PROGRESS }
enum class CreativeAnimation { NONE, FADE, SCALE_IN, POP, SLIDE_UP, WORD_HIGHLIGHT }
enum class TextAnchor { TOP_START, TOP_CENTER, TOP_END, CENTER, BOTTOM_START, BOTTOM_CENTER, BOTTOM_END, CUSTOM }

data class CaptionStyle(
    val id: String,
    val name: String,
    val fontFamilyRef: String? = null,
    val relativeFontSize: Float = 0.052f,
    val weight: Int = 700,
    val italic: Boolean = false,
    val textArgb: Long = 0xFFFFFFFF,
    val strokeArgb: Long = 0xFF000000,
    val strokeWidth: Float = 0.012f,
    val shadowRadius: Float = 0.015f,
    val backgroundArgb: Long? = null,
    val backgroundOpacity: Float = 0f,
    val cornerRadius: Float = 0.02f,
    val horizontalPadding: Float = 0.025f,
    val verticalPadding: Float = 0.012f,
    val alignment: CaptionTextAlignment = CaptionTextAlignment.CENTER,
    val maxWidth: Float = 0.84f,
    val lineSpacing: Float = 1f,
    val safeMarginX: Float = 0.06f,
    val safeMarginY: Float = 0.08f,
    val anchor: TextAnchor = TextAnchor.BOTTOM_CENTER,
    val highlightMode: CaptionHighlightMode = CaptionHighlightMode.NONE,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(relativeFontSize in 0.01f..0.2f)
        require(weight in 100..900)
        require(backgroundOpacity in 0f..1f)
        require(maxWidth in 0.2f..1f)
    }
}

data class FontAsset(
    val id: FontAssetId,
    val familyName: String,
    val assetId: AssetId,
    val licenseId: String,
    val attribution: String? = null,
)

interface FontRegistry {
    suspend fun resolve(reference: String): FontAsset?
    suspend fun listAvailable(): List<FontAsset>
}

enum class SafeAreaKind { GENERIC, SOCIAL_VERTICAL, CUSTOM }
data class SafeAreaInsets(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it in 0f..0.45f }) }
}
data class PlatformSafeAreaPreset(val id: String, val kind: SafeAreaKind, val insets: SafeAreaInsets)

sealed interface CreativeElement {
    val id: CreativeElementId
    val trackId: TrackId
    val range: TimeRangeUs
    val transform: CreativeTransform
    val zIndex: Int
    val enabled: Boolean
}

data class CaptionCreativeElement(
    override val id: CreativeElementId,
    override val trackId: TrackId,
    override val range: TimeRangeUs,
    val text: String,
    val styleId: String,
    val wordIds: List<String>,
    val animation: CreativeAnimation = CreativeAnimation.NONE,
    val rightToLeft: Boolean = false,
    override val transform: CreativeTransform = CreativeTransform(positionY = 0.84f),
    override val zIndex: Int = 100,
    override val enabled: Boolean = true,
) : CreativeElement

data class TextElement(
    override val id: CreativeElementId,
    override val trackId: TrackId,
    override val range: TimeRangeUs,
    val text: String,
    val styleId: String,
    val animationIn: CreativeAnimation = CreativeAnimation.NONE,
    val animationOut: CreativeAnimation = CreativeAnimation.NONE,
    val anchor: TextAnchor = TextAnchor.CENTER,
    override val transform: CreativeTransform = CreativeTransform(),
    override val zIndex: Int = 80,
    override val enabled: Boolean = true,
) : CreativeElement {
    init { require(text.isNotBlank()) }
}

enum class OverlayFitMode { FIT, FILL, STRETCH }
data class ImageOverlayElement(
    override val id: CreativeElementId,
    override val trackId: TrackId,
    override val range: TimeRangeUs,
    val assetId: AssetId,
    val fitMode: OverlayFitMode = OverlayFitMode.FIT,
    val cornerRadius: Float = 0f,
    val shadowRadius: Float = 0f,
    override val transform: CreativeTransform = CreativeTransform(scaleX = 0.25f, scaleY = 0.25f),
    override val zIndex: Int = 60,
    override val enabled: Boolean = true,
) : CreativeElement

data class VideoOverlayElement(
    override val id: CreativeElementId,
    override val trackId: TrackId,
    override val range: TimeRangeUs,
    val sourceId: SourceId,
    val sourceRange: TimeRangeUs,
    val crop: TransformNode = TransformNode(),
    override val transform: CreativeTransform = CreativeTransform(scaleX = 0.33f, scaleY = 0.33f),
    override val zIndex: Int = 50,
    override val enabled: Boolean = false,
) : CreativeElement

enum class ShapePrimitive { RECTANGLE, ROUNDED_RECTANGLE, LINE, ARROW, CIRCLE }
data class ShapeElement(
    override val id: CreativeElementId,
    override val trackId: TrackId,
    override val range: TimeRangeUs,
    val primitive: ShapePrimitive,
    val fillArgb: Long,
    val strokeArgb: Long? = null,
    val strokeWidth: Float = 0f,
    override val transform: CreativeTransform = CreativeTransform(),
    override val zIndex: Int = 40,
    override val enabled: Boolean = true,
) : CreativeElement

enum class EffectType { BRIGHTNESS, CONTRAST, SATURATION, TEMPERATURE, TINT, EXPOSURE, BLUR, SHARPEN, VIGNETTE, GRAYSCALE, OPACITY, CROP_TRANSFORM, LUT }
enum class BackendRequirement { MEDIA3, GPU_EFFECT, FFMPEG, ANY }
enum class SupportLevel { SUPPORTED, UNSUPPORTED, UNKNOWN }

data class EffectParameter(val name: String, val value: Double, val minimum: Double, val maximum: Double, val keyframes: List<Keyframe<Double>> = emptyList()) {
    init { require(minimum <= value && value <= maximum) }
}

data class EffectDescriptor(
    val type: EffectType,
    val backendRequirement: BackendRequirement,
    val previewSupport: SupportLevel,
    val exportSupport: SupportLevel,
    val parameterRanges: Map<String, ClosedFloatingPointRange<Double>>,
)

data class EffectInstance(
    val id: EffectId,
    val type: EffectType,
    val parameters: List<EffectParameter>,
    val range: TimeRangeUs? = null,
    val enabled: Boolean = true,
    val orderIndex: Int = 0,
) {
    init { require(orderIndex >= 0) }
}

data class ColorAdjustmentModel(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val exposure: Float = 0f,
    val enabled: Boolean = true,
) {
    init {
        require(brightness in -1f..1f)
        require(contrast in 0f..2f)
        require(saturation in 0f..2f)
        require(temperature in -1f..1f)
        require(tint in -1f..1f)
        require(exposure in -2f..2f)
    }
}

enum class CreativeTransitionType { CUT, FADE, CROSS_DISSOLVE, DIP_TO_COLOR, SLIDE, PUSH }
data class CreativeTransition(
    val id: TransitionId,
    val type: CreativeTransitionType,
    val durationMs: Long,
    val fromClipId: ClipId,
    val toClipId: ClipId,
    val parameters: Map<String, Double> = emptyMap(),
) {
    init { require(durationMs >= 0) }
}

object TransitionValidator {
    fun validate(transition: CreativeTransition, from: TimelineItem, to: TimelineItem): List<String> {
        val errors = mutableListOf<String>()
        if (from.id != transition.fromClipId || to.id != transition.toClipId) errors += "TRANSITION_CLIP_MISMATCH"
        if (transition.type != CreativeTransitionType.CUT && transition.durationMs <= 0) errors += "TRANSITION_DURATION_REQUIRED"
        val maximumUs = minOf(from.timelineDuration.value, to.timelineDuration.value)
        if (transition.durationMs * 1_000 > maximumUs) errors += "TRANSITION_TOO_LONG"
        if (from.timelineDuration.value <= 0 || to.timelineDuration.value <= 0) errors += "INVALID_CLIP_DURATION"
        return errors
    }
}

enum class AudioLoopPolicy { NONE, LOOP_TO_PROJECT_END, LOOP_FIXED_COUNT }
data class CreativeAudioClip(
    val id: ClipId,
    val trackId: TrackId,
    val assetId: AssetId,
    val range: TimeRangeUs,
    val sourceRange: TimeRangeUs?,
    val gainDb: Float = 0f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val muted: Boolean = false,
    val loopPolicy: AudioLoopPolicy = AudioLoopPolicy.NONE,
) {
    init {
        require(gainDb in -60f..24f)
        require(fadeInMs >= 0 && fadeOutMs >= 0)
        require((fadeInMs + fadeOutMs) * 1_000 <= range.duration.value)
    }
}

enum class DuckingMode { OFF, AUTO_SPEECH_DUCK, MANUAL }
data class DuckingSettings(
    val mode: DuckingMode = DuckingMode.OFF,
    val reductionDb: Float = -12f,
    val attackMs: Long = 120,
    val releaseMs: Long = 350,
    val minimumSpeechDurationMs: Long = 180,
) {
    init {
        require(reductionDb in -30f..0f)
        require(attackMs in 10..2_000)
        require(releaseMs in 20..5_000)
        require(minimumSpeechDurationMs in 50..5_000)
    }
}

data class GainEnvelopePoint(val timeMs: Long, val gainDb: Float)
class DuckingProcessor {
    fun buildEnvelope(speechRanges: List<TimeRangeUs>, projectDuration: DurationUs, settings: DuckingSettings): List<GainEnvelopePoint> {
        if (settings.mode == DuckingMode.OFF) return listOf(GainEnvelopePoint(0, 0f), GainEnvelopePoint(projectDuration.value / 1_000, 0f))
        val ranges = speechRanges.filter { it.duration.value >= settings.minimumSpeechDurationMs * 1_000 }.sortedBy { it.start.value }
        if (ranges.isEmpty()) return listOf(GainEnvelopePoint(0, 0f), GainEnvelopePoint(projectDuration.value / 1_000, 0f))
        val points = mutableListOf(GainEnvelopePoint(0, 0f))
        ranges.forEach { range ->
            val startMs = range.start.value / 1_000
            val endMs = range.endExclusive.value / 1_000
            points += GainEnvelopePoint((startMs - settings.attackMs).coerceAtLeast(0), 0f)
            points += GainEnvelopePoint(startMs, settings.reductionDb)
            points += GainEnvelopePoint(endMs, settings.reductionDb)
            points += GainEnvelopePoint((endMs + settings.releaseMs).coerceAtMost(projectDuration.value / 1_000), 0f)
        }
        return points.distinctBy { it.timeMs to it.gainDb }.sortedBy { it.timeMs }
    }
}

interface LoudnessProcessor {
    suspend fun analyze(input: MediaInput): LoudnessMeasurement
    suspend fun normalize(input: MediaInput, targetLufs: Double, peakDbfs: Double): LoudnessResult
}
data class LoudnessMeasurement(val integratedLufs: Double?, val truePeakDbfs: Double?, val peakDbfs: Double)
data class LoudnessResult(val gainDb: Double, val limiterRequired: Boolean)

interface BeatDetector { suspend fun detect(input: MediaInput): List<Long> }

data class CreativeAsset(
    val assetId: AssetId,
    val type: String,
    val reference: String,
    val fingerprint: String,
    val source: String,
    val license: String? = null,
    val createdAtEpochMs: Long,
    val projectScoped: Boolean = true,
)

data class CreativeEditPolicy(
    val maxTransitionsPerMinute: Int = 12,
    val maxTextOverlaysPerMinute: Int = 10,
    val maxZoomsPerMinute: Int = 8,
    val maximumEffectIntensity: Double = 0.75,
    val preserveCaptionReadability: Boolean = true,
) {
    init {
        require(maxTransitionsPerMinute in 0..60)
        require(maxTextOverlaysPerMinute in 0..60)
        require(maxZoomsPerMinute in 0..60)
        require(maximumEffectIntensity in 0.0..1.0)
    }
}

data class ElementCompatibility(
    val elementId: String,
    val previewBackend: BackendKind?,
    val exportBackend: BackendKind?,
    val parity: SupportLevel,
    val reason: String? = null,
)
data class CompatibilityReport(val elements: List<ElementCompatibility>) {
    val fullParity: Boolean get() = elements.all { it.parity == SupportLevel.SUPPORTED }
}

object DefaultCreativeDescriptors {
    val effects: Map<EffectType, EffectDescriptor> = mapOf(
        EffectType.BRIGHTNESS to EffectDescriptor(EffectType.BRIGHTNESS, BackendRequirement.ANY, SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, mapOf("amount" to -1.0..1.0)),
        EffectType.CONTRAST to EffectDescriptor(EffectType.CONTRAST, BackendRequirement.ANY, SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, mapOf("amount" to 0.0..2.0)),
        EffectType.SATURATION to EffectDescriptor(EffectType.SATURATION, BackendRequirement.ANY, SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, mapOf("amount" to 0.0..2.0)),
        EffectType.BLUR to EffectDescriptor(EffectType.BLUR, BackendRequirement.GPU_EFFECT, SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, mapOf("radius" to 0.0..40.0)),
        EffectType.GRAYSCALE to EffectDescriptor(EffectType.GRAYSCALE, BackendRequirement.ANY, SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, emptyMap()),
        EffectType.OPACITY to EffectDescriptor(EffectType.OPACITY, BackendRequirement.ANY, SupportLevel.SUPPORTED, SupportLevel.SUPPORTED, mapOf("amount" to 0.0..1.0)),
    )
}
