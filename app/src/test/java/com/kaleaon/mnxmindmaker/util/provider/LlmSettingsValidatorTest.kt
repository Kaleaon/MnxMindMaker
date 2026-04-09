package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmSettingsValidatorTest {

    @Test
    fun `validate flags empty model and invalid base url`() {
        val settings = LlmSettings(
            provider = LlmProvider.OPENAI,
            model = "",
            baseUrl = "not-a-url",
            enabled = true,
            apiKey = "token"
        )

        val issues = validate(settings, PrivacyMode.HYBRID)

        assertTrue(issues.any { it.field == "model" && it.severity == ValidationSeverity.CRITICAL })
        assertTrue(issues.any { it.field == "baseUrl" && it.severity == ValidationSeverity.CRITICAL })
    }

    @Test
    fun `validate enforces strict local-only compatibility`() {
        val settings = LlmSettings(
            provider = LlmProvider.OPENAI,
            enabled = true,
            apiKey = "token"
        )

        val issues = validate(settings, PrivacyMode.STRICT_LOCAL_ONLY)

        assertTrue(issues.any { it.field == "provider" && it.severity == ValidationSeverity.CRITICAL })
    }

    @Test
    fun `validate checks token and context bounds`() {
        val settings = LlmSettings(
            provider = LlmProvider.LOCAL_ON_DEVICE,
            enabled = true,
            localModelPath = "/tmp/model.gguf",
            maxTokens = 5000,
            wakeUpTokenBudget = 6000,
            runtimeControls = LocalRuntimeControls(contextWindowTokens = 4096)
        )

        val issues = validate(settings)

        assertTrue(issues.any { it.field == "maxTokens" })
        assertTrue(issues.any { it.field == "wakeUpTokenBudget" })
    }

    @Test
    fun `validate runtime endpoint accepts http and https only`() {
        assertTrue(validateRuntimeEndpoint("ftp://example.com").isNotEmpty())
        assertTrue(validateRuntimeEndpoint("https://example.com").isEmpty())
    }
}
