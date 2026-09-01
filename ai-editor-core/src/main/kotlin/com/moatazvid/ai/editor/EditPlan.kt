package com.moatazvid.ai.editor

import com.moatazvid.core.*
import com.moatazvid.media.*
import com.moatazvid.speech.CaptionDraft

@JvmInline value class PendingEditId(val value: String)
@JvmInline value class ConversationId(val value: String)
@JvmInline value class AiMessageId(val value: String)

data class EditPlan(
    val schemaVersion: String = "1.2",
    val id: EditPlanId,
    val previousPlanId: EditPlanId? = null,
    val projectId: ProjectId,
    val sequenceId: SequenceId,
    val baseProjectRevision: Long,
    val title: String,
    val summary: String,
    val assumptions: List<String> = emptyList(),
    val operations: List<EditOperation>,
    val estimatedResult: EstimatedEditResult?,
    val warnings: List<String> = emptyList(),
    val confidence: Double? = null,
    val requiresUserApproval: Boolean = true,
) {
    init { require(schemaVersion in setOf("1.0", "1.1", "1.2")); require(baseProjectRevision >= 0); require(title.isNotBlank()); confidence?.let { require(it in 0.0..1.0) } }
}

data class EstimatedEditResult(val currentDuration: DurationUs, val estimatedDuration: DurationUs)

sealed interface EditOperation {
    val type: String
    data class TrimClip(val clipId: ClipId, val sourceRange: TimeRangeUs) : EditOperation { override val type = "TRIM_CLIP" }
    data class SplitClip(val clipId: ClipId, val atTimeline: TimeUs, val leftClipId: ClipId, val rightClipId: ClipId) : EditOperation { override val type = "SPLIT_CLIP" }
    data class RemoveRange(val clipId: ClipId, val sourceRange: TimeRangeUs, val leftClipId: ClipId?, val rightClipId: ClipId?, val reason: String) : EditOperation { override val type = "REMOVE_RANGE" }
    data class RemoveClip(val clipId: ClipId, val reason: String) : EditOperation { override val type = "REMOVE_CLIP" }
    data class MoveClip(val clipId: ClipId, val targetTrackId: TrackId, val targetIndex: Int) : EditOperation { override val type = "MOVE_CLIP" }
    data class InsertRange(val sourceId: SourceId, val sourceRange: TimeRangeUs, val newClipId: ClipId, val targetTrackId: TrackId, val timelineStart: TimeUs) : EditOperation { override val type = "INSERT_RANGE" }
    data class ReplaceWithTake(val oldClipId: ClipId, val newSourceId: SourceId, val sourceRange: TimeRangeUs) : EditOperation { override val type = "REPLACE_WITH_TAKE" }
    data class ChangeSpeed(val clipId: ClipId, val speed: Double, val preservePitch: Boolean = true) : EditOperation { override val type = "CHANGE_SPEED" }
    data class SetCrop(val clipId: ClipId, val aspectRatio: String, val mode: CropMode) : EditOperation { override val type = "SET_CROP" }
    data class SetTransform(val clipId: ClipId, val transform: TransformNode) : EditOperation { override val type = "SET_TRANSFORM" }
    data class AddZoom(val clipId: ClipId, val timelineRange: TimeRangeUs, val scaleFrom: Float, val scaleTo: Float) : EditOperation { override val type = "ADD_ZOOM" }
    data class AddText(val id: ClipId, val trackId: TrackId, val timelineRange: TimeRangeUs, val text: String, val styleId: String) : EditOperation { override val type = "ADD_TEXT" }
    data class UpdateText(val id: ClipId, val text: String) : EditOperation { override val type = "UPDATE_TEXT" }
    data class RemoveOverlay(val id: ClipId) : EditOperation { override val type = "REMOVE_OVERLAY" }
    data class AddImageOverlay(val id: ClipId, val assetId: AssetId, val trackId: TrackId, val timelineRange: TimeRangeUs, val transform: CreativeTransform = CreativeTransform(), val opacity: Float = 1f, val zIndex: Int = 60) : EditOperation { override val type = "ADD_IMAGE_OVERLAY" }
    data class SetOverlayTransform(val id: ClipId, val transform: CreativeTransform) : EditOperation { override val type = "SET_OVERLAY_TRANSFORM" }
    data class AddCaptions(val trackId: TrackId, val transcriptId: String, val styleId: String, val drafts: List<CaptionDraft>) : EditOperation { override val type = "ADD_CAPTIONS" }
    data class RegenerateCaptions(val trackId: TrackId, val transcriptId: String, val styleId: String, val drafts: List<CaptionDraft>) : EditOperation { override val type = "REGENERATE_CAPTIONS" }
    data class UpdateCaptionStyle(val styleId: String, val wordsPerChunk: Int, val position: CaptionPosition, val fontScale: Float) : EditOperation { override val type = "UPDATE_CAPTION_STYLE" }
    data class AddAudio(val id: ClipId, val assetId: AssetId, val trackId: TrackId, val timelineStart: TimeUs, val duration: DurationUs, val volume: Float) : EditOperation { override val type = "ADD_AUDIO" }
    data class RemoveAudio(val clipId: ClipId) : EditOperation { override val type = "REMOVE_AUDIO" }
    data class SetAudioGain(val clipId: ClipId, val gainDb: Float) : EditOperation { override val type = "SET_AUDIO_GAIN" }
    data class SetDucking(val trackId: TrackId, val settings: DuckingSettings) : EditOperation { override val type = "SET_DUCKING" }
    data class AddFade(val clipId: ClipId, val fadeType: FadeType, val duration: DurationUs) : EditOperation { override val type = "ADD_FADE" }
    data class ApplyColorAdjustment(val clipId: ClipId, val brightness: Float, val contrast: Float, val saturation: Float) : EditOperation { override val type = "APPLY_COLOR_ADJUSTMENT" }
    data class AddEffect(val clipId: ClipId, val effectId: EffectId, val effectType: EffectType, val parameters: Map<String, Double>, val range: TimeRangeUs? = null) : EditOperation { override val type = "ADD_EFFECT" }
    data class UpdateEffect(val clipId: ClipId, val effectId: EffectId, val parameters: Map<String, Double>) : EditOperation { override val type = "UPDATE_EFFECT" }
    data class RemoveEffect(val clipId: ClipId, val effectId: EffectId) : EditOperation { override val type = "REMOVE_EFFECT" }
    data class AddTransition(val transition: CreativeTransition) : EditOperation { override val type = "ADD_TRANSITION" }
    data class RemoveTransition(val transitionId: TransitionId) : EditOperation { override val type = "REMOVE_TRANSITION" }
    data class SetProjectAspectRatio(val width: Int, val height: Int) : EditOperation { override val type = "SET_PROJECT_ASPECT_RATIO" }
    data class SetDurationTarget(val duration: DurationUs, val tolerancePercent: Double) : EditOperation { override val type = "SET_PROJECT_DURATION_TARGET" }
    data class AddConstraint(val constraint: ProjectConstraint) : EditOperation { override val type = "ADD_CONSTRAINT" }
    data class RemoveConstraint(val constraintId: ConstraintId) : EditOperation { override val type = "REMOVE_CONSTRAINT" }
}

enum class CropMode { FIT, FILL, SMART }
enum class CaptionPosition { LOWER_CENTER, CENTER, UPPER_CENTER }
enum class FadeType { AUDIO_IN, AUDIO_OUT, VIDEO_IN, VIDEO_OUT }

data class ClipEditProperties(
    val speed: Double = 1.0,
    val preservePitch: Boolean = true,
    val gainDb: Float = 0f,
    val transform: TransformNode = TransformNode(),
    val cropAspectRatio: String? = null,
    val fades: List<Pair<FadeType, DurationUs>> = emptyList(),
    val effects: List<String> = emptyList(),
)

data class AiEditableProject(
    val snapshot: com.moatazvid.storage.ProjectSnapshot,
    val sources: List<MediaSource>,
    val clipProperties: Map<ClipId, ClipEditProperties> = emptyMap(),
    val constraints: List<ProjectConstraint> = emptyList(),
    val protectedRanges: List<ProtectedRange> = emptyList(),
    val captions: List<CaptionDraft> = emptyList(),
    val creativeElements: List<CreativeElement> = emptyList(),
    val creativeEffects: Map<ClipId, List<EffectInstance>> = emptyMap(),
    val creativeTransitions: List<CreativeTransition> = emptyList(),
    val ducking: Map<TrackId, DuckingSettings> = emptyMap(),
) {
    val revision get() = snapshot.sequence.revision
    val duration: DurationUs get() = DurationUs(snapshot.items.maxOfOrNull { it.timelineStart.value + it.timelineDuration.value } ?: 0)
}
