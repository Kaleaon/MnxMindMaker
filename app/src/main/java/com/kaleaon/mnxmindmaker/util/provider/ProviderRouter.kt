package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONObject

data class RoutingPolicy(
    val userPreference: LlmProvider? = null,
    val prioritizeCost: Boolean = false,
    val prioritizeLatency: Boolean = true,
    val allowOfflineFallback: Boolean = true
)

class ProviderRouter(
    private val providers: List<AssistantProvider> = listOf(
        LocalProvider(),
        ClaudeProvider(),
        ChatGPTProvider()
    )
) {

    fun chat(
        settingsChain: List<LlmSettings>,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec> = emptyList(),
        policy: RoutingPolicy = RoutingPolicy()
    ): AssistantTurn {
        val ordered = orderByPolicy(settingsChain, policy)
        if (ordered.isEmpty()) throw LlmApiException("No provider configuration available for routing")

        var lastError: Exception? = null
        for (settings in ordered) {
            val provider = providers.firstOrNull { it.supports(settings) }
            if (provider == null) {
                lastError = LlmApiException("No provider adapter for ${settings.provider.displayName}")
                continue
            }
            try {
                return provider.chat(
                    ProviderRequest(
                        settings = settings,
                        systemPrompt = systemPrompt,
                        transcript = transcript,
                        tools = tools
                    )
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw LlmApiException(
            "All providers failed. Last error: ${lastError?.message}",
            lastError
        )
    }

    fun healthCheck(settings: LlmSettings): ProviderHealth {
        val provider = providers.firstOrNull { it.supports(settings) }
            ?: return ProviderHealth(false, "No adapter for ${settings.provider.displayName}")
        return provider.healthCheck(settings)
    }

    private fun orderByPolicy(settingsChain: List<LlmSettings>, policy: RoutingPolicy): List<LlmSettings> {
        var filtered = settingsChain.filter {
            policy.allowOfflineFallback || it.provider.runtime != LlmRuntime.LOCAL_ON_DEVICE
        }

        if (policy.userPreference != null) {
            val preferred = filtered.filter { it.provider == policy.userPreference }
            val rest = filtered.filterNot { it.provider == policy.userPreference }
            filtered = preferred + rest
        }

        return filtered.sortedBy { settings ->
            var score = 0
            if (policy.prioritizeCost) score += costRank(settings.provider)
            if (policy.prioritizeLatency) score += latencyRank(settings.provider)
            score
        }
    }

    private fun costRank(provider: LlmProvider): Int = when (provider) {
        LlmProvider.LOCAL_ON_DEVICE, LlmProvider.VLLM_GEMMA4 -> 0
        LlmProvider.GEMINI -> 1
        LlmProvider.OPENAI -> 2
        LlmProvider.ANTHROPIC -> 3
    }

    private fun latencyRank(provider: LlmProvider): Int = when (provider) {
        LlmProvider.LOCAL_ON_DEVICE, LlmProvider.VLLM_GEMMA4 -> 0
        LlmProvider.OPENAI -> 1
        LlmProvider.ANTHROPIC -> 2
        LlmProvider.GEMINI -> 3
    }
}
