package com.moatazvid.ai.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

enum class RequestEndpoint { RESPONSES, CHAT_COMPLETIONS }
class RequestRouter {
    fun route(capabilities: CapabilitySet, request: LlmRequest): RequestEndpoint =
        if (capabilities.responsesApi == TriState.YES && request.tools.isEmpty()) RequestEndpoint.RESPONSES else RequestEndpoint.CHAT_COMPLETIONS
}

open class OpenAiCompatibleAdapter(
    final override val profile: ProviderProfile,
    private val transport: HttpTransport,
    private val secrets: SecretStore,
    private val staticCapabilities: CapabilitySet,
    private val clock: () -> Long = System::currentTimeMillis,
) : LlmProvider {
    private val cancelled = ConcurrentHashMap.newKeySet<RequestId>()

    override suspend fun listModels(): LlmResult<List<ModelDescriptor>> {
        val request = makeRequest(RequestId("models_${clock()}"), "GET", profile.modelsPath, null, true)
            ?: return LlmResult.Failure(LlmError.Authentication(profile.id, null))
        return when (val result = executeWithRetry(request, null)) {
            is LlmResult.Failure -> result
            is LlmResult.Success -> parseModels(result.value.body)
        }
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val start = clock()
        val models = listModels()
        if (models is LlmResult.Success) return ConnectionTestResult(true, profile.id, TriState.YES, staticCapabilities.streaming,
            staticCapabilities.structuredOutput, staticCapabilities.vision, clock() - start)
        val selected = profile.defaultModel
        if (selected == null) return ConnectionTestResult(false, profile.id, TriState.NO, staticCapabilities.streaming,
            staticCapabilities.structuredOutput, staticCapabilities.vision, clock() - start, (models as LlmResult.Failure).error)
        val tiny = complete(LlmRequest(RequestId("test_${clock()}"), selected,
            listOf(LlmMessage(LlmRole.USER, listOf(LlmContentPart.Text("Reply OK")))), maxOutputTokens = 2))
        return ConnectionTestResult(tiny is LlmResult.Success, profile.id, TriState.NO, staticCapabilities.streaming,
            staticCapabilities.structuredOutput, staticCapabilities.vision, clock() - start, (tiny as? LlmResult.Failure)?.error)
    }

    override suspend fun complete(request: LlmRequest): LlmResult<LlmResponse> {
        val capabilities = getCapabilities(request.model).values
        capabilityError(request, capabilities)?.let { return LlmResult.Failure(it) }
        val endpoint = RequestRouter().route(capabilities, request)
        val body = encodeRequest(request, endpoint)
        val http = makeRequest(request.requestId, "POST", if (endpoint == RequestEndpoint.RESPONSES) profile.responsesPath else profile.chatPath, body, idempotent = request.tools.isEmpty())
            ?: return LlmResult.Failure(LlmError.Authentication(profile.id, request.model))
        return when (val result = executeWithRetry(http, request.model)) {
            is LlmResult.Failure -> result
            is LlmResult.Success -> parseCompletion(request, result.value.body, endpoint)
        }
    }

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        emit(LlmStreamEvent.Started(request.requestId))
        val body = encodeRequest(request.copy(stream = true), RequestEndpoint.CHAT_COMPLETIONS)
        val http = makeRequest(request.requestId, "POST", profile.chatPath, body, idempotent = request.tools.isEmpty())
        if (http == null) { emit(LlmStreamEvent.Error(LlmError.Authentication(profile.id, request.model))); return@flow }
        val parser = OpenAiSseParser(profile.id, request.model)
        transport.stream(http).collect { event -> when (event) {
            is TransportStreamEvent.Data -> parser.accept(event.line).forEach { emit(it) }
            is TransportStreamEvent.Failure -> emit(LlmStreamEvent.Error(LlmError.StreamingFailure(profile.id, request.model, event.detail)))
            TransportStreamEvent.Closed -> parser.finish().forEach { emit(it) }
        } }
    }

    override suspend fun <T> invokeStructured(request: StructuredRequest<T>): LlmResult<T> {
        val capabilities = getCapabilities(request.request.model).values
        val strategy = StructuredStrategySelector().select(capabilities)
        val adapted = when (strategy) {
            StructuredStrategy.NATIVE_JSON_SCHEMA, StructuredStrategy.RESPONSE_FORMAT_JSON_SCHEMA -> request.request.copy(responseFormat = ResponseFormat.JsonSchema(request.schemaName, request.schema, request.strict))
            StructuredStrategy.JSON_MODE -> request.request.copy(responseFormat = ResponseFormat.Json)
            StructuredStrategy.PROMPTED_JSON -> request.request.copy(messages = request.request.messages + LlmMessage(LlmRole.SYSTEM, listOf(LlmContentPart.Text("Return only valid JSON matching schema ${request.schemaName}."))))
        }
        return when (val response = complete(adapted)) {
            is LlmResult.Failure -> response
            is LlmResult.Success -> try {
                val decoded = request.decoder(extractJson(response.value.text))
                val errors = request.validator(decoded)
                if (errors.isEmpty()) LlmResult.Success(decoded) else LlmResult.Failure(LlmError.InvalidStructuredResponse(profile.id, adapted.model, errors.joinToString()))
            } catch (failure: Throwable) { LlmResult.Failure(LlmError.InvalidStructuredResponse(profile.id, adapted.model, failure.message)) }
        }
    }

    override suspend fun invokeWithTools(request: LlmRequest, executor: ToolExecutor): LlmResult<LlmResponse> {
        val first = complete(request)
        if (first !is LlmResult.Success || first.value.toolCalls.isEmpty()) return first
        val results = first.value.toolCalls.map { executor.execute(it) }
        val continuation = request.copy(requestId = RequestId(request.requestId.value + "_tools"), messages = request.messages +
            LlmMessage(LlmRole.ASSISTANT, listOf(LlmContentPart.Text(first.value.text))) +
            results.map { LlmMessage(LlmRole.TOOL, listOf(LlmContentPart.Text(it.content)), it.callId) })
        return complete(continuation)
    }

    override suspend fun getCapabilities(model: String?) = ProviderCapabilities(staticCapabilities, "provider-static+model-metadata")
    override suspend fun cancel(requestId: RequestId): Boolean { cancelled += requestId; return transport.cancel(requestId) }

    private fun capabilityError(request: LlmRequest, capabilities: CapabilitySet): LlmError? = when {
        request.tools.isNotEmpty() && capabilities.tools == TriState.NO -> LlmError.UnsupportedCapability(profile.id, request.model, "tools")
        request.responseFormat is ResponseFormat.JsonSchema && capabilities.structuredOutput == TriState.NO && capabilities.jsonMode == TriState.NO -> null // prompted fallback
        request.messages.any { it.content.any { part -> part is LlmContentPart.ImageReference } } && capabilities.vision != TriState.YES -> LlmError.UnsupportedCapability(profile.id, request.model, "vision")
        else -> null
    }

    private suspend fun makeRequest(id: RequestId, method: String, path: String, body: String?, idempotent: Boolean): HttpRequest? {
        val url = try { BaseUrlNormalizer.resolve(BaseUrlNormalizer.normalize(profile.baseUrl), path) } catch (_: Throwable) { return null }
        val secret = if (profile.authMode == AuthMode.NONE) null else secrets.readSecret(profile.id) ?: return null
        return try {
            val auth = secret?.useValue { AuthHeaderFactory.create(profile, it) } ?: emptyMap()
            HttpRequest(id, method, url, mapOf("Accept" to "application/json", "Content-Type" to "application/json") + profile.customHeaders + auth, body, profile.timeoutMs, idempotent)
        } finally { secret?.close() }
    }

    private suspend fun executeWithRetry(request: HttpRequest, model: String?): LlmResult<HttpResponse> {
        var attempt = 0
        val retry = RetryPolicy(profile.retries)
        while (true) {
            if (request.requestId in cancelled) return LlmResult.Failure(LlmError.Cancelled(profile.id, model))
            when (val result = transport.execute(request)) {
                is TransportResult.Success -> {
                    if (result.value.status in 200..299) return LlmResult.Success(result.value)
                    val retryAfter = result.value.headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value?.toLongOrNull()?.times(1_000)
                    val decision = retry.decide(attempt, result.value.status, false, retryAfter, request.idempotent)
                    if (!decision.retry) return LlmResult.Failure(HttpErrorMapper.map(profile.id, model, result.value.status, result.value.body, retryAfter))
                    delay(decision.delayMs); attempt++
                }
                is TransportResult.Failure -> {
                    if (result.timeout) return LlmResult.Failure(LlmError.Timeout(profile.id, model))
                    val decision = retry.decide(attempt, null, true, null, request.idempotent)
                    if (!decision.retry) return LlmResult.Failure(LlmError.NetworkUnavailable(profile.id, result.detail))
                    delay(decision.delayMs); attempt++
                }
            }
        }
    }

    private fun encodeRequest(request: LlmRequest, endpoint: RequestEndpoint): String {
        val messages = request.messages.map { message -> MiniJson.obj(
            "role" to MiniJson.str(message.role.name.lowercase()),
            "content" to MiniJson.str(message.content.filterIsInstance<LlmContentPart.Text>().joinToString("\n") { it.text }),
            "tool_call_id" to MiniJson.str(message.toolCallId),
        ) }
        val responseFormat = when (val format = request.responseFormat) {
            ResponseFormat.Text -> null
            ResponseFormat.Json -> MiniJson.obj("type" to MiniJson.str("json_object"))
            is ResponseFormat.JsonSchema -> MiniJson.obj("type" to MiniJson.str("json_schema"), "json_schema" to MiniJson.obj(
                "name" to MiniJson.str(format.name), "strict" to MiniJson.bool(format.strict), "schema" to JsonValue.ObjectValue(format.schema)))
        }
        val tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool -> MiniJson.obj("type" to MiniJson.str("function"), "function" to MiniJson.obj(
            "name" to MiniJson.str(tool.name), "description" to MiniJson.str(tool.description), "parameters" to JsonValue.ObjectValue(tool.parameters))) }
        val core = linkedMapOf<String, JsonValue>(
            "model" to JsonValue.StringValue(request.model),
            (if (endpoint == RequestEndpoint.RESPONSES) "input" else "messages") to JsonValue.ArrayValue(messages),
            "stream" to JsonValue.BooleanValue(request.stream),
        )
        request.temperature?.let { core["temperature"] = JsonValue.NumberValue(it) }
        request.maxOutputTokens?.let { core[if (endpoint == RequestEndpoint.RESPONSES) "max_output_tokens" else "max_tokens"] = JsonValue.NumberValue(it.toDouble()) }
        responseFormat?.let { core["response_format"] = it }
        tools?.let { core["tools"] = JsonValue.ArrayValue(it) }
        profile.extraBody.forEach { (key, value) -> if (key !in setOf("model", "messages", "input", "stream", "tools", "response_format")) core[key] = value }
        return MiniJson.stringify(JsonValue.ObjectValue(core))
    }

    private fun parseModels(body: String): LlmResult<List<ModelDescriptor>> = try {
        val root = MiniJson.parse(body).objectOrNull() ?: error("Expected object")
        val data = root["data"]?.arrayOrNull() ?: error("Missing model data")
        LlmResult.Success(data.mapNotNull { item ->
            val obj = item.objectOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.stringOrNull() ?: return@mapNotNull null
            val context = obj["context_length"]?.numberOrNull()?.toLong()
            val architecture = obj["architecture"]?.objectOrNull()
            fun modalities(key: String): Set<Modality> = (architecture?.get(key) ?: obj[key])?.arrayOrNull()?.mapNotNull {
                when (it.stringOrNull()?.lowercase()) { "text" -> Modality.TEXT; "image" -> Modality.IMAGE; "audio" -> Modality.AUDIO; else -> null }
            }?.toSet().orEmpty().ifEmpty { setOf(Modality.TEXT) }
            val input = modalities("input_modalities")
            val output = modalities("output_modalities")
            val parameters = obj["supported_parameters"]?.arrayOrNull()?.mapNotNull { it.stringOrNull() }?.toSet().orEmpty()
            fun promoted(base: TriState, names: Set<String>) = if (parameters.any { it in names }) TriState.YES else base
            val detected = staticCapabilities.copy(
                jsonMode = promoted(staticCapabilities.jsonMode, setOf("response_format")),
                structuredOutput = promoted(staticCapabilities.structuredOutput, setOf("structured_outputs", "json_schema")),
                tools = promoted(staticCapabilities.tools, setOf("tools", "tool_choice")),
                vision = if (Modality.IMAGE in input) TriState.YES else staticCapabilities.vision,
            )
            val pricingObj = obj["pricing"]?.objectOrNull()
            fun perMillion(key: String) = pricingObj?.get(key)?.let { value -> value.numberOrNull() ?: value.stringOrNull()?.toDoubleOrNull() }?.times(1_000_000)
            val pricing = pricingObj?.let { ModelPricing(perMillion("prompt"), perMillion("completion")) }
            ModelDescriptor(id, obj["name"]?.stringOrNull() ?: id, profile.id, context, input, output, ModelCapabilities(detected, "models-endpoint"), pricing)
        })
    } catch (failure: Throwable) { LlmResult.Failure(LlmError.MalformedProviderResponse(profile.id, null, failure.message)) }

    private fun parseCompletion(request: LlmRequest, body: String, endpoint: RequestEndpoint): LlmResult<LlmResponse> = try {
        val root = MiniJson.parse(body).objectOrNull() ?: error("Expected object")
        val responseId = root["id"]?.stringOrNull()
        val text: String
        val calls = mutableListOf<LlmToolCall>()
        if (endpoint == RequestEndpoint.RESPONSES) {
            text = root["output_text"]?.stringOrNull() ?: root["output"]?.arrayOrNull()?.mapNotNull { it.objectOrNull()?.get("content")?.stringOrNull() }?.joinToString("").orEmpty()
        } else {
            val message = root["choices"]?.arrayOrNull()?.firstOrNull()?.objectOrNull()?.get("message")?.objectOrNull() ?: error("Missing choice message")
            text = message["content"]?.stringOrNull().orEmpty()
            message["tool_calls"]?.arrayOrNull()?.forEach { value -> value.objectOrNull()?.let { call ->
                val function = call["function"]?.objectOrNull() ?: return@let
                calls += LlmToolCall(call["id"]?.stringOrNull() ?: "call_${calls.size}", function["name"]?.stringOrNull() ?: return@let, function["arguments"]?.stringOrNull() ?: "{}")
            } }
        }
        val usageObj = root["usage"]?.objectOrNull()
        val usage = usageObj?.let { LlmUsage((it["prompt_tokens"] ?: it["input_tokens"])?.numberOrNull()?.toLong() ?: 0,
            (it["completion_tokens"] ?: it["output_tokens"])?.numberOrNull()?.toLong() ?: 0, it["cached_tokens"]?.numberOrNull()?.toLong()) }
        LlmResult.Success(LlmResponse(request.requestId, profile.id, request.model, text, calls, usage, responseId))
    } catch (failure: Throwable) { LlmResult.Failure(LlmError.MalformedProviderResponse(profile.id, request.model, failure.message)) }

    private fun extractJson(text: String): String {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        MiniJson.parse(trimmed)
        return trimmed
    }
}

class OpenAiProvider(profile: ProviderProfile, transport: HttpTransport, secrets: SecretStore) : OpenAiCompatibleAdapter(profile, transport, secrets,
    CapabilitySet(TriState.YES, TriState.YES, TriState.YES, TriState.YES, TriState.YES, TriState.UNKNOWN, TriState.YES))
class OpenRouterProvider(profile: ProviderProfile, transport: HttpTransport, secrets: SecretStore) : OpenAiCompatibleAdapter(profile, transport, secrets,
    CapabilitySet(TriState.YES, TriState.YES, TriState.YES, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN, TriState.NO))
class HuggingFaceProvider(profile: ProviderProfile, transport: HttpTransport, secrets: SecretStore) : OpenAiCompatibleAdapter(profile, transport, secrets,
    CapabilitySet(TriState.YES, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN))
class NvidiaProvider(profile: ProviderProfile, transport: HttpTransport, secrets: SecretStore) : OpenAiCompatibleAdapter(profile, transport, secrets,
    CapabilitySet(TriState.YES, TriState.YES, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN, TriState.UNKNOWN))
class CustomOpenAiCompatibleProvider(profile: ProviderProfile, transport: HttpTransport, secrets: SecretStore) : OpenAiCompatibleAdapter(profile, transport, secrets, CapabilitySet())
