package com.moatazvid.ai.provider

enum class CostPreference { LOWEST, BALANCED, QUALITY }
data class TaskRequirements(
    val needsTools: Boolean = false, val needsStructured: Boolean = false, val needsVision: Boolean = false,
    val minimumContext: Long = 0, val streamingPreferred: Boolean = false, val costPreference: CostPreference? = null,
    val localOnly: Boolean = false,
)
data class ResolvedModel(val provider: LlmProvider, val model: ModelDescriptor, val assignment: ModelAssignment?)
enum class FallbackPolicy { OFF, ASK, AUTO_FOR_SAFE_TASKS }
data class FallbackDecision(val candidates: List<ResolvedModel>, val requiresApproval: Boolean)

class ProviderRouter(
    private val providers: () -> Collection<LlmProvider>,
    private val registry: ModelRegistry,
    private val assignments: () -> List<ModelAssignment>,
    private val networkAvailable: () -> Boolean,
) {
    suspend fun resolve(requirements: TaskRequirements, role: ModelRole): LlmResult<ResolvedModel> {
        val assignment = assignments().firstOrNull { it.role == role }
        val ordered = providers().filter { it.profile.enabled }
            .filter { !requirements.localOnly || it.profile.type == ProviderType.LOCAL }
            .filter { networkAvailable() || it.profile.type == ProviderType.LOCAL }
            .sortedWith(compareByDescending<LlmProvider> { it.profile.id == assignment?.providerId }.thenBy { it.profile.priority })
        for (provider in ordered) {
            val models = when (val result = registry.models(provider.profile.id)) { is LlmResult.Success -> result.value; is LlmResult.Failure -> continue }
            val chosen = models.firstOrNull { model ->
                (assignment == null || assignment.providerId != provider.profile.id || model.id == assignment.modelId) &&
                    (model.contextLength == null || model.contextLength >= requirements.minimumContext) &&
                    (!requirements.needsTools || model.capabilities.values.tools == TriState.YES) &&
                    (!requirements.needsStructured || model.capabilities.values.structuredOutput == TriState.YES || model.capabilities.values.jsonMode == TriState.YES) &&
                    (!requirements.needsVision || model.capabilities.values.vision == TriState.YES)
            }
            if (chosen != null) return LlmResult.Success(ResolvedModel(provider, chosen, assignment))
        }
        return LlmResult.Failure(LlmError.UnsupportedCapability(ProviderId("router"), null, "No enabled model satisfies task requirements"))
    }

    fun fallback(primary: ResolvedModel, alternatives: List<ResolvedModel>, policy: FallbackPolicy, safeNonExecutingTask: Boolean): FallbackDecision = when (policy) {
        FallbackPolicy.OFF -> FallbackDecision(listOf(primary), false)
        FallbackPolicy.ASK -> FallbackDecision(listOf(primary) + alternatives, alternatives.isNotEmpty())
        FallbackPolicy.AUTO_FOR_SAFE_TASKS -> FallbackDecision(listOf(primary) + if (safeNonExecutingTask) alternatives else emptyList(), !safeNonExecutingTask && alternatives.isNotEmpty())
    }
}
