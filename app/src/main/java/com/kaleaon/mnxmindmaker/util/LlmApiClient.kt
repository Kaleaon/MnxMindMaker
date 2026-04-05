package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for external LLM APIs (Anthropic, OpenAI, Gemini).
 * Used to request AI assistance when designing a mind graph.
 */
class LlmApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * Send a prompt to the configured LLM and return the text response.
     * Throws [LlmApiException] on error.
     */
    fun complete(settings: LlmSettings, systemPrompt: String, userMessage: String): String {
        return when (settings.provider) {
            LlmProvider.ANTHROPIC -> callAnthropic(settings, systemPrompt, userMessage)
            LlmProvider.OPENAI -> callOpenAI(settings, systemPrompt, userMessage)
            LlmProvider.GEMINI -> callGemini(settings, systemPrompt, userMessage)
            LlmProvider.VLLM_GEMMA4 -> callOpenAICompatible(settings, systemPrompt, userMessage)
        }
    }

    private fun callAnthropic(settings: LlmSettings, systemPrompt: String, userMessage: String): String {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", settings.maxTokens)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            }))
        }
        val request = Request.Builder()
            .url("${settings.baseUrl}/messages")
            .post(body.toString().toRequestBody(json))
            .addHeader("x-api-key", settings.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from Anthropic")
            if (!response.isSuccessful) throw LlmApiException("Anthropic error ${response.code}: $responseBody")
            val json = JSONObject(responseBody)
            json.getJSONArray("content").getJSONObject(0).getString("text")
        }
    }

    private fun callOpenAI(settings: LlmSettings, systemPrompt: String, userMessage: String): String {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", settings.maxTokens)
            put("temperature", settings.temperature.toDouble())
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
            })
        }
        val request = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(json))
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("content-type", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from OpenAI")
            if (!response.isSuccessful) throw LlmApiException("OpenAI error ${response.code}: $responseBody")
            val jsonObj = JSONObject(responseBody)
            jsonObj.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    private fun callOpenAICompatible(settings: LlmSettings, systemPrompt: String, userMessage: String): String {
        val apiKey = settings.apiKey.ifBlank { "EMPTY" }
        val body = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", settings.maxTokens)
            put("temperature", settings.temperature.toDouble())
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
            })
        }
        val request = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(json))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from vLLM Gemma 4")
            if (!response.isSuccessful) throw LlmApiException("vLLM Gemma 4 error ${response.code}: $responseBody")
            val jsonObj = JSONObject(responseBody)
            jsonObj.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    private fun callGemini(settings: LlmSettings, systemPrompt: String, userMessage: String): String {
        val fullPrompt = "$systemPrompt\n\n$userMessage"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", fullPrompt)
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", settings.maxTokens)
                put("temperature", settings.temperature.toDouble())
            })
        }
        val url = "${settings.baseUrl}/models/${settings.model}:generateContent?key=${settings.apiKey}"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(json))
            .addHeader("content-type", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from Gemini")
            if (!response.isSuccessful) throw LlmApiException("Gemini error ${response.code}: $responseBody")
            val jsonObj = JSONObject(responseBody)
            jsonObj.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text")
        }
    }
}

class LlmApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
