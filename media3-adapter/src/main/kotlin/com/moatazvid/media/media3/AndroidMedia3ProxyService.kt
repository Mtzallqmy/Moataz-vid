package com.moatazvid.media.media3

import com.moatazvid.core.DurationUs
import com.moatazvid.core.ProjectColorMode
import com.moatazvid.core.Rational
import com.moatazvid.core.TimeUs
import com.moatazvid.media.AudioCodec
import com.moatazvid.media.ExportSettings
import com.moatazvid.media.FpsPolicy
import com.moatazvid.media.HdrPolicy
import com.moatazvid.media.MediaJobHandle
import com.moatazvid.media.MediaResult
import com.moatazvid.media.OutputCanvas
import com.moatazvid.media.QualityMode
import com.moatazvid.media.VideoCodec
import com.moatazvid.media.ProxyRequest
import kotlin.math.roundToInt

/** Generates lightweight H.264 proxies without changing source/timeline duration or edit timecodes. */
class AndroidMedia3ProxyService(
    private val resolver: Media3InputResolver,
    private val transformer: TransformerFacade,
) : Media3ProxyService {
    override suspend fun generate(request: ProxyRequest): MediaResult<MediaJobHandle> {
        val probe = request.probe
        val codedWidth = probe.codedWidth ?: 0
        val codedHeight = probe.codedHeight ?: 0
        require(codedWidth > 0 && codedHeight > 0) { "Proxy generation requires video dimensions" }
        require(probe.duration.value > 0) { "Proxy generation requires media duration" }

        val rotated = ((probe.rotationDegrees % 180) + 180) % 180 != 0
        val displayWidth = if (rotated) codedHeight else codedWidth
        val displayHeight = if (rotated) codedWidth else codedHeight
        val scale = minOf(1.0, request.preset.maxLongEdge.toDouble() / maxOf(displayWidth, displayHeight).toDouble())
        val outWidth = even((displayWidth * scale).roundToInt().coerceAtLeast(2))
        val outHeight = even((displayHeight * scale).roundToInt().coerceAtLeast(2))
        val sourceFps = probe.frameRate ?: Rational.FPS_30
        val proxyFps = when {
            sourceFps.asDouble() > 30.1 -> Rational.FPS_30
            else -> sourceFps
        }
        val token = resolver.tokenFor(request.source, preferProxy = false)
        val item = Media3EditedItemSpec(
            stableId = request.source.stableId,
            resolverToken = token,
            sourceDurationUs = probe.duration.value,
            sourceStartUs = 0,
            sourceEndUs = probe.duration.value,
            timelineStartUs = 0,
            timelineDurationUs = probe.duration.value,
            removeAudio = false,
            removeVideo = false,
            speed = 1.0,
            gainDb = 0f,
            transform = null,
            effects = emptyList(),
        )
        val composition = Media3CompositionSpec(
            sequences = listOf(Media3SequenceSpec(com.moatazvid.core.TrackId("proxy-primary"), SequenceRole.PRIMARY_VIDEO, listOf(item))),
            canvas = OutputCanvas(outWidth, outHeight, proxyFps, ProjectColorMode.SDR, 0xFF000000),
            hdrMode = if (probe.hdr) Media3HdrMode.TONE_MAP_TO_SDR else Media3HdrMode.FORCE_SDR,
            audioMixingEnabled = false,
        )
        val settings = ExportSettings(
            width = outWidth,
            height = outHeight,
            frameRate = proxyFps,
            fpsPolicy = FpsPolicy.EXPLICIT,
            qualityMode = QualityMode.BITRATE,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.AAC,
            videoBitrate = request.preset.videoBitrate,
            audioBitrate = 96_000,
            hdrPolicy = if (probe.hdr) HdrPolicy.TONE_MAP_TO_SDR else HdrPolicy.SDR,
        )
        return transformer.export(
            jobId = "proxy-${request.source.sourceId.value}-${System.nanoTime()}",
            composition = composition,
            outputUri = request.outputRef,
            settings = settings,
        )
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else (value - 1).coerceAtLeast(2)
}
