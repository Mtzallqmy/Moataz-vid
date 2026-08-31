package com.moatazvid.ai.editor

import com.moatazvid.core.*
import com.moatazvid.speech.*
import kotlin.math.abs

interface EditorIdFactory { fun clip(prefix: String): ClipId; fun plan(): EditPlanId; fun pending(): PendingEditId; fun transaction(): TransactionId }
class SequentialEditorIdFactory : EditorIdFactory {
    private var value = 0L
    @Synchronized private fun next() = (++value).toString().padStart(6, '0')
    override fun clip(prefix: String) = ClipId("${prefix}_${next()}")
    override fun plan() = EditPlanId("plan_${next()}")
    override fun pending() = PendingEditId("pending_${next()}")
    override fun transaction() = TransactionId("transaction_${next()}")
}

class WordSafeCutSnapper {
    fun snap(range: TimeRangeUs, words: List<TranscriptWord>, policy: WordSafeCutPolicy): TimeRangeUs {
        fun nearest(value: Long, candidates: List<Long>): Long = candidates.minByOrNull { abs(it - value) }?.takeIf { abs(it - value) <= policy.snapTolerance.value } ?: value
        val boundaries = words.flatMap { listOf(it.sourceRange.start.value, it.sourceRange.endExclusive.value) }
        val start = (nearest(range.start.value, boundaries) - policy.beforePadding.value).coerceAtLeast(0)
        val end = nearest(range.endExclusive.value, boundaries) + policy.afterPadding.value
        return TimeRangeUs(TimeUs(start), TimeUs(maxOf(end, start + 1)))
    }
}

class SilenceCommandPlanner(private val ids: EditorIdFactory, private val policy: SilenceEditPolicy = SilenceEditPolicy()) {
    fun plan(project: AiEditableProject, silence: List<SilenceRange>, words: List<TranscriptWord> = emptyList()): EditPlan {
        val maxCuts = ((project.duration.value / 60_000_000.0).coerceAtLeast(1.0) * policy.maxCutsPerMinute).toInt()
        val operations = silence.asSequence().filter { it.sourceRange.duration.value >= policy.minimumDetectedSilence.value }.take(maxCuts).mapNotNull { silent ->
            val clip = project.snapshot.items.firstOrNull { it.sourceId == silent.sourceId && it.sourceRange?.overlaps(silent.sourceRange) == true && !it.locked } ?: return@mapNotNull null
            val keep = when {
                silent.sourceRange.duration.value > 1_500_000 -> policy.targetRemainingGap.value
                silent.sourceRange.duration.value >= 900_000 -> maxOf(policy.targetRemainingGap.value, 220_000)
                else -> maxOf(policy.targetRemainingGap.value, 300_000)
            }
            if (silent.sourceRange.duration.value <= keep) return@mapNotNull null
            val raw = TimeRangeUs(TimeUs(silent.sourceRange.start.value + keep / 2), TimeUs(silent.sourceRange.endExclusive.value - (keep - keep / 2)))
            val snapped = if (words.isEmpty()) raw else WordSafeCutSnapper().snap(raw, words.filter { it.sourceId == silent.sourceId }, policy.wordSafePolicy)
            val clipSourceRange = requireNotNull(clip.sourceRange)
            if (snapped.endExclusive.value > clipSourceRange.endExclusive.value || snapped.start < clipSourceRange.start) return@mapNotNull null
            EditOperation.RemoveRange(clip.id, snapped, ids.clip("silence_left"), ids.clip("silence_right"), "long_silence")
        }.toList()
        val estimate = DurationUs((project.duration.value - operations.sumOf { it.sourceRange.duration.value }).coerceAtLeast(0))
        return EditPlan(id = ids.plan(), projectId = project.snapshot.project.id, sequenceId = project.snapshot.sequence.id, baseProjectRevision = project.revision,
            title = "تقصير فترات الصمت", summary = "تقصير الصمت الطويل مع إبقاء تنفس طبيعي بين الجمل.", operations = operations,
            estimatedResult = EstimatedEditResult(project.duration, estimate), warnings = if (operations.isEmpty()) listOf("لم أجد صمتًا طويلًا آمنًا للحذف") else emptyList())
    }
}

data class TakeCandidate(
    val clipId: ClipId, val text: String, val duration: DurationUs, val audioScore: Double, val visualScore: Double?,
    val speechConfidence: Double, val fillerCount: Int, val slipCount: Int, val silenceRatio: Double,
) {
    val score: Double get() = speechConfidence * 0.30 + audioScore * 0.25 + (visualScore ?: 0.5) * 0.15 +
        (1.0 - silenceRatio.coerceIn(0.0, 1.0)) * 0.15 + (1.0 / (1 + fillerCount + slipCount)) * 0.15
}
data class TakeCandidateGroup(val id: String, val candidates: List<TakeCandidate>)
class BestTakePlanner(private val ids: EditorIdFactory) {
    fun plan(project: AiEditableProject, groups: List<TakeCandidateGroup>): EditPlan {
        val operations = groups.flatMap { group ->
            val best = group.candidates.maxByOrNull { it.score } ?: return@flatMap emptyList()
            group.candidates.filter { it.clipId != best.clipId }.map { EditOperation.RemoveClip(it.clipId, "duplicate_take_lower_score") }
        }
        return EditPlan(id = ids.plan(), projectId = project.snapshot.project.id, sequenceId = project.snapshot.sequence.id, baseProjectRevision = project.revision,
            title = "اختيار أفضل المحاولات", summary = "الإبقاء على المحاولات الأعلى وضوحًا وجودةً من مجموعات التكرار المرشحة.", operations = operations,
            estimatedResult = null, warnings = listOf("القرار الآلي يعتمد على metadata؛ راجع المعاينة قبل التطبيق"))
    }
}

data class DurationPlanResult(val possible: Boolean, val operations: List<EditOperation>, val estimatedDuration: DurationUs, val warning: String?)
class DurationPlanner(private val tolerancePercent: Double = 5.0) {
    fun plan(project: AiEditableProject, target: DurationUs, candidates: List<EditOperation>): DurationPlanResult {
        val lowerBound = (target.value * (1.0 - tolerancePercent / 100.0)).toLong()
        val upperBound = (target.value * (1.0 + tolerancePercent / 100.0)).toLong()
        if (project.duration.value in lowerBound..upperBound) return DurationPlanResult(true, emptyList(), project.duration, null)
        var duration = project.duration.value; val selected = mutableListOf<EditOperation>()
        for (candidate in candidates) {
            val removable = when (candidate) {
                is EditOperation.RemoveClip -> project.snapshot.items.firstOrNull { it.id == candidate.clipId }?.timelineDuration?.value ?: 0
                is EditOperation.RemoveRange -> candidate.sourceRange.duration.value
                else -> 0
            }
            if (duration - removable >= lowerBound) { selected += candidate; duration -= removable }
            if (duration <= upperBound) break
        }
        val possible = duration <= upperBound
        return DurationPlanResult(possible, selected, DurationUs(duration), if (possible) null else "المدة المطلوبة غير ممكنة دون حذف محتوى محمي أو أساسي")
    }
}

data class EditStrategy(
    val targetAspectRatio: String?, val targetDuration: DurationTargetRange?, val beats: List<StoryBeat>,
    val pacing: String, val captionsSuggested: Boolean, val assumptions: List<String>,
)
data class StoryBeat(val role: StoryBeatRole, val candidateClipIds: List<ClipId>, val required: Boolean)
data class DurationTargetRange(val minimum: DurationUs, val maximum: DurationUs)
enum class StoryBeatRole { HOOK, MAIN_IDEA, BENEFIT, EXAMPLE, CTA }
object ReelStrategyFactory {
    fun create(candidates: Map<StoryBeatRole, List<ClipId>>, requestedSeconds: Long? = null) = EditStrategy(
        "9:16", DurationTargetRange(DurationUs((requestedSeconds ?: 30) * 1_000_000), DurationUs((requestedSeconds ?: 60) * 1_000_000)),
        StoryBeatRole.entries.mapNotNull { role -> candidates[role]?.takeIf { it.isNotEmpty() }?.let { StoryBeat(role, it, role in setOf(StoryBeatRole.HOOK, StoryBeatRole.MAIN_IDEA)) } },
        "FAST_SOCIAL", true, listOf("تُكيف البنية بحسب المادة المتاحة ولا يُخترع CTA غير موجود"),
    )
}
