package com.moatazvid.media

import com.moatazvid.core.*

enum class VideoCodec { H264, HEVC, AV1 }
enum class AudioCodec { AAC, OPUS }
enum class ContainerFormat { MP4, WEBM }
enum class QualityMode { BITRATE, QUALITY }
enum class FpsPolicy { PROJECT, PRESERVE_SINGLE_SOURCE, EXPLICIT }
enum class HdrPolicy { SDR, KEEP_HDR, TONE_MAP_TO_SDR }

data class ExportSettings(
    val container: ContainerFormat = ContainerFormat.MP4,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val width: Int,
    val height: Int,
    val frameRate: Rational,
    val fpsPolicy: FpsPolicy = FpsPolicy.PROJECT,
    val qualityMode: QualityMode = QualityMode.BITRATE,
    val videoBitrate: Long = 12_000_000,
    val audioBitrate: Int = 192_000,
    val audioSampleRateHz: Int = 48_000,
    val hdrPolicy: HdrPolicy = HdrPolicy.SDR,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(width > 0 && height > 0)
        require(videoBitrate > 0 && audioBitrate > 0)
        require(container == ContainerFormat.MP4 || videoCodec != VideoCodec.H264)
    }
}

data class ExportEstimate(
    val estimatedOutputBytes: Long,
    val requiredWorkingBytes: Long,
    val estimatedDurationMillis: Long?,
    val backend: BackendKind,
)

data class MediaProbe(
    val duration: DurationUs,
    val codedWidth: Int?,
    val codedHeight: Int?,
    val rotationDegrees: Int,
    val frameRate: Rational?,
    val variableFrameRate: Boolean,
    val videoMime: String?,
    val audioMime: String?,
    val hasAudio: Boolean,
    val hdr: Boolean,
    val bitrate: Long?,
)

enum class ProxyPreset(val maxLongEdge: Int, val videoBitrate: Long) {
    LOW_480P(854, 2_000_000), EDIT_720P(1280, 4_000_000), EDIT_1080P(1920, 8_000_000)
}

object ProxyPolicy {
    fun choose(probe: MediaProbe, lowMemoryDevice: Boolean): ProxyPreset? {
        val longEdge = maxOf(probe.codedWidth ?: 0, probe.codedHeight ?: 0)
        val fps = probe.frameRate?.asDouble() ?: 30.0
        val needsProxy = longEdge > 1920 || fps > 30.5 || probe.hdr || (probe.bitrate ?: 0) > 25_000_000
        if (!needsProxy) return null
        return if (lowMemoryDevice) ProxyPreset.LOW_480P else ProxyPreset.EDIT_720P
    }
}

