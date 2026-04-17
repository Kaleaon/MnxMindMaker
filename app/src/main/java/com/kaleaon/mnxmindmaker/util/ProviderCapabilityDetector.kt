package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.ProviderCapabilityMetadata

class ProviderCapabilityDetector {

    fun detect(provider: ExternalProvider): ProviderCapabilityMetadata {
        val models = when (provider) {
            ExternalProvider.CLAUDE -> listOf("claude-3-7-sonnet", "claude-3-5-sonnet")
            ExternalProvider.CHATGPT -> listOf("gpt-4o", "gpt-4.1", "gpt-4.1-mini")
            ExternalProvider.HUGGING_FACE -> listOf("google/gemma-3n-E2B-it-litert-lm", "Qwen/Qwen2.5-7B-Instruct", "meta-llama/Llama-3.1-8B-Instruct")
        }
        val rateLimit = when (provider) {
            ExternalProvider.CLAUDE -> "Plan-dependent (detected offline profile)"
            ExternalProvider.CHATGPT -> "Plan-dependent (detected offline profile)"
            ExternalProvider.HUGGING_FACE -> "Hub/API limits depend on account tier and endpoint"
        }
        return ProviderCapabilityMetadata(
            models = models,
            supportsToolUse = true,
            rateLimitInfo = rateLimit,
            detectedAtEpochMs = System.currentTimeMillis()
        )
    }
}
