import com.moatazvid.core.*
import com.moatazvid.media.*
import com.moatazvid.storage.*
import com.moatazvid.speech.*
import com.moatazvid.ai.provider.*
import java.nio.file.Files

private fun checkCondition(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val adjacentA = TimeRangeUs(TimeUs(0), TimeUs(1_000))
    val adjacentB = TimeRangeUs(TimeUs(1_000), TimeUs(2_000))
    checkCondition(!adjacentA.overlaps(adjacentB), "half-open range contract")
    checkCondition(Rational.FPS_29_97 == Rational(30_000, 1_001), "rational fps")

    val id = UlidIdGenerator().newId(IdKind.PROJECT)
    checkCondition(id.startsWith("prj_") && id.length == 30, "ULID policy")

    val projectId = ProjectId("prj_01ARZ3NDEKTSV4RRFFQ69G5FAV")
    val root = Files.createTempDirectory("moataz-smoke")
    val paths = ProjectPaths.under(root, projectId)
    checkCondition(paths.proxies.startsWith(root), "project path containment")

    val state = root.resolve("state.json")
    AtomicFileWriter().write(state) { it.write("revision-1".toByteArray()) }
    AtomicFileWriter().write(state) { it.write("revision-2".toByteArray()) }
    checkCondition(Files.readString(state) == "revision-2", "atomic replace")

    val cachePlan = CachePolicy(warningFreeBytes = 500, criticalFreeBytes = 100).plan(
        entries = listOf(
            CacheEntry("old", projectId, CacheKind.THUMBNAIL, "old", 100, 1, "f", CacheImportance.REGENERATABLE),
            CacheEntry("pinned", projectId, CacheKind.PROXY, "pin", 1_000, 0, "f", CacheImportance.REGENERATABLE, pinned = true),
        ),
        freeBytes = 0,
        targetFreeBytes = 100,
    )
    checkCondition(cachePlan.entriesToDelete.map { it.id } == listOf("old"), "cache safety")

    val range = TimeRangeUs(TimeUs(0), TimeUs(5_000_000))
    val graph = RenderGraph(
        projectId = projectId,
        sequenceId = SequenceId("seq_01ARZ3NDEKTSV4RRFFQ69G5FAV"),
        timelineRevision = 7,
        canvas = OutputCanvas(1080, 1920, Rational.FPS_29_97, ProjectColorMode.SDR, 0xff000000),
        videoLayers = listOf(
            VideoLayer(
                id = ClipId("clp_01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                trackId = TrackId("trk_01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                input = MediaInput.Original(SourceId("src_01ARZ3NDEKTSV4RRFFQ69G5FAV")),
                sourceRange = range,
                placement = TimelinePlacement(TimeUs(0), DurationUs(5_000_000)),
                transform = TransformNode(cropLeft = 0.2f, cropRight = 0.8f),
                opacity = 1f,
                speed = SpeedCurve(listOf(SpeedSegmentNode(range, 1.0, 1.0, SpeedInterpolation.CONSTANT))),
                effects = emptyList(),
                includeSourceAudio = true,
            )
        ),
        audioLayers = emptyList(),
        overlays = emptyList(),
        transitions = emptyList(),
        duration = DurationUs(5_000_000),
    )
    checkCondition(RenderFeature.CROP in CapabilityResolver().requiredFeatures(graph), "capability extraction")

    val proxy = ProxyPolicy.choose(
        MediaProbe(DurationUs(1), 3840, 2160, 0, Rational.FPS_60, false, "video/avc", "audio/mp4a-latm", true, false, 50_000_000),
        lowMemoryDevice = false,
    )
    checkCondition(proxy == ProxyPreset.EDIT_720P, "proxy policy")

    val licenseErrors = FfmpegLicensePolicy().validate(
        FfmpegBuildInfo("8", "GPL", setOf("--enable-gpl"), setOf("libx264"))
    )
    checkCondition(licenseErrors.isNotEmpty(), "FFmpeg license guard")

    checkCondition(ArabicTextNormalizer.normalize("أَلْبَطّارية ١٢") == "البطارية 12", "Arabic transcript normalization")
    val silence = SilenceDetector().detect(SourceId("src"), FloatArray(16_000))
    checkCondition(silence.single().sourceRange.duration.value == 1_000_000L, "offline silence detection")
    checkCondition(BaseUrlNormalizer.normalize("https://example.test/v1/") == "https://example.test/v1", "provider base URL")
    checkCondition(BaseUrlNormalizer.resolve("https://example.test/v1", "/v1/chat/completions") == "https://example.test/v1/chat/completions", "no duplicate v1")
    checkCondition(RedactingNetworkLogger(true).requestSummary(HttpRequest(RequestId("r"), "POST", "https://example.test", mapOf("Authorization" to "Bearer secret"), "private", 1_000, true)).contains("<redacted>"), "secret redaction")

    println("Moataz vid core smoke tests: PASS")
}
