package com.moatazvid.media.media3

import com.moatazvid.videouse.VideoUsePolicy

/** Android rendering translation of video-use's load-bearing output invariants. */
object VideoUseMedia3Policy {
    private val policy = VideoUsePolicy.PRODUCTION

    fun normalize(spec: Media3CompositionSpec): Media3CompositionSpec = spec.copy(
        sequences = spec.sequences.map { sequence ->
            sequence.copy(items = sequence.items.map(::normalizeItem))
        },
        overlays = spec.overlays.sortedWith(
            compareBy<Media3OverlaySpec> { it.kind == Media3OverlayKind.CAPTION }
                .thenBy { it.startUs }
                .thenBy { it.endUs }
                .thenBy { it.id }
        ),
    )

    fun inspect(spec: Media3CompositionSpec): VideoUseRenderPreflight {
        val normalized = normalize(spec)
        val issues = buildList {
            normalized.sequences.flatMap { it.items }.filter { !it.removeAudio }.forEach { item ->
                val required = minimumBoundaryFade(item.timelineDurationUs)
                if (item.fadeInUs < required || item.fadeOutUs < required) {
                    add("audio-boundary-fade:${item.stableId}")
                }
            }
            val firstCaption = normalized.overlays.indexOfFirst { it.kind == Media3OverlayKind.CAPTION }
            if (firstCaption >= 0 && normalized.overlays.drop(firstCaption).any { it.kind != Media3OverlayKind.CAPTION }) {
                add("captions-not-last")
            }
            if (normalized.transitions.any { it.type.name != "CUT" }) {
                add("unsupported-transition-in-media3")
            }
        }
        return VideoUseRenderPreflight(issues.isEmpty(), issues)
    }

    private fun normalizeItem(item: Media3EditedItemSpec): Media3EditedItemSpec {
        if (item.removeAudio) return item
        val required = minimumBoundaryFade(item.timelineDurationUs)
        var fadeIn = maxOf(item.fadeInUs, required)
        var fadeOut = maxOf(item.fadeOutUs, required)
        if (fadeIn + fadeOut > item.timelineDurationUs) {
            val available = item.timelineDurationUs
            if (item.fadeInUs >= item.fadeOutUs) {
                fadeOut = minOf(fadeOut, available / 2)
                fadeIn = minOf(fadeIn, available - fadeOut)
            } else {
                fadeIn = minOf(fadeIn, available / 2)
                fadeOut = minOf(fadeOut, available - fadeIn)
            }
        }
        return item.copy(fadeInUs = fadeIn, fadeOutUs = fadeOut)
    }

    private fun minimumBoundaryFade(durationUs: Long): Long =
        minOf(policy.boundaryAudioFade.value, durationUs / 2).coerceAtLeast(0)
}

data class VideoUseRenderPreflight(
    val passed: Boolean,
    val issues: List<String>,
)
