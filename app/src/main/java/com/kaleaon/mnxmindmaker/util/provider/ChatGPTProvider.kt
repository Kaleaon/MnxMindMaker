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

class ChatGPTProvider(
    private val httpClient: OkHttpClient = defaultClient(),
    private val jsonMediaType: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
) : AssistantProvider {

    override val id: String = "chatgpt"

    override fun supports(settings: LlmSettings): Boolean = settings.provider == LlmProvider.OPENAI

    override fun chat(request: ProviderRequest) = runCatching {
        val body = ProviderJsonAdapters.openAiBody(
            settings = request.settings,
            systemPrompt = request.systemPrompt,
            transcript = request.transcript,
            tools = request.tools
        )

        val httpRequest = Request.Builder()
            .url("${request.settings.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer ${request.settings.apiKey}")
            .addHeader("content-type", "application/json")
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from OpenAI")
            if (!response.isSuccessful) throw LlmApiException("OpenAI error ${response.code}: $responseBody")
            ProviderJsonAdapters.parseOpenAiTurn(JSONObject(responseBody))
        }
    }.getOrElse { throwable ->
        throw if (throwable is LlmApiException) throwable else LlmApiException("ChatGPT provider failure", throwable)
    }

    override fun healthCheck(settings: LlmSettings): ProviderHealth {
        if (!supports(settings)) return ProviderHealth(false, "Unsupported settings for ChatGPTProvider")
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
