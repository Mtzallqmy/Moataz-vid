package com.moatazvid.ai.provider

data class StructuredRequest<T>(
    val request: LlmRequest,
    val schemaName: String,
    val schema: JsonObject,
    val strict: Boolean = true,
    val decoder: (String) -> T,
    val validator: (T) -> List<String> = { emptyList() },
)

enum class StructuredStrategy { NATIVE_JSON_SCHEMA, RESPONSE_FORMAT_JSON_SCHEMA, JSON_MODE, PROMPTED_JSON }
class StructuredStrategySelector {
    fun select(capabilities: CapabilitySet): StructuredStrategy = when {
        capabilities.structuredOutput == TriState.YES -> StructuredStrategy.RESPONSE_FORMAT_JSON_SCHEMA
        capabilities.jsonMode == TriState.YES -> StructuredStrategy.JSON_MODE
        else -> StructuredStrategy.PROMPTED_JSON
    }
}

data class ToolDefinition(val name: String, val description: String, val parameters: JsonObject) {
    init { require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))); require(description.length <= 1_024) }
}
data class ToolResult(val callId: String, val content: String, val isError: Boolean = false)
fun interface ToolExecutor { suspend fun execute(call: LlmToolCall): ToolResult }

interface TokenBudgetEstimator {
    fun estimate(request: LlmRequest, model: ModelDescriptor?): TokenBudget
}
data class TokenBudget(val estimatedInputTokens: Long, val requestedOutputTokens: Long, val contextLimit: Long?, val fits: Boolean)
class ConservativeTokenBudgetEstimator : TokenBudgetEstimator {
    override fun estimate(request: LlmRequest, model: ModelDescriptor?): TokenBudget {
        val characters = request.messages.sumOf { message -> message.content.sumOf { (it as? LlmContentPart.Text)?.text?.length ?: 0 } }
        val input = (characters / 2.5).toLong() + request.tools.size * 180L
        val output = request.maxOutputTokens?.toLong() ?: 1_024
        val limit = model?.contextLength
        return TokenBudget(input, output, limit, limit == null || input + output <= (limit * 0.9).toLong())
    }
}

data class UsageRecord(
    val providerId: ProviderId, val model: String, val inputTokens: Long, val outputTokens: Long,
    val cachedTokens: Long?, val requestType: String, val timestampEpochMs: Long, val estimatedCost: Double?,
)
