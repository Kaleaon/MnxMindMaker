package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.LlmApiException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ClaudeProvider(
    private val httpClient: OkHttpClient = defaultClient(),
    private val jsonMediaType: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
) : AssistantProvider {

    override val id: String = "claude"

    override fun supports(settings: LlmSettings): Boolean = settings.provider == LlmProvider.ANTHROPIC

    override fun chat(request: ProviderRequest) = runCatching {
        val body = JSONObject().apply {
            put("model", request.settings.model)
            put("max_tokens", request.settings.maxTokens)
            put("system", request.systemPrompt)
            put("messages", ProviderJsonAdapters.anthropicMessages(request.transcript))
            if (request.tools.isNotEmpty()) put("tools", ProviderJsonAdapters.anthropicTools(request.tools))
        }

        val httpRequest = Request.Builder()
            .url("${request.settings.baseUrl}/messages")
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("x-api-key", request.settings.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from Anthropic")
            if (!response.isSuccessful) throw LlmApiException("Anthropic error ${response.code}: $responseBody")
            ProviderJsonAdapters.parseAnthropicTurn(JSONObject(responseBody))
        }
    }.getOrElse { throwable ->
        throw if (throwable is LlmApiException) throwable else LlmApiException("Claude provider failure", throwable)
    }

    override fun healthCheck(settings: LlmSettings): ProviderHealth {
        if (!supports(settings)) return ProviderHealth(false, "Unsupported settings for ClaudeProvider")
        return if (settings.apiKey.isBlank()) ProviderHealth(false, "Missing API key") else ProviderHealth(true, "API key configured")
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
