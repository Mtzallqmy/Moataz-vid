package com.moatazvid.media.media3

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.moatazvid.media.DeviceMediaCapabilities
import com.moatazvid.media.EncoderCapability
import com.moatazvid.media.VideoCodec

/** Reads capabilities from MediaCodecList instead of guessing from a device brand/model name. */
class AndroidDeviceMediaCapabilitiesProvider(private val context: Context) {
    fun snapshot(): DeviceMediaCapabilities {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        val encoders = buildList {
            infos.filter { it.isEncoder }.forEach { info ->
                info.supportedTypes.forEach { mime ->
                    val codec = mime.toVideoCodec() ?: return@forEach
                    val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@forEach
                    val video = capabilities.videoCapabilities ?: return@forEach
                    add(
                        EncoderCapability(
                            codec = codec,
                            maximumWidth = video.supportedWidths.upper,
                            maximumHeight = video.supportedHeights.upper,
                            maximumFps = video.supportedFrameRates.upper.toDouble(),
                            hardwareAccelerated = isHardwareAccelerated(info),
                            // HDR output is resolved separately against profiles and the requested format.
                            // Keep this conservative until a profile-level HDR check is bound.
                            hdrEncoding = false,
                        )
                    )
                }
            }
        }.distinctBy { listOf(it.codec, it.maximumWidth, it.maximumHeight, it.maximumFps, it.hardwareAccelerated) }

        val hevcDecode = infos.any { info -> !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val fingerprint = listOf(Build.FINGERPRINT, Build.VERSION.SDK_INT, abi, encoders.size).joinToString("|")
        return DeviceMediaCapabilities(
            abi = abi,
            apiLevel = Build.VERSION.SDK_INT,
            memoryClassMb = activityManager.memoryClass,
            encoders = encoders,
            hevcDecode = hevcDecode,
            hdrDisplay = false,
            fingerprint = fingerprint,
        )
    }

    fun supports(codec: VideoCodec, width: Int, height: Int, fps: Double): Boolean =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            if (!info.isEncoder) return@any false
            val mime = codec.mime
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@any false
            val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return@any false
            runCatching { video.areSizeAndRateSupported(width, height, fps) }.getOrDefault(false)
        }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        info.isHardwareAccelerated
    } else {
        val name = info.name.lowercase()
        !(name.contains("google") || name.contains("software") || name.contains("sw.") || name.startsWith("omx.google"))
    }

    private fun String.toVideoCodec(): VideoCodec? = when (lowercase()) {
        "video/avc" -> VideoCodec.H264
        "video/hevc" -> VideoCodec.HEVC
        "video/av01" -> VideoCodec.AV1
        else -> null
    }

    private val VideoCodec.mime: String
        get() = when (this) {
            VideoCodec.H264 -> "video/avc"
            VideoCodec.HEVC -> "video/hevc"
            VideoCodec.AV1 -> "video/av01"
        }
}
