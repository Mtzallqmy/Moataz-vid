package com.moatazvid.ai.provider

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProviderCoreTest {
    @Test fun `base URL prevents duplicate v1`() {
        assertEquals("https://host.test/v1", BaseUrlNormalizer.normalize("https://HOST.test/v1/"))
        assertEquals("https://host.test/v1/chat/completions", BaseUrlNormalizer.resolve("https://host.test/v1", "/v1/chat/completions"))
        assertThrows(IllegalArgumentException::class.java) { BaseUrlNormalizer.normalize("https://user:pass@host.test") }
    }

    @Test fun `auth and logging never reveal secret`() {
        val profile = profile()
        assertEquals("Bearer top-secret", AuthHeaderFactory.create(profile, "top-secret".toCharArray())["Authorization"])
        val summary = RedactingNetworkLogger(true).requestSummary(HttpRequest(RequestId("r"), "POST", "https://host.test", mapOf("Authorization" to "Bearer top-secret"), "prompt", 1_000, true))
        assertFalse(summary.contains("top-secret")); assertFalse(summary.contains("prompt")); assertTrue(summary.contains("<redacted>"))
    }

    @Test fun `model listing normalizes unknown metadata`() = runBlocking {
        val transport = FakeTransport(HttpResponse(200, emptyMap(), """{"data":[{"id":"model-a"}]}"""))
        val provider = CustomOpenAiCompatibleProvider(profile(), transport, MemorySecrets())
        val result = provider.listModels() as LlmResult.Success
        assertEquals("model-a", result.value.single().id)
        assertNull(result.value.single().contextLength)
        assertEquals(TriState.UNKNOWN, result.value.single().capabilities.values.tools)
    }

    @Test fun `SSE emits text tools and rejects malformed event safely`() {
        val parser = OpenAiSseParser(ProviderId("p"), "m")
        assertEquals(LlmStreamEvent.TextDelta("Hi"), parser.accept("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}").single())
        assertTrue(parser.accept("data: not-json").single() is LlmStreamEvent.Error)
        assertTrue(parser.accept("data: [DONE]").last() is LlmStreamEvent.Completed)
    }

    @Test fun `HTTP errors have stable taxonomy and retry policy respects actions`() {
        assertTrue(HttpErrorMapper.map(ProviderId("p"), "m", 401, null, null) is LlmError.Authentication)
        assertTrue(HttpErrorMapper.map(ProviderId("p"), "m", 429, null, 1_000) is LlmError.RateLimited)
        assertFalse(RetryPolicy(3) { 0 }.decide(0, 500, false, null, idempotent = false).retry)
        assertTrue(RetryPolicy(3) { 0 }.decide(0, 500, false, null, idempotent = true).retry)
    }

    @Test fun `capability evidence and editing levels remain explicit`() {
        val caps = CapabilityDetector().merge(listOf(
            CapabilityEvidence("structuredOutput", TriState.YES, EvidenceSource.MODEL_METADATA, 30),
            CapabilityEvidence("tools", TriState.NO, EvidenceSource.MODEL_METADATA, 30),
            CapabilityEvidence("tools", TriState.YES, EvidenceSource.USER_OVERRIDE, 50),
        ))
        assertEquals(EditingCapabilityLevel.LEVEL_A, caps.editingLevel())
        assertEquals(TriState.UNKNOWN, caps.vision)
    }

    @Test fun `secret deletion erases provider value`() = runBlocking {
        val secrets = MemorySecrets(); secrets.saveSecret(ProviderId("p"), "value".toCharArray())
        assertNotNull(secrets.readSecret(ProviderId("p")))
        assertTrue(secrets.deleteSecret(ProviderId("p"))); assertNull(secrets.readSecret(ProviderId("p")))
    }

    private fun profile() = ProviderProfile(ProviderId("p"), "Test", ProviderType.OPENAI_COMPATIBLE, "https://host.test", "keystore:p", "model-a")
}

private class FakeTransport(private val response: HttpResponse) : HttpTransport {
    lateinit var request: HttpRequest
    override suspend fun execute(request: HttpRequest): TransportResult<HttpResponse> { this.request = request; return TransportResult.Success(response) }
    override fun stream(request: HttpRequest): Flow<TransportStreamEvent> = flowOf(TransportStreamEvent.Closed)
    override suspend fun cancel(requestId: RequestId) = true
}

private class MemorySecrets : SecretStore {
    private val values = mutableMapOf<ProviderId, CharArray>()
    override suspend fun saveSecret(providerId: ProviderId, value: CharArray): String { values[providerId] = value.copyOf(); value.fill('\u0000'); return "memory:${providerId.value}" }
    override suspend fun readSecret(providerId: ProviderId): SecretValue? = values[providerId]?.copyOf()?.let(::SecretValue)
    override suspend fun deleteSecret(providerId: ProviderId): Boolean = values.remove(providerId)?.also { it.fill('\u0000') } != null
}
