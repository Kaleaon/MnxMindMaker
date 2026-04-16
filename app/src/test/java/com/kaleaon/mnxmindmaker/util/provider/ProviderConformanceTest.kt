package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConformanceTest {

    @Test
    fun `all built-in providers pass conformance suite`() {
        val providers = listOf(
            LlmEdgeProvider(),
            LocalProvider(),
            ClaudeProvider(),
            GeminiProvider(),
            ChatGPTProvider()
        )

        val failures = providers.map { ProviderConformanceSuite.validate(it) }
            .filter { !it.passed }

        assertTrue(
            "Expected all providers to pass conformance but got: ${failures.joinToString { "${it.providerId}:${it.violations}" }}",
            failures.isEmpty()
        )
    }

    @Test
    fun `non-conforming providers are rejected by conformance gate`() {
        val routed = ProviderConformanceGate.enforce(
            listOf(
                InvalidProvider(),
                ValidStubProvider()
            )
        )

        assertEquals(1, routed.size)
        assertEquals("valid_stub", routed.first().id)
    }

    @Test
    fun `normalized chat extracts usage and keeps provider id`() {
        val provider = ValidStubProvider()
        val result = provider.completeChat(
            ProviderRequest(
                settings = settings(LlmProvider.OPENAI),
                systemPrompt = "system",
                transcript = listOf(JSONObject().put("role", "user").put("content", "hello"))
            )
        )

        assertEquals("valid_stub", result.providerId)
        assertEquals(5, result.usage.promptTokens)
        assertEquals(3, result.usage.completionTokens)
        assertEquals(8, result.usage.totalTokens)
    }

    @Test
    fun `embedding and rerank are normalized for every provider via fallback`() {
        val provider = ValidStubProvider()
        val settings = settings(LlmProvider.OPENAI)
        val embeddings = provider.embedNormalized(
            settings,
            ProviderEmbeddingRequest(input = listOf("alpha", "beta"), dimensions = 64)
        )
        assertEquals(2, embeddings.vectors.size)
        assertEquals(64, embeddings.vectors.first().size)

        val rerank = provider.rerankNormalized(
            settings,
            ProviderRerankRequest(
                query = "alpha",
                documents = listOf("alpha result", "unrelated"),
                topK = 1
            )
        )
        assertEquals(1, rerank.items.size)
        assertFalse(rerank.items.first().score.isNaN())
    }

    private fun settings(provider: LlmProvider): LlmSettings = LlmSettings(
        provider = provider,
        enabled = true,
        runtimeControls = LocalRuntimeControls(contextWindowTokens = 4096)
    )

    private class ValidStubProvider : AssistantProvider {
        override val id: String = "valid_stub"

        override fun supports(settings: LlmSettings): Boolean = true

        override fun chat(request: ProviderRequest): AssistantTurn {
            val raw = JSONObject().put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", 5)
                    .put("completion_tokens", 3)
                    .put("total_tokens", 8)
            )
            return AssistantTurn(text = "ok", raw = raw)
        }

        override fun healthCheck(settings: LlmSettings): ProviderHealth = ProviderHealth(true, "ok")
    }

    private class InvalidProvider : AssistantProvider {
        override val id: String = "INVALID"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(supportsStreaming = false)

        override fun supports(settings: LlmSettings): Boolean = false

        override fun chat(request: ProviderRequest): AssistantTurn = AssistantTurn(text = "bad")

        override fun healthCheck(settings: LlmSettings): ProviderHealth = ProviderHealth(false, "bad")
    }
}
