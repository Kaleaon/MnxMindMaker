package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.provider.AssistantProvider
import com.kaleaon.mnxmindmaker.util.provider.ModelCapabilityProfile
import com.kaleaon.mnxmindmaker.util.provider.ModelCapabilitySource
import com.kaleaon.mnxmindmaker.util.provider.ProviderHealth
import com.kaleaon.mnxmindmaker.util.provider.ProviderRequest
import com.kaleaon.mnxmindmaker.util.provider.RuntimeModelCapabilityDetector
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolOperationClass
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModelCapabilityDetectorTest {

    @Test
    fun `openai runtime detection infers tool json vision and context window`() {
        val detector = RuntimeModelCapabilityDetector(clock = { 123L })

        val profile = detector.detect(
            LlmSettings(
                provider = LlmProvider.OPENAI,
                apiKey = "k",
                model = "gpt-4.1"
            )
        )

        assertEquals(LlmProvider.OPENAI, profile.provider)
        assertTrue(profile.supportsToolCalls)
        assertTrue(profile.supportsJsonMode)
        assertTrue(profile.supportsVision)
        assertEquals(128_000, profile.contextWindowTokens)
        assertEquals(123L, profile.detectedAtEpochMs)
    }

    @Test
    fun `llm api client removes tools when runtime capability says no tool calls`() {
        val provider = CapturingProvider()
        val capabilitySource = StubCapabilitySource(
            ModelCapabilityProfile(
                provider = LlmProvider.OPENAI,
                modelId = "gpt-4o",
                supportsToolCalls = false,
                supportsJsonMode = true,
                supportsVision = true,
                contextWindowTokens = 8_000,
                detectedAtEpochMs = 1L
            )
        )
        val client = LlmApiClient(
            capabilityRegistry = capabilitySource,
            providers = listOf(provider)
        )
        val settings = LlmSettings(
            provider = LlmProvider.OPENAI,
            apiKey = "k",
            model = "gpt-4o",
            enabled = true
        )
        val tools = listOf(
            ToolSpec(
                name = "lookup_docs",
                description = "lookup",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject().put("type", "object")
            )
        )

        client.completeAssistantTurn(
            settings = settings,
            systemPrompt = "system",
            transcript = listOf(JSONObject().put("role", "user").put("content", "hello")),
            tools = tools
        )

        assertTrue(provider.lastRequest != null)
        assertTrue(provider.lastRequest!!.tools.isEmpty())
    }

    @Test
    fun `llm api client caps max tokens using detected context window`() {
        val provider = CapturingProvider()
        val capabilitySource = StubCapabilitySource(
            ModelCapabilityProfile(
                provider = LlmProvider.OPENAI,
                modelId = "gpt-4o",
                supportsToolCalls = true,
                supportsJsonMode = true,
                supportsVision = true,
                contextWindowTokens = 512,
                detectedAtEpochMs = 2L
            )
        )
        val client = LlmApiClient(
            capabilityRegistry = capabilitySource,
            providers = listOf(provider)
        )
        val settings = LlmSettings(
            provider = LlmProvider.OPENAI,
            apiKey = "k",
            model = "gpt-4o",
            enabled = true,
            maxTokens = 2048
        )

        client.completeAssistantTurn(
            settings = settings,
            systemPrompt = "system",
            transcript = listOf(JSONObject().put("role", "user").put("content", "hello")),
            tools = emptyList()
        )

        assertEquals(512, provider.lastRequest!!.settings.maxTokens)
        assertFalse(provider.lastRequest!!.settings.maxTokens > settings.maxTokens)
    }

    private class StubCapabilitySource(
        private val profile: ModelCapabilityProfile
    ) : ModelCapabilitySource {
        override fun capabilitiesFor(settings: LlmSettings): ModelCapabilityProfile = profile
    }

    private class CapturingProvider : AssistantProvider {
        override val id: String = "capturing"
        var lastRequest: ProviderRequest? = null

        override fun supports(settings: LlmSettings): Boolean = settings.provider == LlmProvider.OPENAI

        override fun chat(request: ProviderRequest): AssistantTurn {
            lastRequest = request
            return AssistantTurn(text = "ok")
        }

        override fun healthCheck(settings: LlmSettings): ProviderHealth = ProviderHealth(ok = true, message = "ok")
    }
}
