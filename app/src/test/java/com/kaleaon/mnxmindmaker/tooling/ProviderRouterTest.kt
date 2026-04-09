package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.util.provider.AssistantProvider
import com.kaleaon.mnxmindmaker.util.provider.ProviderHealth
import com.kaleaon.mnxmindmaker.util.provider.ProviderJsonAdapters
import com.kaleaon.mnxmindmaker.util.provider.ProviderRequest
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import com.kaleaon.mnxmindmaker.util.provider.RoutingPolicy
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRouterTest {

    @Test
    fun `user preference is applied before ranking`() {
        val openAi = settings(LlmProvider.OPENAI)
        val anthropic = settings(LlmProvider.ANTHROPIC)
        val router = ProviderRouter(
            providers = listOf(
                StubProvider("openai") { it.provider == LlmProvider.OPENAI },
                StubProvider("anthropic") { it.provider == LlmProvider.ANTHROPIC }
            )
        )

        val turn = router.chat(
            settingsChain = listOf(openAi, anthropic),
            systemPrompt = "system",
            transcript = emptyList(),
            policy = RoutingPolicy(
                userPreference = LlmProvider.ANTHROPIC,
                prioritizeLatency = true
            )
        )

        assertEquals("anthropic", turn.text)
    }

    @Test
    fun `cost and latency ranking prefers local runtime when fallback is allowed`() {
        val router = ProviderRouter(
            providers = listOf(
                StubProvider("edge") { it.provider == LlmProvider.LOCAL_ON_DEVICE },
                StubProvider("openai") { it.provider == LlmProvider.OPENAI }
            )
        )

        val turn = router.chat(
            settingsChain = listOf(settings(LlmProvider.OPENAI), settings(LlmProvider.LOCAL_ON_DEVICE)),
            systemPrompt = "system",
            transcript = emptyList(),
            policy = RoutingPolicy(
                prioritizeCost = true,
                prioritizeLatency = true,
                allowOfflineFallback = true
            )
        )

        assertEquals("edge", turn.text)
    }

    @Test
    fun `offline fallback disabled excludes local runtime`() {
        val router = ProviderRouter(
            providers = listOf(
                StubProvider("edge") { it.provider == LlmProvider.LOCAL_ON_DEVICE },
                StubProvider("openai") { it.provider == LlmProvider.OPENAI }
            )
        )

        val turn = router.chat(
            settingsChain = listOf(settings(LlmProvider.LOCAL_ON_DEVICE), settings(LlmProvider.OPENAI)),
            systemPrompt = "system",
            transcript = emptyList(),
            policy = RoutingPolicy(allowOfflineFallback = false)
        )

        assertEquals("openai", turn.text)
    }

    @Test
    fun `strict local only takes precedence over user preference in hierarchical routing`() {
        val router = ProviderRouter(
            providers = listOf(
                StubProvider("edge") { it.provider == LlmProvider.LOCAL_ON_DEVICE },
                StubProvider("openai") { it.provider == LlmProvider.OPENAI }
            )
        )

        val turn = router.chat(
            settingsChain = listOf(settings(LlmProvider.OPENAI), settings(LlmProvider.LOCAL_ON_DEVICE)),
            systemPrompt = "system",
            transcript = emptyList(),
            policy = RoutingPolicy(
                userPreference = LlmProvider.OPENAI,
                strictLocalOnly = true
            )
        )

        assertEquals("edge", turn.text)
    }

    @Test
    fun `wake-up token cap is respected when provider request body is serialized`() {
        val settings = settings(
            provider = LlmProvider.OPENAI,
            maxTokens = 192
        )

        val body = ProviderJsonAdapters.openAiBody(
            settings = settings,
            systemPrompt = "wake up and summarize",
            transcript = listOf(JSONObject().put("role", "user").put("content", "hello")),
            tools = emptyList()
        )

        assertEquals(192, body.getInt("max_tokens"))
    }

    @Test
    fun `default provider router includes llm edge adapter for local runtime`() {
        val health = ProviderRouter().healthCheck(settings(LlmProvider.LOCAL_ON_DEVICE))

        assertFalse(health.message.contains("No adapter"))
    }

    @Test
    fun `default provider router includes gemini adapter`() {
        val health = ProviderRouter().healthCheck(settings(LlmProvider.GEMINI))

        assertFalse(health.message.contains("No adapter"))
    }

    @Test
    fun `local provider supports vllm and self hosted openai but not official openai`() {
        val provider = com.kaleaon.mnxmindmaker.util.provider.LocalProvider()

        assertTrue(provider.supports(settings(LlmProvider.VLLM_GEMMA4)))
        assertTrue(provider.supports(settings(LlmProvider.OPENAI, baseUrl = "http://10.0.2.2:9000/v1")))
        assertFalse(provider.supports(settings(LlmProvider.OPENAI, baseUrl = "https://api.openai.com/v1")))
        assertFalse(provider.supports(settings(LlmProvider.LOCAL_ON_DEVICE)))
    }

    private fun settings(
        provider: LlmProvider,
        baseUrl: String = provider.baseUrl,
        maxTokens: Int = 2048
    ): LlmSettings = LlmSettings(
        provider = provider,
        enabled = true,
        baseUrl = baseUrl,
        maxTokens = maxTokens,
        runtimeControls = LocalRuntimeControls(contextWindowTokens = 4096)
    )

    private class StubProvider(
        override val id: String,
        private val supportsPredicate: (LlmSettings) -> Boolean
    ) : AssistantProvider {
        override fun supports(settings: LlmSettings): Boolean = supportsPredicate(settings)

        override fun chat(request: ProviderRequest): AssistantTurn = AssistantTurn(text = id, raw = JSONObject())

        override fun healthCheck(settings: LlmSettings): ProviderHealth = ProviderHealth(true, "ok")
    }
}
