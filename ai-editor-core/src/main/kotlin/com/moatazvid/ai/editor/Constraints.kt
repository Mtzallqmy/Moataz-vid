package com.moatazvid.ai.editor

import com.moatazvid.core.*

enum class ProjectConstraintType { PRESERVE_RANGE, PRESERVE_TOPIC, TARGET_ASPECT_RATIO, TARGET_DURATION, NO_MUSIC, NO_CAPTIONS, STYLE_PREFERENCE, USER_LOCK, CUSTOM_TEXT }
enum class ConstraintPriority { NORMAL, HIGH, REQUIRED }
enum class ConstraintSource { USER, AI_SUGGESTION, PRESET, SYSTEM }

data class ProjectConstraint(
    val id: ConstraintId,
    val projectId: ProjectId,
    val type: ProjectConstraintType,
    val text: String,
    val sourceId: SourceId? = null,
    val sourceRange: TimeRangeUs? = null,
    val source: ConstraintSource,
    val priority: ConstraintPriority,
    val createdBy: String,
    val createdAtEpochMs: Long,
    val active: Boolean = true,
)

data class ProtectedRange(
    val id: String,
    val projectId: ProjectId,
    val sourceId: SourceId,
    val sourceRange: TimeRangeUs,
    val reason: String,
    val active: Boolean = true,
)

enum class WordCutStyle { FAST_SOCIAL, NATURAL, CINEMATIC, CUSTOM }
data class WordSafeCutPolicy(val style: WordCutStyle, val beforePadding: DurationUs, val afterPadding: DurationUs, val snapTolerance: DurationUs)
object WordSafePolicies {
    val FAST_SOCIAL = WordSafeCutPolicy(WordCutStyle.FAST_SOCIAL, DurationUs(40_000), DurationUs(60_000), DurationUs(180_000))
    val NATURAL = WordSafeCutPolicy(WordCutStyle.NATURAL, DurationUs(60_000), DurationUs(80_000), DurationUs(220_000))
    val CINEMATIC = WordSafeCutPolicy(WordCutStyle.CINEMATIC, DurationUs(100_000), DurationUs(140_000), DurationUs(300_000))
}

data class SilenceEditPolicy(
    val minimumDetectedSilence: DurationUs = DurationUs(500_000),
    val targetRemainingGap: DurationUs = DurationUs(180_000),
    val preserveSentenceBreath: Boolean = true,
    val preserveSpeakerHandoff: Boolean = true,
    val maxCutsPerMinute: Int = 24,
    val wordSafePolicy: WordSafeCutPolicy = WordSafePolicies.NATURAL,
) { init { require(maxCutsPerMinute in 1..120); require(targetRemainingGap.value < minimumDetectedSilence.value) } }
