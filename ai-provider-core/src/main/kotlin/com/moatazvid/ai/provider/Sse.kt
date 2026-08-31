package com.moatazvid.ai.provider

class OpenAiSseParser(private val providerId: ProviderId, private val model: String) {
    private val calls = mutableMapOf<String, Pair<String, StringBuilder>>()
    private var completed = false

    fun accept(line: String): List<LlmStreamEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(':') || !trimmed.startsWith("data:")) return emptyList()
        val data = trimmed.removePrefix("data:").trim()
        if (data == "[DONE]") { completed = true; return finishCalls() + LlmStreamEvent.Completed(null) }
        return try {
            val root = MiniJson.parse(data).objectOrNull() ?: error("SSE data must be object")
            val result = mutableListOf<LlmStreamEvent>()
            val choice = root["choices"]?.arrayOrNull()?.firstOrNull()?.objectOrNull()
            val delta = choice?.get("delta")?.objectOrNull()
            delta?.get("content")?.stringOrNull()?.takeIf(String::isNotEmpty)?.let { result += LlmStreamEvent.TextDelta(it) }
            delta?.get("reasoning")?.stringOrNull()?.takeIf(String::isNotEmpty)?.let { result += LlmStreamEvent.ReasoningDelta(it) }
            delta?.get("tool_calls")?.arrayOrNull()?.forEach { raw ->
                val call = raw.objectOrNull() ?: return@forEach
                val id = call["id"]?.stringOrNull() ?: "index_${call["index"]?.numberOrNull()?.toInt() ?: 0}"
                val function = call["function"]?.objectOrNull()
                val name = function?.get("name")?.stringOrNull()
                if (id !in calls) { calls[id] = (name ?: "") to StringBuilder(); result += LlmStreamEvent.ToolCallStarted(id, name ?: "") }
                function?.get("arguments")?.stringOrNull()?.let { fragment -> calls.getValue(id).second.append(fragment); result += LlmStreamEvent.ToolCallDelta(id, fragment) }
            }
            root["usage"]?.objectOrNull()?.let { usage -> result += LlmStreamEvent.Usage(LlmUsage(
                usage["prompt_tokens"]?.numberOrNull()?.toLong() ?: 0, usage["completion_tokens"]?.numberOrNull()?.toLong() ?: 0,
                usage["cached_tokens"]?.numberOrNull()?.toLong())) }
            result
        } catch (failure: Throwable) { listOf(LlmStreamEvent.Error(LlmError.StreamingFailure(providerId, model, failure.message))) }
    }

    fun finish(): List<LlmStreamEvent> = if (completed) emptyList() else finishCalls() + LlmStreamEvent.Completed(null)
    private fun finishCalls(): List<LlmStreamEvent> = calls.map { (id, pair) -> LlmStreamEvent.ToolCallCompleted(LlmToolCall(id, pair.first, pair.second.toString())) }.also { calls.clear() }
}
