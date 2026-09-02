package com.moatazvid.ai.editor

import com.moatazvid.ai.provider.*
import com.moatazvid.core.*
import com.moatazvid.media.*

class ProviderEditPlanClient(private val codec: EditPlanJsonCodec = EditPlanJsonCodec()) : EditPlanProposalClient {
    override suspend fun strategy(model: EditingModel, context: AiTaskContext): LlmResult<String> {
        val request = LlmRequest(
            RequestId("strategy_${System.currentTimeMillis()}"),
            model.descriptor.id,
            listOf(
                LlmMessage(LlmRole.SYSTEM, listOf(LlmContentPart.Text(PromptRepository.coreRules))),
                LlmMessage(LlmRole.USER, listOf(LlmContentPart.Text(PromptRepository.strategy(context)))),
            ),
        )
        return when (val result = model.provider.complete(request)) {
            is LlmResult.Success -> LlmResult.Success(result.value.text)
            is LlmResult.Failure -> result
        }
    }

    override suspend fun propose(model: EditingModel, context: AiTaskContext, previous: EditPlan?, feedback: String?): LlmResult<EditPlan> {
        val prompt = PromptRepository.editPlan(context) + (previous?.let { "\nPREVIOUS_PLAN_DATA id=${it.id.value} summary=${it.summary}\nUSER_FEEDBACK=${feedback.orEmpty()}" } ?: "")
        val request = LlmRequest(RequestId("edit_${System.currentTimeMillis()}"), model.descriptor.id,
            listOf(LlmMessage(LlmRole.SYSTEM, listOf(LlmContentPart.Text(PromptRepository.coreRules))), LlmMessage(LlmRole.USER, listOf(LlmContentPart.Text(prompt)))))
        return model.provider.invokeStructured(StructuredRequest(request, "moataz_vid_edit_plan_v1_2", EDIT_PLAN_SCHEMA, true, codec::decode) { plan ->
            buildList { if (plan.projectId != context.projectId) add("projectId mismatch"); if (plan.baseProjectRevision != context.projectRevision) add("base revision mismatch") }
        })
    }
    override suspend fun repair(model: EditingModel, invalid: EditPlan, errors: List<PlanValidationError>, validIds: Set<String>, attempt: Int): LlmResult<EditPlan> {
        val request = LlmRequest(RequestId("repair_${System.currentTimeMillis()}_$attempt"), model.descriptor.id,
            listOf(LlmMessage(LlmRole.SYSTEM, listOf(LlmContentPart.Text(PromptRepository.coreRules))),
                LlmMessage(LlmRole.USER, listOf(LlmContentPart.Text(PromptRepository.repair(errors, validIds))))))
        return model.provider.invokeStructured(StructuredRequest(request, "moataz_vid_edit_plan_v1_2", EDIT_PLAN_SCHEMA, true, codec::decode))
    }
    override suspend fun analyze(model: EditingModel, context: AiTaskContext): LlmResult<String> {
        val request = LlmRequest(RequestId("analyze_${System.currentTimeMillis()}"), model.descriptor.id,
            listOf(LlmMessage(LlmRole.SYSTEM, listOf(LlmContentPart.Text(PromptRepository.coreRules))), LlmMessage(LlmRole.USER, listOf(LlmContentPart.Text(PromptRepository.editPlan(context) + "\nReturn analysis only; do not return an EditPlan.")))))
        return when (val result = model.provider.complete(request)) { is LlmResult.Success -> LlmResult.Success(result.value.text); is LlmResult.Failure -> result }
    }
    companion object {
        val EDIT_PLAN_SCHEMA: JsonObject = mapOf(
            "type" to JsonValue.StringValue("object"),
            "required" to JsonValue.ArrayValue(listOf("schemaVersion", "id", "projectId", "sequenceId", "baseProjectRevision", "title", "summary", "operations", "requiresUserApproval").map(JsonValue::StringValue)),
            "additionalProperties" to JsonValue.BooleanValue(false),
        )
    }
}

class EditPlanJsonCodec {
    fun decode(json: String): EditPlan {
        val root = MiniJson.parse(json).objectOrNull() ?: error("EditPlan must be object")
        fun str(name: String) = root[name]?.stringOrNull() ?: error("Missing $name")
        fun long(name: String) = root[name]?.numberOrNull()?.toLong() ?: error("Missing $name")
        val operations = root["operations"]?.arrayOrNull()?.mapIndexed { index, value -> decodeOperation(value.objectOrNull() ?: error("operations[$index]")) } ?: error("Missing operations")
        val estimate = root["estimatedResult"]?.objectOrNull()?.let { EstimatedEditResult(DurationUs(ms(it, "currentDurationMs") * 1_000), DurationUs(ms(it, "estimatedDurationMs") * 1_000)) }
        return EditPlan(
            schemaVersion = str("schemaVersion"), id = EditPlanId(str("id")), previousPlanId = root["previousPlanId"]?.stringOrNull()?.let(::EditPlanId),
            projectId = ProjectId(str("projectId")), sequenceId = SequenceId(str("sequenceId")), baseProjectRevision = long("baseProjectRevision"),
            title = str("title"), summary = str("summary"), assumptions = strings(root["assumptions"]), operations = operations,
            estimatedResult = estimate, warnings = strings(root["warnings"]), confidence = root["confidence"]?.numberOrNull(),
            requiresUserApproval = root["requiresUserApproval"]?.booleanOrNull() ?: true,
        )
    }

    private fun decodeOperation(obj: JsonObject): EditOperation {
        fun s(name: String) = obj[name]?.stringOrNull() ?: error("Missing operation.$name")
        fun n(name: String) = obj[name]?.numberOrNull() ?: error("Missing operation.$name")
        fun clip(name: String = "clipId") = ClipId(s(name))
        fun track(name: String = "trackId") = TrackId(s(name))
        fun range(prefix: String = "source") = TimeRangeUs(TimeUs(n("${prefix}StartMs").toLong() * 1_000), TimeUs(n("${prefix}EndMs").toLong() * 1_000))
        return when (s("type")) {
            "TRIM_CLIP" -> EditOperation.TrimClip(clip(), range())
            "SPLIT_CLIP" -> EditOperation.SplitClip(clip(), TimeUs(n("atMs").toLong() * 1_000), clip("leftClipId"), clip("rightClipId"))
            "REMOVE_RANGE" -> EditOperation.RemoveRange(clip(), range(""), obj["leftClipId"]?.stringOrNull()?.let(::ClipId), obj["rightClipId"]?.stringOrNull()?.let(::ClipId), s("reason"))
            "REMOVE_CLIP" -> EditOperation.RemoveClip(clip(), s("reason"))
            "MOVE_CLIP" -> EditOperation.MoveClip(clip(), track("targetTrackId"), n("targetIndex").toInt())
            "INSERT_RANGE", "INSERT_CLIP" -> EditOperation.InsertRange(SourceId(s("sourceId")), range(), clip("newClipId"), track("targetTrackId"), TimeUs(n("timelineStartMs").toLong() * 1_000))
            "REPLACE_WITH_TAKE" -> EditOperation.ReplaceWithTake(clip("oldClipId"), SourceId(s("newSourceId")), range())
            "CHANGE_SPEED" -> EditOperation.ChangeSpeed(clip(), n("speed"), obj["preservePitch"]?.booleanOrNull() ?: true)
            "SET_CROP" -> EditOperation.SetCrop(clip(), s("aspectRatio"), CropMode.valueOf(s("mode")))
            "SET_TRANSFORM" -> EditOperation.SetTransform(clip(), transform(obj["transform"]?.objectOrNull() ?: error("transform")))
            "ADD_ZOOM" -> EditOperation.AddZoom(clip(), range(""), n("scaleFrom").toFloat(), n("scaleTo").toFloat())
            "ADD_TEXT" -> EditOperation.AddText(clip("id"), track(), range(""), s("text"), s("styleId"))
            "UPDATE_TEXT" -> EditOperation.UpdateText(clip("id"), s("text"))
            "REMOVE_OVERLAY" -> EditOperation.RemoveOverlay(clip("id"))
            "ADD_IMAGE_OVERLAY" -> EditOperation.AddImageOverlay(clip("id"), AssetId(s("assetId")), track(), range(""), obj["transform"]?.objectOrNull()?.let(::creativeTransform) ?: CreativeTransform(), obj["opacity"]?.numberOrNull()?.toFloat() ?: 1f, obj["zIndex"]?.numberOrNull()?.toInt() ?: 60)
            "SET_OVERLAY_TRANSFORM" -> EditOperation.SetOverlayTransform(clip("id"), creativeTransform(obj["transform"]?.objectOrNull() ?: error("transform")))
            "ADD_CAPTIONS" -> EditOperation.AddCaptions(track(), s("transcriptId"), s("styleId"), emptyList())
            "REGENERATE_CAPTIONS" -> EditOperation.RegenerateCaptions(track(), s("transcriptId"), s("styleId"), emptyList())
            "UPDATE_CAPTION_STYLE" -> EditOperation.UpdateCaptionStyle(s("styleId"), n("wordsPerChunk").toInt(), CaptionPosition.valueOf(s("position")), n("fontScale").toFloat())
            "ADD_AUDIO" -> EditOperation.AddAudio(clip("id"), AssetId(s("assetId")), track(), TimeUs(n("startMs").toLong() * 1_000), DurationUs(n("durationMs").toLong() * 1_000), n("volume").toFloat())
            "REMOVE_AUDIO" -> EditOperation.RemoveAudio(clip())
            "SET_AUDIO_GAIN" -> EditOperation.SetAudioGain(clip(), n("gainDb").toFloat())
            "SET_DUCKING" -> EditOperation.SetDucking(track(), DuckingSettings(DuckingMode.valueOf(s("mode")), obj["reductionDb"]?.numberOrNull()?.toFloat() ?: -12f, obj["attackMs"]?.numberOrNull()?.toLong() ?: 120, obj["releaseMs"]?.numberOrNull()?.toLong() ?: 350, obj["minimumSpeechDurationMs"]?.numberOrNull()?.toLong() ?: 180))
            "ADD_FADE" -> EditOperation.AddFade(clip(), FadeType.valueOf(s("fadeType")), DurationUs(n("durationMs").toLong() * 1_000))
            "APPLY_COLOR_ADJUSTMENT" -> EditOperation.ApplyColorAdjustment(clip(), n("brightness").toFloat(), n("contrast").toFloat(), n("saturation").toFloat())
            "ADD_EFFECT" -> EditOperation.AddEffect(clip(), EffectId(s("effectId")), EffectType.valueOf(s("effectType")), numberMap(obj["parameters"]), obj["startMs"]?.numberOrNull()?.let { start -> TimeRangeUs(TimeUs(start.toLong() * 1_000), TimeUs(n("endMs").toLong() * 1_000)) })
            "UPDATE_EFFECT" -> EditOperation.UpdateEffect(clip(), EffectId(s("effectId")), numberMap(obj["parameters"]))
            "REMOVE_EFFECT" -> EditOperation.RemoveEffect(clip(), EffectId(s("effectId")))
            "ADD_TRANSITION" -> EditOperation.AddTransition(CreativeTransition(TransitionId(s("id")), CreativeTransitionType.valueOf(s("transitionType")), n("durationMs").toLong(), clip("fromClipId"), clip("toClipId"), numberMap(obj["parameters"])))
            "REMOVE_TRANSITION" -> EditOperation.RemoveTransition(TransitionId(s("transitionId")))
            "SET_PROJECT_ASPECT_RATIO" -> EditOperation.SetProjectAspectRatio(n("width").toInt(), n("height").toInt())
            "SET_PROJECT_DURATION_TARGET" -> EditOperation.SetDurationTarget(DurationUs(n("durationMs").toLong() * 1_000), obj["tolerancePercent"]?.numberOrNull() ?: 5.0)
            else -> error("Unknown operation type ${s("type")}")
        }
    }
    private fun transform(obj: JsonObject): TransformNode = TransformNode(
        positionX = obj["positionX"]?.numberOrNull()?.toFloat() ?: 0.5f, positionY = obj["positionY"]?.numberOrNull()?.toFloat() ?: 0.5f,
        scaleX = obj["scaleX"]?.numberOrNull()?.toFloat() ?: 1f, scaleY = obj["scaleY"]?.numberOrNull()?.toFloat() ?: 1f,
        rotationDegrees = obj["rotationDegrees"]?.numberOrNull()?.toFloat() ?: 0f,
        cropLeft = obj["cropLeft"]?.numberOrNull()?.toFloat() ?: 0f, cropTop = obj["cropTop"]?.numberOrNull()?.toFloat() ?: 0f,
        cropRight = obj["cropRight"]?.numberOrNull()?.toFloat() ?: 1f, cropBottom = obj["cropBottom"]?.numberOrNull()?.toFloat() ?: 1f,
    )
    private fun creativeTransform(obj: JsonObject): CreativeTransform = CreativeTransform(
        positionX = obj["positionX"]?.numberOrNull()?.toFloat() ?: 0.5f,
        positionY = obj["positionY"]?.numberOrNull()?.toFloat() ?: 0.5f,
        scaleX = obj["scaleX"]?.numberOrNull()?.toFloat() ?: 1f,
        scaleY = obj["scaleY"]?.numberOrNull()?.toFloat() ?: 1f,
        rotationDegrees = obj["rotationDegrees"]?.numberOrNull()?.toFloat() ?: 0f,
        anchorX = obj["anchorX"]?.numberOrNull()?.toFloat() ?: 0.5f,
        anchorY = obj["anchorY"]?.numberOrNull()?.toFloat() ?: 0.5f,
        opacity = obj["opacity"]?.numberOrNull()?.toFloat() ?: 1f,
    )
    private fun numberMap(value: JsonValue?): Map<String, Double> = value?.objectOrNull()?.mapNotNull { (key, item) -> item.numberOrNull()?.let { key to it } }?.toMap().orEmpty()
    private fun strings(value: JsonValue?) = value?.arrayOrNull()?.mapNotNull { it.stringOrNull() }.orEmpty()
    private fun ms(obj: JsonObject, name: String) = obj[name]?.numberOrNull()?.toLong() ?: error("Missing $name")
}
