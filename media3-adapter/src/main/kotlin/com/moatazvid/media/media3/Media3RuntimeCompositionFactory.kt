package com.moatazvid.media.media3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.TimestampWrapper
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.moatazvid.media.ShapePrimitive
import com.moatazvid.media.TransformNode
import com.moatazvid.media.VideoEffectNode

/** Android text styling resolved independently from Compose and from Media3 classes in the project model. */
data class AndroidCaptionRenderStyle(
    val textColor: Int = android.graphics.Color.WHITE,
    val backgroundColor: Int? = null,
    val textSizePx: Int = 100,
    val bold: Boolean = true,
    val italic: Boolean = false,
)

fun interface AndroidCaptionStyleResolver {
    fun resolve(styleId: String): AndroidCaptionRenderStyle
}

/**
 * Converts the stable Media3CompositionSpec into the actual Media3 Composition used by both
 * CompositionPlayer and Transformer. CUT-only timelines are represented with sequence gaps;
 * transitions remain capability-gated until a verified transition renderer/fallback is installed.
 */
@OptIn(UnstableApi::class)
class Media3RuntimeCompositionFactory(
    private val context: Context,
    private val styleResolver: AndroidCaptionStyleResolver = AndroidCaptionStyleResolver { AndroidCaptionRenderStyle() },
) {
    fun build(spec: Media3CompositionSpec): Composition {
        require(spec.sequences.isNotEmpty()) { "Composition requires at least one media sequence" }
        require(spec.transitions.all { it.type == com.moatazvid.media.TransitionType.CUT }) {
            "Non-CUT transitions require a verified transition renderer/fallback"
        }

        val sequences = spec.sequences.map(::buildSequence)
        val compositionVideoEffects = buildList<Effect> {
            add(Presentation.createForWidthAndHeight(spec.canvas.width, spec.canvas.height, Presentation.LAYOUT_SCALE_TO_FIT))
            spec.overlays.sortedBy { it.startUs }.forEach { overlay ->
                val timed = buildTimedOverlay(overlay)
                if (timed != null) add(timed)
            }
        }
        val builder = Composition.Builder(sequences)
            .setHdrMode(
                when (spec.hdrMode) {
                    Media3HdrMode.KEEP_HDR -> Composition.HDR_MODE_KEEP_HDR
                    Media3HdrMode.TONE_MAP_TO_SDR -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                    Media3HdrMode.FORCE_SDR -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                }
            )
        if (compositionVideoEffects.isNotEmpty()) {
            builder.setEffects(Effects(emptyList(), compositionVideoEffects))
        }
        return builder.build()
    }

    private fun buildSequence(sequence: Media3SequenceSpec): EditedMediaItemSequence {
        val trackTypes = when (sequence.role) {
            SequenceRole.PRIMARY_VIDEO, SequenceRole.OVERLAY_VIDEO -> setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
            SequenceRole.DIALOGUE_AUDIO, SequenceRole.MUSIC_AUDIO, SequenceRole.OTHER_AUDIO -> setOf(C.TRACK_TYPE_AUDIO)
        }
        val builder = EditedMediaItemSequence.Builder(trackTypes)
        var cursorUs = 0L
        sequence.items.sortedBy { it.timelineStartUs }.forEach { item ->
            require(item.timelineStartUs >= cursorUs) { "Overlapping items must be represented on separate tracks/sequences" }
            val gapUs = item.timelineStartUs - cursorUs
            if (gapUs > 0) builder.addGap(gapUs)
            builder.addItem(buildItem(item))
            cursorUs = item.timelineStartUs + item.timelineDurationUs
        }
        return builder.build()
    }

    private fun buildItem(spec: Media3EditedItemSpec): EditedMediaItem {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(spec.sourceStartUs)
            .setEndPositionUs(spec.sourceEndUs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(spec.resolverToken))
            .setClippingConfiguration(clipping)
            .build()
        val videoEffects = buildItemVideoEffects(spec)
        val builder = EditedMediaItem.Builder(mediaItem)
            .setDurationUs(spec.sourceDurationUs)
            .setRemoveAudio(spec.removeAudio)
            .setRemoveVideo(spec.removeVideo)
        if (videoEffects.isNotEmpty()) builder.setEffects(Effects(emptyList(), videoEffects))
        if (spec.speed != 1.0) builder.setSpeed(ConstantSpeedProvider(spec.speed.toFloat()))
        return builder.build()
    }

    private fun buildItemVideoEffects(spec: Media3EditedItemSpec): List<Effect> = buildList {
        spec.transform?.let { transform -> addAll(transformEffects(transform)) }
        spec.effects.forEach { node ->
            when (node) {
                is VideoEffectNode.ColorAdjustment -> {
                    if (node.brightness != 0f) add(Brightness(node.brightness.coerceIn(-1f, 1f)))
                    if (node.contrast != 1f) add(Contrast((node.contrast - 1f).coerceIn(-1f, 1f)))
                    if (node.saturation != 1f) add(HslAdjustment.Builder().adjustSaturation(((node.saturation - 1f) * 100f).coerceIn(-100f, 100f)).build())
                }
                is VideoEffectNode.CustomRegistered -> add(customEffect(node))
            }
        }
    }

    private fun transformEffects(transform: TransformNode): List<Effect> = buildList {
        if (transform.cropLeft != 0f || transform.cropTop != 0f || transform.cropRight != 1f || transform.cropBottom != 1f) {
            val left = -1f + 2f * transform.cropLeft
            val right = -1f + 2f * transform.cropRight
            val bottom = -1f + 2f * (1f - transform.cropBottom)
            val top = 1f - 2f * transform.cropTop
            add(Crop(left, right, bottom, top))
        }
        if (transform.scaleX != 1f || transform.scaleY != 1f || transform.rotationDegrees != 0f) {
            add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(transform.scaleX, transform.scaleY)
                    .setRotationDegrees(transform.rotationDegrees)
                    .build()
            )
        }
        require(transform.positionX == 0.5f && transform.positionY == 0.5f) {
            "Video translation is not bound in the Media3 V1 renderer"
        }
    }

    private fun customEffect(node: VideoEffectNode.CustomRegistered): Effect = when (node.registryKey) {
        "moataz.blur" -> GaussianBlur((node.parameters["radius"] ?: 1.0).toFloat().coerceAtLeast(0.1f))
        "moataz.grayscale" -> RgbFilter.createGrayscaleFilter()
        "moataz.opacity" -> AlphaScale((node.parameters["amount"] ?: 1.0).toFloat().coerceAtLeast(0f))
        else -> error("Unbound Media3 effect: ${node.registryKey}")
    }

    private fun buildTimedOverlay(spec: Media3OverlaySpec): Effect? {
        val texture: TextureOverlay = when (spec.kind) {
            Media3OverlayKind.TEXT, Media3OverlayKind.CAPTION -> buildTextOverlay(spec)
            Media3OverlayKind.IMAGE -> {
                val token = requireNotNull(spec.assetResolverToken) { "Missing image resolver token for ${spec.id}" }
                BitmapOverlay.createStaticBitmapOverlay(context, Uri.parse(token), overlaySettings(spec.transform, spec.opacity))
            }
            Media3OverlayKind.GRAPHIC -> buildGraphicOverlay(spec)
        }
        return TimestampWrapper(OverlayEffect(listOf(texture)), spec.startUs, spec.endUs)
    }

    private fun buildTextOverlay(spec: Media3OverlaySpec): TextOverlay {
        val text = requireNotNull(spec.text)
        val style = styleResolver.resolve(spec.styleId.orEmpty())
        val spannable = SpannableString(text)
        if (text.isNotEmpty()) {
            spannable.setSpan(ForegroundColorSpan(style.textColor), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            style.backgroundColor?.let { spannable.setSpan(BackgroundColorSpan(it), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            spannable.setSpan(AbsoluteSizeSpan(style.textSizePx), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val typefaceStyle = when {
                style.bold && style.italic -> Typeface.BOLD_ITALIC
                style.bold -> Typeface.BOLD
                style.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            spannable.setSpan(StyleSpan(typefaceStyle), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Android's text layout performs Arabic shaping and Unicode bidi on the unmodified text,
        // preserving embedded Latin model IDs, URLs and timecodes instead of manually reversing them.
        return TextOverlay.createStaticTextOverlay(spannable, overlaySettings(spec.transform, spec.opacity))
    }

    private fun buildGraphicOverlay(spec: Media3OverlaySpec): BitmapOverlay {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.fillArgb?.toInt() ?: android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        when (spec.graphicPrimitive ?: ShapePrimitive.RECTANGLE) {
            ShapePrimitive.RECTANGLE -> canvas.drawRect(48f, 96f, 464f, 416f, paint)
            ShapePrimitive.ROUNDED_RECTANGLE -> canvas.drawRoundRect(48f, 96f, 464f, 416f, 48f, 48f, paint)
            ShapePrimitive.CIRCLE -> canvas.drawCircle(256f, 256f, 190f, paint)
            ShapePrimitive.LINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (spec.strokeWidth ?: 0.02f).coerceAtLeast(0.005f) * 512f
                canvas.drawLine(64f, 256f, 448f, 256f, paint)
            }
            ShapePrimitive.ARROW -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (spec.strokeWidth ?: 0.02f).coerceAtLeast(0.005f) * 512f
                canvas.drawLine(64f, 256f, 420f, 256f, paint)
                canvas.drawLine(420f, 256f, 340f, 190f, paint)
                canvas.drawLine(420f, 256f, 340f, 322f, paint)
            }
        }
        spec.strokeArgb?.let { stroke ->
            paint.color = stroke.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (spec.strokeWidth ?: 0.01f).coerceAtLeast(0.003f) * 512f
            when (spec.graphicPrimitive ?: ShapePrimitive.RECTANGLE) {
                ShapePrimitive.RECTANGLE -> canvas.drawRect(48f, 96f, 464f, 416f, paint)
                ShapePrimitive.ROUNDED_RECTANGLE -> canvas.drawRoundRect(48f, 96f, 464f, 416f, 48f, 48f, paint)
                ShapePrimitive.CIRCLE -> canvas.drawCircle(256f, 256f, 190f, paint)
                ShapePrimitive.LINE, ShapePrimitive.ARROW -> Unit
            }
        }
        return BitmapOverlay.createStaticBitmapOverlay(bitmap, overlaySettings(spec.transform, spec.opacity))
    }

    private fun overlaySettings(transform: TransformNode, opacity: Float): StaticOverlaySettings =
        StaticOverlaySettings.Builder()
            .setAlphaScale(opacity.coerceAtLeast(0f))
            .setBackgroundFrameAnchor(
                (transform.positionX * 2f - 1f).coerceIn(-1f, 1f),
                (1f - transform.positionY * 2f).coerceIn(-1f, 1f),
            )
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(transform.scaleX, transform.scaleY)
            .setRotationDegrees(transform.rotationDegrees)
            .build()

    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        init { require(speed > 0f) }
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }
}
