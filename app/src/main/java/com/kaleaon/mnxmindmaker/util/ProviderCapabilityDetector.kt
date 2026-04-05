package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.ProviderCapabilityMetadata

class ProviderCapabilityDetector {

    fun detect(provider: ExternalProvider): ProviderCapabilityMetadata {
        val models = when (provider) {
            ExternalProvider.CLAUDE -> listOf("claude-3-7-sonnet", "claude-3-5-sonnet")
            ExternalProvider.CHATGPT -> listOf("gpt-4o", "gpt-4.1", "gpt-4.1-mini")
        }
        val rateLimit = when (provider) {
            ExternalProvider.CLAUDE -> "Plan-dependent (detected offline profile)"
            ExternalProvider.CHATGPT -> "Plan-dependent (detected offline profile)"
        }
        return ProviderCapabilityMetadata(
            models = models,
            supportsToolUse = true,
            rateLimitInfo = rateLimit,
            detectedAtEpochMs = System.currentTimeMillis()
        )
    }
}
