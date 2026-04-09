package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.provider.AssistantProvider
import com.kaleaon.mnxmindmaker.util.provider.ChatGPTProvider
import com.kaleaon.mnxmindmaker.util.provider.ClaudeProvider
import com.kaleaon.mnxmindmaker.util.provider.GeminiProvider
import com.kaleaon.mnxmindmaker.util.provider.LocalProvider
import com.kaleaon.mnxmindmaker.util.provider.ProviderRequest
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONObject

class LlmApiClient(
    private val providers: List<AssistantProvider> = listOf(
        LocalProvider(),
        ClaudeProvider(),
        GeminiProvider(),
        ChatGPTProvider()
    )
) {

    fun complete(settings: LlmSettings, systemPrompt: String, userMessage: String): String {
        val turn = completeAssistantTurn(
            settings = settings,
            systemPrompt = systemPrompt,
            transcript = listOf(JSONObject().put("role", "user").put("content", userMessage)),
            tools = emptyList()
        )
        return turn.text
    }

    fun completeAssistantTurn(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): AssistantTurn {
        val provider = providers.firstOrNull { it.supports(settings) }
            ?: throw LlmApiException("No provider adapter for ${settings.provider.displayName}")

        return provider.chat(
            ProviderRequest(
                settings = settings,
                systemPrompt = systemPrompt,
                transcript = transcript,
                tools = tools
            )
        )
    }

    fun localMetadata(settings: LlmSettings): LocalModelMetadata? {
        if (settings.provider != LlmProvider.LOCAL_ON_DEVICE) return null
        return LocalModelMetadata(
            backendId = "openai-compatible-local",
            modelName = settings.model,
            runtimePath = settings.localModelPath,
            contextWindowTokens = settings.capabilities.contextWindowTokens,
            supportsStreaming = true,
            supportsCancellation = true
        )
    }
}

data class LocalModelMetadata(
    val backendId: String,
    val modelName: String,
    val runtimePath: String,
    val contextWindowTokens: Int,
    val supportsStreaming: Boolean,
    val supportsCancellation: Boolean
)
