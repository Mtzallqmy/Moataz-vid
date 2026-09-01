package com.moatazvid.media

import com.moatazvid.core.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreativeProductionTest {
    @Test fun `creative mapper preserves z order and effect chain`() {
        val graph = graph()
        val text = TextElement(CreativeElementId("title"), TrackId("overlay"), range(0, 2_000_000), "مرحبا", "title", zIndex = 80)
        val logo = ImageOverlayElement(CreativeElementId("logo"), TrackId("overlay"), range(0, 5_000_000), AssetId("logo"), zIndex = 60)
        val effect = EffectInstance(EffectId("e1"), EffectType.BRIGHTNESS, listOf(EffectParameter("amount", 0.2, -1.0, 1.0)))
        val mapped = CreativeRenderMapper().apply(graph, listOf(text, logo), mapOf(ClipId("clip") to listOf(effect)))
        assertEquals(listOf("logo", "title"), mapped.graph.overlays.map { it.id })
        assertTrue(mapped.graph.videoLayers.single().effects.isNotEmpty())
        assertTrue(mapped.compatibility.fullParity)
    }

    @Test fun `ducking envelope reduces music only around speech`() {
        val settings = DuckingSettings(DuckingMode.AUTO_SPEECH_DUCK, reductionDb = -14f, attackMs = 100, releaseMs = 300)
        val points = DuckingProcessor().buildEnvelope(listOf(range(1_000_000, 2_000_000)), DurationUs(4_000_000), settings)
        assertTrue(points.any { it.timeMs == 1_000L && it.gainDb == -14f })
        assertTrue(points.any { it.timeMs == 2_300L && it.gainDb == 0f })
    }

    @Test fun `too long transition is rejected`() {
        val from = item("a", 0, 1_000_000)
        val to = item("b", 1_000_000, 1_000_000)
        val transition = CreativeTransition(TransitionId("t"), CreativeTransitionType.CROSS_DISSOLVE, 1_500, from.id, to.id)
        assertTrue("TRANSITION_TOO_LONG" in TransitionValidator.validate(transition, from, to))
    }

    @Test fun `proxy policy selects edit proxy for 4k60`() {
        val probe = MediaProbe(DurationUs(10_000_000), 3840, 2160, 0, Rational.FPS_60, false, "video/avc", "audio/mp4a-latm", true, false, 45_000_000)
        val decision = ProxyDecisionPolicy().decide(probe, DevicePerformanceTier.MID)
        assertTrue(decision.required)
        assertEquals(ProxyPreset.EDIT_720P, decision.preset)
    }

    @Test fun `quality resolver keeps rational ntsc fps`() {
        val caps = DeviceMediaCapabilities("arm64-v8a", 35, 512, listOf(EncoderCapability(VideoCodec.H264, 3840, 2160, 60.0, true, false)), true, false, "x")
        val settings = ExportSettingsResolver.resolve(graph(), caps, ExportPreset.FULL_HD_1080, ExportQuality.BALANCED, Rational.FPS_29_97)
        assertEquals(30_000, settings.frameRate.numerator)
        assertEquals(1_001, settings.frameRate.denominator)
    }

    @Test fun `output verifier rejects long av drift`() = runTest {
        val verifier = OutputVerifier(object : OutputInspector {
            override suspend fun inspect(uri: String) = OutputVerification(true, 1000, DurationUs(5_000_000), 1920, 1080, Rational.FPS_30, "avc", "aac", true, true, 500_000, emptyList())
        })
        val result = verifier.verify("temp", graph(), ExportSettings(width = 1920, height = 1080, frameRate = Rational.FPS_30))
        assertFalse(result.valid)
        assertTrue("AV_DRIFT" in result.issues)
    }

    @Test fun `thermal policy blocks nonessential work when hot`() {
        assertFalse(ThermalPolicy.decide(ThermalLevel.HOT).allowNonessentialJobs)
        assertEquals(0, ThermalPolicy.decide(ThermalLevel.CRITICAL).maximumConcurrency)
    }

    @Test fun `job reconciler never claims resume for non resumable dead worker`() {
        val job = JobRecord("e", HeavyJobType.FINAL_EXPORT, JobStage.ENCODING, resumable = false, workerAlive = false)
        assertEquals(ReconciledJobState.INTERRUPTED, JobReconciler.reconcile(job))
    }

    private fun graph(): RenderGraph {
        val source = SourceId("src")
        val clipRange = range(0, 5_000_000)
        val video = VideoLayer(ClipId("clip"), TrackId("video"), MediaInput.Original(source), clipRange,
            TimelinePlacement(TimeUs(0), DurationUs(5_000_000)), TransformNode(), 1f,
            SpeedCurve(listOf(SpeedSegmentNode(clipRange, 1.0, 1.0, SpeedInterpolation.CONSTANT))), emptyList(), true)
        return RenderGraph(ProjectId("p"), SequenceId("seq"), 1, OutputCanvas(1920, 1080, Rational.FPS_30, ProjectColorMode.SDR, 0xFF000000),
            listOf(video), emptyList(), emptyList(), emptyList(), DurationUs(5_000_000))
    }

    private fun item(id: String, start: Long, duration: Long) = TimelineItem(ClipId(id), ProjectId("p"), SequenceId("seq"), TrackId("video"), TimelineItemType.VIDEO,
        TimeUs(start), DurationUs(duration), SourceId("src"), TimeRangeUs(TimeUs(start), TimeUs(start + duration)))

    private fun range(start: Long, end: Long) = TimeRangeUs(TimeUs(start), TimeUs(end))
}
