package com.moatazvid.ai.provider

import kotlinx.coroutines.flow.Flow

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }
sealed interface LlmContentPart {
    data class Text(val text: String) : LlmContentPart
    data class ImageReference(val uri: String, val mimeType: String, val width: Int?, val height: Int?) : LlmContentPart
}
data class LlmMessage(val role: LlmRole, val content: List<LlmContentPart>, val toolCallId: String? = null)
data class LlmRequest(
    val requestId: RequestId,
    val model: String,
    val messages: List<LlmMessage>,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition> = emptyList(),
    val responseFormat: ResponseFormat = ResponseFormat.Text,
) {
    init { require(model.isNotBlank()); temperature?.let { require(it in 0.0..2.0) }; maxOutputTokens?.let { require(it > 0) } }
}

sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data object Json : ResponseFormat
    data class JsonSchema(val name: String, val schema: JsonObject, val strict: Boolean) : ResponseFormat
}
data class LlmToolCall(val id: String, val name: String, val argumentsJson: String)
data class LlmUsage(val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long? = null)
data class LlmResponse(val requestId: RequestId, val providerId: ProviderId, val model: String, val text: String, val toolCalls: List<LlmToolCall>, val usage: LlmUsage?, val rawResponseId: String?)

sealed interface LlmStreamEvent {
    data class Started(val requestId: RequestId) : LlmStreamEvent
    data class TextDelta(val text: String) : LlmStreamEvent
    data class ReasoningDelta(val text: String) : LlmStreamEvent
    data class ToolCallStarted(val id: String, val name: String) : LlmStreamEvent
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : LlmStreamEvent
    data class ToolCallCompleted(val call: LlmToolCall) : LlmStreamEvent
    data class Usage(val usage: LlmUsage) : LlmStreamEvent
    data class Completed(val responseId: String?) : LlmStreamEvent
    data class Error(val error: LlmError) : LlmStreamEvent
}

data class ConnectionTestResult(
    val connected: Boolean, val providerId: ProviderId, val modelsEndpoint: TriState,
    val streaming: TriState, val structuredOutput: TriState, val vision: TriState,
    val latencyMs: Long, val error: LlmError? = null,
)

interface LlmProvider {
    val profile: ProviderProfile
    suspend fun listModels(): LlmResult<List<ModelDescriptor>>
    suspend fun testConnection(): ConnectionTestResult
    suspend fun complete(request: LlmRequest): LlmResult<LlmResponse>
    fun stream(request: LlmRequest): Flow<LlmStreamEvent>
    suspend fun <T> invokeStructured(request: StructuredRequest<T>): LlmResult<T>
    suspend fun invokeWithTools(request: LlmRequest, executor: ToolExecutor): LlmResult<LlmResponse>
    suspend fun getCapabilities(model: String?): ProviderCapabilities
    suspend fun cancel(requestId: RequestId): Boolean
}

interface LocalLlmProvider : LlmProvider { val runtimeName: String }
sealed interface LlmResult<out T> { data class Success<T>(val value: T) : LlmResult<T>; data class Failure(val error: LlmError) : LlmResult<Nothing> }
