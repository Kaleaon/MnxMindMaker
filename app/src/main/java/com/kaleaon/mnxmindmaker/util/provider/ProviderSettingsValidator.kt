package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.LlmApiException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ProviderSettingsValidator {

    fun validateOrThrow(settings: LlmSettings) {
        validate(settings)?.let { throw LlmApiException(it) }
    }

    fun validate(settings: LlmSettings): String? {
        val parsedUrl = settings.baseUrl.toHttpUrlOrNull()
            ?: return "Invalid Base URL for ${settings.provider.displayName}. Enter a full http(s) URL (for example: https://api.openai.com/v1)."

        return when (settings.provider) {
            LlmProvider.OPENAI -> {
                val isOfficialOpenAiHost = parsedUrl.host.equals("api.openai.com", ignoreCase = true)
                if (!isOfficialOpenAiHost) {
                    "OpenAI must use https://api.openai.com/v1. For self-hosted OpenAI-compatible endpoints, switch provider to OpenAI-compatible (Self-hosted)."
                } else {
                    null
                }
            }

            LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED -> {
                val isOfficialOpenAiHost = parsedUrl.host.equals("api.openai.com", ignoreCase = true)
                if (isOfficialOpenAiHost) {
                    "OpenAI-compatible (Self-hosted) cannot use api.openai.com. Select OpenAI (GPT) for official OpenAI, or provide your self-hosted endpoint URL."
                } else {
                    null
                }
            }

            else -> null
        }
    }
}
