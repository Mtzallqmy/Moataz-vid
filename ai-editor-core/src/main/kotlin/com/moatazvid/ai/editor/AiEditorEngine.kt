package com.moatazvid.ai.editor

import com.moatazvid.ai.provider.*
import com.moatazvid.core.*
import com.moatazvid.speech.*
import com.moatazvid.storage.StorageError
import kotlinx.coroutines.CancellationException

enum class AiEditorStage { IDLE, CLASSIFYING, BUILDING_CONTEXT, USING_TOOLS, BUILDING_PLAN, REPAIRING_PLAN, SIMULATING, PLAN_READY, APPLYING, DONE, ERROR, CANCELLED }
data class AiEditorProgress(val stage: AiEditorStage, val userVisibleStatus: String)
sealed interface AiEditorResult {
    data class Analysis(val text: String, val intent: IntentResult) : AiEditorResult
    data class PlanReady(val pending: PendingEditTransaction, val intent: IntentResult) : AiEditorResult
    data class ConstraintSaved(val constraint: ProjectConstraint) : AiEditorResult
    data class Applied(val result: CommitResult.Success) : AiEditorResult
    data class HistoryChanged(val result: CommitResult.Success, val undo: Boolean) : AiEditorResult
    data class Clarification(val question: String) : AiEditorResult
    data class Failure(val messageKey: String, val detail: String? = null) : AiEditorResult
    data object Cancelled : AiEditorResult
}

interface AiEditorDataSource : AiProjectReadTools {
    suspend fun project(projectId: ProjectId): AiEditableProject?
    suspend fun silence(projectId: ProjectId): List<SilenceRange>
    suspend fun transcriptWords(projectId: ProjectId): List<TranscriptWord>
    suspend fun takeGroups(projectId: ProjectId): List<TakeCandidateGroup>
    suspend fun resolvePreservedTopic(projectId: ProjectId, userText: String): List<ProtectedRange>
    suspend fun saveConstraint(constraint: ProjectConstraint)
}

data class EditingModel(val provider: LlmProvider, val descriptor: ModelDescriptor)
fun interface EditingModelResolver { suspend fun resolve(requirements: TaskRequirements, role: ModelRole): LlmResult<EditingModel> }

interface EditPlanProposalClient {
    suspend fun propose(model: EditingModel, context: AiTaskContext, previous: EditPlan? = null, feedback: String? = null): LlmResult<EditPlan>
    suspend fun repair(model: EditingModel, invalid: EditPlan, errors: List<PlanValidationError>, validIds: Set<String>, attempt: Int): LlmResult<EditPlan>
    suspend fun analyze(model: EditingModel, context: AiTaskContext): LlmResult<String>
}

data class AiEditorPolicy(val maxRepairAttempts: Int = 2, val contextBudget: ContextBudget = ContextBudget(32_000, 4_000, 1_500, 2_000))

class AiEditorEngine(
    private val data: AiEditorDataSource,
    private val store: AiTimelineStore,
    private val contextBuilder: AiContextBuilder,
    private val modelResolver: EditingModelResolver,
    private val proposalClient: EditPlanProposalClient,
    private val ids: EditorIdFactory = SequentialEditorIdFactory(),
    private val validator: EditPlanValidator = EditPlanValidator(),
    private val pending: PendingEditCoordinator = PendingEditCoordinator(store),
    private val intents: ArabicIntentClassifier = ArabicIntentClassifier(),
    private val policy: AiEditorPolicy = AiEditorPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun analyzeMessage(projectId: ProjectId, message: String, currentPending: PendingEditTransaction? = null, onProgress: (AiEditorProgress) -> Unit = {}): AiEditorResult {
        return try {
            onProgress(AiEditorProgress(AiEditorStage.CLASSIFYING, "أفهم طلبك…"))
            val intent = intents.classify(message, currentPending?.status == PendingEditStatus.READY)
            val project = data.project(projectId) ?: return AiEditorResult.Failure("project.not_found")
            when (intent.intent) {
                AiIntent.UNDO_REQUEST -> undoLastAiEdit(project.snapshot.sequence.id)
                AiIntent.REDO_REQUEST -> redoLastAiEdit(project.snapshot.sequence.id)
                AiIntent.EXPLAIN_EDIT -> currentPending?.simulationResult?.diff?.let { AiEditorResult.Analysis(it.userSummary, intent) }
                    ?: store.history(project.snapshot.sequence.id).lastOrNull()?.let { AiEditorResult.Analysis(it.diff.userSummary, intent) }
                    ?: AiEditorResult.Analysis("لا يوجد تعديل سابق لشرحه.", intent)
                AiIntent.PROJECT_CONSTRAINT -> saveConstraint(project, message, intent)
                AiIntent.FIND_CONTENT, AiIntent.ANALYZE_PROJECT -> analyze(project, message, intent, onProgress)
                AiIntent.CLARIFICATION_REQUIRED -> AiEditorResult.Clarification("ما الجزء أو النتيجة التي تريد تعديلها؟")
                else -> proposeEdit(project, message, intent, currentPending, onProgress)
            }
        } catch (_: CancellationException) { onProgress(AiEditorProgress(AiEditorStage.CANCELLED, "أُلغي الطلب")); AiEditorResult.Cancelled }
        catch (failure: Throwable) { onProgress(AiEditorProgress(AiEditorStage.ERROR, "تعذر إكمال الطلب")); AiEditorResult.Failure("ai.editor.failure", failure.message) }
    }

    suspend fun buildContext(projectId: ProjectId, message: String, intent: IntentResult): AiTaskContext {
        val project = requireNotNull(data.project(projectId)); return contextBuilder.build(projectId, project.revision, message, intent, policy.contextBudget)
    }

    suspend fun proposeEdit(project: AiEditableProject, message: String, intent: IntentResult = intents.classify(message), previous: PendingEditTransaction? = null,
        onProgress: (AiEditorProgress) -> Unit = {}): AiEditorResult {
        deterministicPlan(project, message)?.let { plan ->
            onProgress(AiEditorProgress(AiEditorStage.SIMULATING, "أحاكي التعديلات…"))
            return pending.create(ids.pending(), plan, null, "local-deterministic").let { if (it.status == PendingEditStatus.READY) AiEditorResult.PlanReady(it, intent) else AiEditorResult.Failure("edit_plan.invalid") }
        }
        onProgress(AiEditorProgress(AiEditorStage.BUILDING_CONTEXT, "أجمع بيانات المشروع اللازمة…"))
        val context = contextBuilder.build(project.snapshot.project.id, project.revision, message, intent, policy.contextBudget)
        val requirements = TaskRequirements(needsStructured = true, needsVision = intent.intent == AiIntent.VISUAL_EDIT && (message.contains("أجمل") || message.contains("أفضل لقطة")), minimumContext = context.estimatedTokens + 4_000)
        val model = when (val resolved = modelResolver.resolve(requirements, ModelRole.EDITING)) {
            is LlmResult.Success -> resolved.value
            is LlmResult.Failure -> return AiEditorResult.Failure("ai.provider_unavailable", resolved.error.userMessageKey)
        }
        onProgress(AiEditorProgress(AiEditorStage.BUILDING_PLAN, "أجهز خطة التعديل…"))
        var plan = when (val proposed = proposalClient.propose(model, context, previous?.editPlan, if (previous != null) message else null)) {
            is LlmResult.Success -> proposed.value.copy(previousPlanId = previous?.editPlan?.id, baseProjectRevision = project.revision)
            is LlmResult.Failure -> return AiEditorResult.Failure(proposed.error.userMessageKey)
        }
        var validation = validator.validate(plan, project)
        repeat(policy.maxRepairAttempts) { attempt ->
            if (validation.valid) return@repeat
            onProgress(AiEditorProgress(AiEditorStage.REPAIRING_PLAN, "أصحح خطة غير صالحة…"))
            val validIds = project.snapshot.items.map { it.id.value }.toSet() + project.snapshot.tracks.map { it.id.value } + project.sources.map { it.id.value }
            plan = when (val repaired = proposalClient.repair(model, plan, validation.errors, validIds, attempt + 1)) {
                is LlmResult.Success -> repaired.value.copy(previousPlanId = previous?.editPlan?.id, baseProjectRevision = project.revision)
                is LlmResult.Failure -> return AiEditorResult.Failure(repaired.error.userMessageKey)
            }
            validation = validator.validate(plan, project)
        }
        if (!validation.valid) return AiEditorResult.Failure("edit_plan.invalid_after_repair", validation.errors.joinToString { it.code })
        onProgress(AiEditorProgress(AiEditorStage.SIMULATING, "أحاكي التعديلات…"))
        val created = pending.create(ids.pending(), plan, model.provider.profile.id, model.descriptor.id)
        return if (created.status == PendingEditStatus.READY) { onProgress(AiEditorProgress(AiEditorStage.PLAN_READY, "الخطة جاهزة للمعاينة")); AiEditorResult.PlanReady(created, intent) }
        else AiEditorResult.Failure("edit_plan.simulation_failed", created.simulationResult.unsupportedOperations.joinToString())
    }

    suspend fun revisePendingEdit(projectId: ProjectId, pendingId: PendingEditId, feedback: String, onProgress: (AiEditorProgress) -> Unit = {}): AiEditorResult {
        val previous = pending.get(pendingId) ?: return AiEditorResult.Failure("pending.not_found")
        val project = data.project(projectId) ?: return AiEditorResult.Failure("project.not_found")
        if (project.revision != previous.baseRevision) return AiEditorResult.Failure("pending.stale")
        return proposeEdit(project, feedback, intents.classify(feedback, true), previous, onProgress)
    }
    suspend fun explainEdit(pendingId: PendingEditId): AiEditorResult = pending.get(pendingId)?.simulationResult?.diff?.let { AiEditorResult.Analysis(it.userSummary, IntentResult(AiIntent.EXPLAIN_EDIT, 1.0, deterministic = true)) }
        ?: AiEditorResult.Failure("pending.not_found")
    suspend fun applyPendingEdit(pendingId: PendingEditId): AiEditorResult = when (val result = pending.apply(pendingId, ids.transaction())) {
        is CommitResult.Success -> AiEditorResult.Applied(result); is CommitResult.Failure -> AiEditorResult.Failure("apply.failed", result.error.toString())
    }
    suspend fun cancelPendingEdit(pendingId: PendingEditId): AiEditorResult = pending.reject(pendingId)?.let { AiEditorResult.Analysis("تم رفض الخطة ولم يتغير المشروع.", IntentResult(AiIntent.EDIT_PROJECT, 1.0, deterministic = true)) }
        ?: AiEditorResult.Failure("pending.not_found")
    suspend fun undoLastAiEdit(sequenceId: SequenceId): AiEditorResult = when (val result = store.undo(sequenceId, aiOnly = true)) {
        is CommitResult.Success -> AiEditorResult.HistoryChanged(result, true); is CommitResult.Failure -> AiEditorResult.Failure("undo.unavailable", result.error.toString())
    }
    suspend fun redoLastAiEdit(sequenceId: SequenceId): AiEditorResult = when (val result = store.redo(sequenceId)) {
        is CommitResult.Success -> AiEditorResult.HistoryChanged(result, false); is CommitResult.Failure -> AiEditorResult.Failure("redo.unavailable", result.error.toString())
    }

    private suspend fun deterministicPlan(project: AiEditableProject, message: String): EditPlan? {
        val lower = message.lowercase()
        if ("صمت" in lower || "silence" in lower) {
            val threshold = Regex("(\\d+(?:[.,]\\d+)?)\\s*(ثانية|ثوان|second|sec)").find(lower)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            val commandPolicy = threshold?.let { SilenceEditPolicy(minimumDetectedSilence = DurationUs((it * 1_000_000).toLong())) } ?: SilenceEditPolicy()
            return SilenceCommandPlanner(ids, commandPolicy).plan(project, data.silence(project.snapshot.project.id), data.transcriptWords(project.snapshot.project.id))
        }
        if (lower.contains("أفضل محاولة") || lower.contains("أفضل take") || lower.contains("best take")) return BestTakePlanner(ids).plan(project, data.takeGroups(project.snapshot.project.id))
        if (lower.contains("9:16") && !lower.contains("ريل") && !lower.contains("reel")) return EditPlan(id = ids.plan(), projectId = project.snapshot.project.id,
            sequenceId = project.snapshot.sequence.id, baseProjectRevision = project.revision, title = "تحويل المشروع إلى 9:16", summary = "تغيير Canvas إلى فيديو عمودي.",
            operations = listOf(EditOperation.SetProjectAspectRatio(1080, 1920)), estimatedResult = EstimatedEditResult(project.duration, project.duration))
        return null
    }

    private suspend fun analyze(project: AiEditableProject, message: String, intent: IntentResult, progress: (AiEditorProgress) -> Unit): AiEditorResult {
        val context = contextBuilder.build(project.snapshot.project.id, project.revision, message, intent, policy.contextBudget)
        val model = when (val result = modelResolver.resolve(TaskRequirements(minimumContext = context.estimatedTokens + 1_000), ModelRole.FAST)) {
            is LlmResult.Success -> result.value; is LlmResult.Failure -> return AiEditorResult.Failure("ai.provider_unavailable")
        }
        progress(AiEditorProgress(AiEditorStage.BUILDING_CONTEXT, "أحلل بيانات المشروع…"))
        return when (val result = proposalClient.analyze(model, context)) { is LlmResult.Success -> AiEditorResult.Analysis(result.value, intent); is LlmResult.Failure -> AiEditorResult.Failure(result.error.userMessageKey) }
    }
    private suspend fun saveConstraint(project: AiEditableProject, message: String, intent: IntentResult): AiEditorResult {
        val ranges = data.resolvePreservedTopic(project.snapshot.project.id, message)
        val constraint = ProjectConstraint(ConstraintId("constraint_${clock()}"), project.snapshot.project.id,
            if (ranges.isNotEmpty()) ProjectConstraintType.PRESERVE_RANGE else ProjectConstraintType.PRESERVE_TOPIC, message,
            ranges.firstOrNull()?.sourceId, ranges.firstOrNull()?.sourceRange, ConstraintSource.USER, ConstraintPriority.REQUIRED, "user", clock())
        data.saveConstraint(constraint); return AiEditorResult.ConstraintSaved(constraint)
    }
}
