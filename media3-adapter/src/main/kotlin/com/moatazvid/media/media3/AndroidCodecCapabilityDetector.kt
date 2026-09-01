package com.moatazvid.media.media3

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.moatazvid.media.CodecCapability
import com.moatazvid.media.ExportSettings
import com.moatazvid.media.HdrPolicy
import com.moatazvid.media.MediaProbe
import com.moatazvid.media.VideoCodec

/** Runtime codec gate based on actual codecs exposed by the device. */
class AndroidCodecCapabilityDetector : CodecCapabilityDetector {
    override suspend fun detect(): List<CodecCapability> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.flatMap { info ->
        info.supportedTypes.mapNotNull { mime ->
            if (!mime.startsWith("video/", ignoreCase = true)) return@mapNotNull null
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
            val video = caps.videoCapabilities
            CodecCapability(
                mime = mime,
                decoder = !info.isEncoder,
                encoder = info.isEncoder,
                maxWidth = video?.supportedWidths?.upper,
                maxHeight = video?.supportedHeights?.upper,
                maxFps = video?.supportedFrameRates?.upper?.toDouble(),
                hdr = supportsHdr(caps),
            )
        }
    }.distinct()

    override suspend fun canEncode(settings: ExportSettings): Boolean {
        // The current Transformer binding deliberately exposes only AVC/HEVC + AAC.
        if (settings.videoCodec == VideoCodec.AV1) return false
        val mime = settings.videoCodec.mime
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            if (!info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@any false
            val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@any false
            val video = capabilities.videoCapabilities ?: return@any false
            val sizeAndRate = runCatching { video.areSizeAndRateSupported(settings.width, settings.height, settings.frameRate.asDouble()) }.getOrDefault(false)
            sizeAndRate && (settings.hdrPolicy != HdrPolicy.KEEP_HDR || supportsHdr(capabilities))
        }
    }

    override suspend fun canDecode(probe: MediaProbe): Boolean {
        val mime = probe.videoMime ?: return true
        return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            if (info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@any false
            val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@any false
            val video = capabilities.videoCapabilities ?: return@any true
            val width = probe.codedWidth
            val height = probe.codedHeight
            val fps = probe.frameRate?.asDouble()
            when {
                width == null || height == null -> true
                fps == null -> runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)
                else -> runCatching { video.areSizeAndRateSupported(width, height, fps) }.getOrDefault(false)
            } && (!probe.hdr || supportsHdr(capabilities))
        }
    }

    private fun supportsHdr(capabilities: MediaCodecInfo.CodecCapabilities): Boolean {
        // HDR10/HLG decoder/encoder support is profile-dependent. We stay conservative rather than
        // infer HDR from API level or the device model.
        return capabilities.profileLevels.any { level ->
            level.profile in HDR_PROFILES
        }
    }

    private val VideoCodec.mime: String
        get() = when (this) {
            VideoCodec.H264 -> "video/avc"
            VideoCodec.HEVC -> "video/hevc"
            VideoCodec.AV1 -> "video/av01"
        }

    companion object {
        private val HDR_PROFILES: Set<Int> = buildSet {
            // Guard constants by platform availability. HEVC Main10 is the common Android HDR path.
            add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
                add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            }
        }
    }
}
