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

class LocalProvider(
    private val httpClient: OkHttpClient = defaultClient(),
    private val jsonMediaType: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
) : AssistantProvider {

    override val id: String = "local"

    override fun supports(settings: LlmSettings): Boolean =
        settings.provider == LlmProvider.VLLM_GEMMA4 || isOpenAiCompatibleSelfHosted(settings)

    override fun chat(request: ProviderRequest) = runCatching {
        val apiKey = request.settings.apiKey.ifBlank { "EMPTY" }
        val body = ProviderJsonAdapters.openAiBody(
            settings = request.settings,
            systemPrompt = request.systemPrompt,
            transcript = request.transcript,
            tools = request.tools
        )

        val httpRequest = Request.Builder()
            .url("${request.settings.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from local provider")
            if (!response.isSuccessful) throw LlmApiException("Local provider error ${response.code}: $responseBody")
            ProviderJsonAdapters.parseOpenAiTurn(JSONObject(responseBody))
        }
    }.getOrElse { throwable ->
        throw if (throwable is LlmApiException) throwable else LlmApiException("Local provider failure", throwable)
    }

    override fun healthCheck(settings: LlmSettings): ProviderHealth {
        if (!supports(settings)) return ProviderHealth(false, "Unsupported settings for LocalProvider")
        return try {
            val request = Request.Builder().url("${settings.baseUrl}/models").get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) ProviderHealth(true, "Local runtime reachable")
                else ProviderHealth(false, "Health check failed with HTTP ${response.code}")
            }
        } catch (e: Exception) {
            ProviderHealth(false, "Health check exception: ${e.message}")
        }
    }


    private fun isOpenAiCompatibleSelfHosted(settings: LlmSettings): Boolean {
        if (settings.provider != LlmProvider.OPENAI) return false
        val normalized = settings.baseUrl.lowercase()
        return !normalized.contains("api.openai.com")
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
