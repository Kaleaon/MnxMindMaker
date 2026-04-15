package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONObject

data class RoutingPolicy(
    val userPreference: LlmProvider? = null,
    val prioritizeCost: Boolean = false,
    val prioritizeLatency: Boolean = true,
    val allowOfflineFallback: Boolean = true,
    val strictLocalOnly: Boolean = false
)

data class GovernedRoutingResult(
    val settings: List<LlmSettings>,
    val rejections: List<String>
)

enum class FailoverReasonCode {
    SETTINGS_INVALID,
    ADAPTER_UNAVAILABLE,
    PROVIDER_ERROR
}

data class ProviderFailoverEvent(
    val fromProvider: LlmProvider,
    val fromModel: String,
    val reasonCode: FailoverReasonCode,
    val userVisibleReason: String
)

class ProviderRouter(
    private val providers: List<AssistantProvider> = listOf(
        LlmEdgeProvider(),
        LocalProvider(),
        ClaudeProvider(),
        GeminiProvider(),
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
        val failoverEvents = mutableListOf<ProviderFailoverEvent>()
        for (settings in ordered) {
            val validationError = ProviderSettingsValidator.validate(settings)
            if (validationError != null) {
                lastError = LlmApiException("${settings.provider.displayName}: $validationError")
                failoverEvents += ProviderFailoverEvent(
                    fromProvider = settings.provider,
                    fromModel = settings.model,
                    reasonCode = FailoverReasonCode.SETTINGS_INVALID,
                    userVisibleReason = validationError
                )
                continue
            }
            val provider = providers.firstOrNull { it.supports(settings) }
            if (provider == null) {
                lastError = LlmApiException("No provider adapter for ${settings.provider.displayName}")
                failoverEvents += ProviderFailoverEvent(
                    fromProvider = settings.provider,
                    fromModel = settings.model,
                    reasonCode = FailoverReasonCode.ADAPTER_UNAVAILABLE,
                    userVisibleReason = "No provider adapter for ${settings.provider.displayName}"
                )
                continue
            }
            try {
                val turn = provider.chat(
                    ProviderRequest(
                        settings = settings,
                        systemPrompt = systemPrompt,
                        transcript = transcript,
                        tools = tools
                    )
                )
                if (failoverEvents.isEmpty()) return turn
                val failoverJson = org.json.JSONArray().also { events ->
                    failoverEvents.forEach { event ->
                        events.put(
                            JSONObject()
                                .put("provider", event.fromProvider.name)
                                .put("model", event.fromModel)
                                .put("reason_code", event.reasonCode.name)
                                .put("message", event.userVisibleReason)
                        )
                    }
                }
                val raw = (turn.raw?.let { JSONObject(it.toString()) } ?: JSONObject())
                    .put("failover_events", failoverJson)
                return turn.copy(raw = raw)
            } catch (e: Exception) {
                lastError = e
                failoverEvents += ProviderFailoverEvent(
                    fromProvider = settings.provider,
                    fromModel = settings.model,
                    reasonCode = FailoverReasonCode.PROVIDER_ERROR,
                    userVisibleReason = e.message ?: "Provider request failed"
                )
            }
        }
        val reasonCodes = failoverEvents.joinToString(",") { it.reasonCode.name }
        throw LlmApiException(
            "All providers failed after policy-approved fallback chain. Reason codes: [$reasonCodes]. Last error: ${lastError?.message}",
            lastError
        )
    }

    fun selectGovernedChain(
        settingsChain: List<LlmSettings>,
        privacyMode: PrivacyMode,
        maxClassification: DataClassification,
        fallbackOrder: LlmFallbackOrder
    ): GovernedRoutingResult {
        val rejections = mutableListOf<String>()

        var filtered = settingsChain.filter { settings ->
            val classificationAllowed = classificationRank(settings.outboundClassification) <= classificationRank(maxClassification)
            if (!classificationAllowed) {
                rejections += "${settings.provider.name}: classification ${settings.outboundClassification} exceeds $maxClassification"
            }
            classificationAllowed
        }

        filtered = filtered.filter { settings ->
            val allowed = privacyMode != PrivacyMode.STRICT_LOCAL_ONLY || settings.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE
            if (!allowed) {
                rejections += "${settings.provider.name}: blocked by STRICT_LOCAL_ONLY privacy mode"
            }
            allowed
        }

        filtered = when (fallbackOrder) {
            LlmFallbackOrder.REMOTE_ONLY -> filtered.filterNot { it.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE }
            LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK -> {
                val local = filtered.filter { it.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE }
                val remote = filtered.filter { it.provider.runtime != LlmRuntime.LOCAL_ON_DEVICE }
                local + remote
            }
        }

        return GovernedRoutingResult(settings = filtered, rejections = rejections)
    }

    fun healthCheck(settings: LlmSettings): ProviderHealth {
        ProviderSettingsValidator.validate(settings)?.let { return ProviderHealth(false, it) }
        val provider = providers.firstOrNull { it.supports(settings) }
            ?: return ProviderHealth(false, "No adapter for ${settings.provider.displayName}")
        return provider.healthCheck(settings)
    }

    private fun orderByPolicy(settingsChain: List<LlmSettings>, policy: RoutingPolicy): List<LlmSettings> {
        var filtered = settingsChain.filter {
            policy.allowOfflineFallback || it.provider.runtime != LlmRuntime.LOCAL_ON_DEVICE
        }
        if (policy.strictLocalOnly) {
            filtered = filtered.filter { it.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE }
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

    private fun classificationRank(classification: DataClassification): Int = when (classification) {
        DataClassification.PUBLIC -> 0
        DataClassification.SENSITIVE -> 1
        DataClassification.RESTRICTED -> 2
    }

    private fun costRank(provider: LlmProvider): Int = when (provider) {
        LlmProvider.LOCAL_ON_DEVICE, LlmProvider.VLLM_GEMMA4, LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED -> 0
        LlmProvider.GEMINI -> 1
        LlmProvider.OPENAI -> 2
        LlmProvider.ANTHROPIC -> 3
    }

    private fun latencyRank(provider: LlmProvider): Int = when (provider) {
        LlmProvider.LOCAL_ON_DEVICE, LlmProvider.VLLM_GEMMA4, LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED -> 0
        LlmProvider.OPENAI -> 1
        LlmProvider.ANTHROPIC -> 2
        LlmProvider.GEMINI -> 3
    }
}
