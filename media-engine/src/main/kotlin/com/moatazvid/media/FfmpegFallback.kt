package com.moatazvid.media

/**
 * Trusted boundary. Callers submit a typed graph; only this package may translate it to
 * native FFmpeg arguments. No shell is used and no caller-supplied filter string is accepted.
 */
interface FfmpegNativeBridge {
    suspend fun execute(request: FfmpegExecutionRequest, progress: (MediaEngineProgress) -> Unit): FfmpegNativeResult
    suspend fun cancel(executionId: String)
    suspend fun probe(inputHandle: String): MediaProbe
    fun buildInfo(): FfmpegBuildInfo
}

data class FfmpegExecutionRequest(
    val executionId: String,
    val inputs: List<TrustedInputHandle>,
    val output: TrustedOutputHandle,
    val graph: TypedFilterGraph,
    val codecs: CodecSelection,
)

data class TrustedInputHandle(val stableId: String, val resolverToken: String)
data class TrustedOutputHandle(val resolverToken: String)
data class CodecSelection(val video: VideoCodec, val audio: AudioCodec, val container: ContainerFormat)

data class TypedFilterGraph(val nodes: List<FfmpegFilterNode>)
sealed interface FfmpegFilterNode {
    data class Trim(val inputIndex: Int, val startUs: Long, val endUs: Long) : FfmpegFilterNode
    data class Scale(val width: Int, val height: Int) : FfmpegFilterNode
    data class Crop(val width: Int, val height: Int, val x: Int, val y: Int) : FfmpegFilterNode
    data class SetPts(val speed: Double) : FfmpegFilterNode
    data class Volume(val gainDb: Float) : FfmpegFilterNode
    data class Fade(val type: FadeType, val startUs: Long, val durationUs: Long) : FfmpegFilterNode
    data class Overlay(val inputIndex: Int, val x: Int, val y: Int, val startUs: Long, val endUs: Long) : FfmpegFilterNode
    data class CrossFade(val durationUs: Long) : FfmpegFilterNode
}
enum class FadeType { AUDIO_IN, AUDIO_OUT, VIDEO_IN, VIDEO_OUT }

data class FfmpegNativeResult(val exitCode: Int, val safeLog: String, val outputBytes: Long?)
data class FfmpegBuildInfo(
    val version: String,
    val license: String,
    val configureFlags: Set<String>,
    val enabledLibraries: Set<String>,
)

class FfmpegLicensePolicy {
    fun validate(info: FfmpegBuildInfo): List<String> = buildList {
        if ("--enable-gpl" in info.configureFlags) add("GPL components are forbidden by the V1 distribution policy")
        if ("--enable-nonfree" in info.configureFlags) add("Nonfree build is forbidden")
        val forbidden = setOf("libx264", "libx265", "libfdk_aac")
        info.enabledLibraries.intersect(forbidden).forEach { add("Forbidden external codec: $it") }
        if (!info.license.contains("LGPL", ignoreCase = true)) add("Expected an LGPL FFmpeg build")
    }
}

