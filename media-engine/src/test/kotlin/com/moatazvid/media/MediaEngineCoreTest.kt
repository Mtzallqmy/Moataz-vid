package com.moatazvid.media

import com.moatazvid.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MediaEngineCoreTest {
    private fun speed(range: TimeRangeUs, value: Double = 1.0) = SpeedCurve(
        listOf(SpeedSegmentNode(range, value, value, SpeedInterpolation.CONSTANT))
    )

    private fun graph(
        transform: TransformNode = TransformNode(),
        fps: Rational = Rational.FPS_29_97,
        overlays: List<OverlayNode> = emptyList(),
        transitions: List<TransitionNode> = emptyList(),
    ): RenderGraph {
        val range = TimeRangeUs(TimeUs(0), TimeUs(10_000_000))
        return RenderGraph(
            projectId = ProjectId("prj_01ARZ3NDEKTSV4RRFFQ69G5FAV"),
            sequenceId = SequenceId("seq_01ARZ3NDEKTSV4RRFFQ69G5FAV"),
            timelineRevision = 1,
            canvas = OutputCanvas(1080, 1920, fps, ProjectColorMode.SDR, 0xff000000),
            videoLayers = listOf(VideoLayer(
                ClipId("clp_01ARZ3NDEKTSV4RRFFQ69G5FAV"), TrackId("trk_01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                MediaInput.Original(SourceId("src_01ARZ3NDEKTSV4RRFFQ69G5FAV")), range,
                TimelinePlacement(TimeUs(0), DurationUs(10_000_000)), transform, 1f, speed(range), emptyList(), true
            )),
            audioLayers = emptyList(), overlays = overlays, transitions = transitions,
            duration = DurationUs(10_000_000),
        )
    }

    @Test fun `2997 output fps remains rational in graph`() {
        assertEquals(Rational(30_000, 1_001), graph().canvas.frameRate)
    }

    @Test fun `crop 9 by 16 requires crop capability`() {
        val graph = graph(TransformNode(cropLeft = .2f, cropRight = .8f))
        assertTrue(RenderFeature.CROP in CapabilityResolver().requiredFeatures(graph))
    }

    @Test fun `unsupported media3 transition selects ffmpeg`() {
        val transition = TransitionNode(
            "trs_1", ClipId("clp_a"), ClipId("clp_b"), TransitionType.CROSSFADE, DurationUs(500_000)
        )
        val capabilities = EngineCapabilities(
            media3Features = setOf(RenderFeature.TRIM),
            ffmpegFeatures = setOf(RenderFeature.TRIM, RenderFeature.CROSSFADE),
            codecs = emptyList(),
        )
        assertEquals(BackendKind.FFMPEG, CapabilityResolver().resolve(graph(transitions = listOf(transition)), capabilities).backend)
    }

    @Test fun `4k60 selects edit proxy`() {
        val probe = MediaProbe(DurationUs(1), 3840, 2160, 0, Rational.FPS_60, false, "video/avc", "audio/mp4a-latm", true, false, 50_000_000)
        assertEquals(ProxyPreset.EDIT_720P, ProxyPolicy.choose(probe, lowMemoryDevice = false))
    }

    @Test fun `ffmpeg policy rejects GPL build`() {
        val errors = FfmpegLicensePolicy().validate(
            FfmpegBuildInfo("8.0", "GPL", setOf("--enable-gpl"), setOf("libx264"))
        )
        assertTrue(errors.isNotEmpty())
    }
}

