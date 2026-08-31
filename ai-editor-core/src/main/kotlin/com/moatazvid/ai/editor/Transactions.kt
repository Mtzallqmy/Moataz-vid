package com.moatazvid.ai.editor

import com.moatazvid.ai.provider.ProviderId
import com.moatazvid.core.*
import com.moatazvid.storage.StorageError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PendingEditStatus { PROPOSED, SIMULATED, READY, APPLIED, REJECTED, INVALID, SUPERSEDED, STALE }
data class PendingEditTransaction(
    val id: PendingEditId,
    val projectId: ProjectId,
    val baseRevision: Long,
    val editPlan: EditPlan,
    val simulationResult: SimulationResult,
    val createdAtEpochMs: Long,
    val providerId: ProviderId?,
    val model: String?,
    val status: PendingEditStatus,
)

enum class AiApprovalMode { ALWAYS_CONFIRM, CONFIRM_LARGE_EDITS, AUTO_SAFE_EDITS }
data class AiApprovalPolicy(val mode: AiApprovalMode = AiApprovalMode.ALWAYS_CONFIRM, val largeRemovalRatio: Double = 0.20) {
    fun requiresApproval(plan: EditPlan, simulation: SimulationResult): Boolean = when (mode) {
        AiApprovalMode.ALWAYS_CONFIRM -> true
        AiApprovalMode.CONFIRM_LARGE_EDITS -> isLarge(plan, simulation)
        AiApprovalMode.AUTO_SAFE_EDITS -> plan.operations.any { it !is EditOperation.SetAudioGain && it !is EditOperation.UpdateCaptionStyle }
    }
    private fun isLarge(plan: EditPlan, simulation: SimulationResult): Boolean {
        val diff = simulation.diff ?: return true
        val removedRatio = if (diff.beforeDuration.value == 0L) 0.0 else (diff.beforeDuration.value - diff.afterDuration.value).coerceAtLeast(0).toDouble() / diff.beforeDuration.value
        return removedRatio > largeRemovalRatio || plan.operations.any { it is EditOperation.MoveClip || it is EditOperation.ReplaceWithTake || it is EditOperation.RemoveClip }
    }
}

data class AppliedEditTransaction(
    val id: TransactionId, val projectId: ProjectId, val sequenceId: SequenceId, val title: String,
    val origin: EditOrigin, val baseRevision: Long, val resultRevision: Long, val planId: EditPlanId?, val diff: EditDiff,
    val createdAtEpochMs: Long,
)
enum class EditOrigin { MANUAL, AI }

sealed interface CommitResult { data class Success(val project: AiEditableProject, val transaction: AppliedEditTransaction) : CommitResult; data class Failure(val error: StorageError) : CommitResult }

interface AiTimelineStore {
    suspend fun load(projectId: ProjectId): AiEditableProject?
    suspend fun commitAtomic(expectedRevision: Long, newProject: AiEditableProject, transaction: AppliedEditTransaction): CommitResult
    suspend fun undo(sequenceId: SequenceId, aiOnly: Boolean = false, transactionId: TransactionId? = null): CommitResult
    suspend fun redo(sequenceId: SequenceId): CommitResult
    suspend fun history(sequenceId: SequenceId): List<AppliedEditTransaction>
}

class InMemoryAiTimelineStore(initial: AiEditableProject) : AiTimelineStore {
    private val mutex = Mutex(); private var state = initial
    private val undo = mutableListOf<Pair<AiEditableProject, AppliedEditTransaction>>()
    private val redo = mutableListOf<Pair<AiEditableProject, AppliedEditTransaction>>()
    override suspend fun load(projectId: ProjectId) = mutex.withLock { state.takeIf { it.snapshot.project.id == projectId } }
    override suspend fun commitAtomic(expectedRevision: Long, newProject: AiEditableProject, transaction: AppliedEditTransaction): CommitResult = mutex.withLock {
        if (state.revision != expectedRevision) return@withLock CommitResult.Failure(StorageError.Conflict(expectedRevision, state.revision))
        undo += state to transaction
        state = newProject.withRevision(transaction.resultRevision); redo.clear()
        CommitResult.Success(state, transaction)
    }
    override suspend fun undo(sequenceId: SequenceId, aiOnly: Boolean, transactionId: TransactionId?): CommitResult = mutex.withLock {
        if (state.snapshot.sequence.id != sequenceId) return@withLock CommitResult.Failure(StorageError.NotFound("sequence", sequenceId.value))
        val index = undo.indexOfLast { (_, tx) -> (!aiOnly || tx.origin == EditOrigin.AI) && (transactionId == null || tx.id == transactionId) }
        if (index < 0 || index != undo.lastIndex) return@withLock CommitResult.Failure(StorageError.Conflict(state.revision, state.revision))
        val (previous, tx) = undo.removeAt(index); redo += state to tx; state = previous
        CommitResult.Success(state, tx)
    }
    override suspend fun redo(sequenceId: SequenceId): CommitResult = mutex.withLock {
        val entry = redo.removeLastOrNull() ?: return@withLock CommitResult.Failure(StorageError.NotFound("redo", sequenceId.value))
        val current = state; state = entry.first; undo += current to entry.second
        CommitResult.Success(state, entry.second)
    }
    override suspend fun history(sequenceId: SequenceId) = mutex.withLock { undo.map { it.second }.filter { it.sequenceId == sequenceId } }
    private fun AiEditableProject.withRevision(revision: Long): AiEditableProject = copy(snapshot = snapshot.copy(
        project = snapshot.project.copy(timelineRevision = revision, updatedAtEpochMs = System.currentTimeMillis()),
        sequence = snapshot.sequence.copy(revision = revision),
    ))
}

class PendingEditCoordinator(
    private val store: AiTimelineStore,
    private val simulator: EditSimulationEngine = EditSimulationEngine(),
    private val approval: AiApprovalPolicy = AiApprovalPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pending = mutableMapOf<PendingEditId, PendingEditTransaction>()
    suspend fun create(id: PendingEditId, plan: EditPlan, providerId: ProviderId?, model: String?): PendingEditTransaction {
        pending.values.filter { it.projectId == plan.projectId && it.status in setOf(PendingEditStatus.PROPOSED, PendingEditStatus.SIMULATED, PendingEditStatus.READY) }
            .forEach { pending[it.id] = it.copy(status = PendingEditStatus.SUPERSEDED) }
        val project = requireNotNull(store.load(plan.projectId))
        val simulation = simulator.simulate(project, plan)
        val status = if (!simulation.valid) PendingEditStatus.INVALID else PendingEditStatus.READY
        return PendingEditTransaction(id, plan.projectId, plan.baseProjectRevision, plan, simulation, clock(), providerId, model, status).also { pending[id] = it }
    }
    suspend fun get(id: PendingEditId): PendingEditTransaction? {
        val value = pending[id] ?: return null; val current = store.load(value.projectId)
        return if (current != null && current.revision != value.baseRevision && value.status == PendingEditStatus.READY) value.copy(status = PendingEditStatus.STALE).also { pending[id] = it } else value
    }
    suspend fun reject(id: PendingEditId): PendingEditTransaction? = pending[id]?.copy(status = PendingEditStatus.REJECTED)?.also { pending[id] = it }
    suspend fun apply(id: PendingEditId, transactionId: TransactionId): CommitResult {
        val proposal = get(id) ?: return CommitResult.Failure(StorageError.NotFound("pendingEdit", id.value))
        if (proposal.status != PendingEditStatus.READY) return CommitResult.Failure(StorageError.Conflict(proposal.baseRevision, store.load(proposal.projectId)?.revision ?: -1))
        val simulated = proposal.simulationResult.simulatedProject ?: return CommitResult.Failure(StorageError.CorruptState("Missing simulated state"))
        val diff = requireNotNull(proposal.simulationResult.diff)
        val tx = AppliedEditTransaction(transactionId, proposal.projectId, simulated.snapshot.sequence.id, proposal.editPlan.title, EditOrigin.AI,
            proposal.baseRevision, proposal.baseRevision + 1, proposal.editPlan.id, diff, clock())
        return store.commitAtomic(proposal.baseRevision, simulated, tx).also { if (it is CommitResult.Success) pending[id] = proposal.copy(status = PendingEditStatus.APPLIED) }
    }
    fun requiresApproval(id: PendingEditId): Boolean = pending[id]?.let { approval.requiresApproval(it.editPlan, it.simulationResult) } ?: true
}
