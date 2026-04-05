package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cooperative cancellation token for local generation streams.
 */
class LlmCancellationToken {
    @Volatile
    var isCancelled: Boolean = false

    fun cancel() {
        isCancelled = true
    }
}

/**
 * Runtime metadata for an on-device model backend.
 */
data class LocalModelMetadata(
    val backendId: String,
    val modelName: String,
    val runtimePath: String,
    val contextWindowTokens: Int,
    val supportsStreaming: Boolean,
    val supportsCancellation: Boolean
)

/**
 * Contract for pluggable local on-device backends.
 */
interface LocalOnDeviceBackend {
    val backendId: String
    fun contextWindow(settings: LlmSettings): Int
    fun metadata(settings: LlmSettings): LocalModelMetadata
    fun streamCompletion(
        settings: LlmSettings,
        systemPrompt: String,
        userMessage: String,
        cancellation: LlmCancellationToken = LlmCancellationToken(),
        onToken: (String) -> Unit = {}
    ): String
}

/**
 * First local adapter: OpenAI-compatible runtime addressed by a local runtime path/profile.
 */
class LocalOpenAiRuntimeBackend(
    private val httpClient: OkHttpClient,
    private val json: okhttp3.MediaType
) : LocalOnDeviceBackend {

    override val backendId: String = "openai-compatible-local"

    override fun contextWindow(settings: LlmSettings): Int = settings.capabilities.contextWindowTokens

    override fun metadata(settings: LlmSettings): LocalModelMetadata {
        val modelName = if (settings.localModelPath.isBlank()) {
            settings.model
        } else {
            File(settings.localModelPath).name.ifBlank { settings.model }
        }
        return LocalModelMetadata(
            backendId = backendId,
            modelName = modelName,
            runtimePath = settings.localModelPath,
            contextWindowTokens = contextWindow(settings),
            supportsStreaming = true,
            supportsCancellation = true
        )
    }

    override fun streamCompletion(
        settings: LlmSettings,
        systemPrompt: String,
        userMessage: String,
        cancellation: LlmCancellationToken,
        onToken: (String) -> Unit
    ): String {
        if (cancellation.isCancelled) throw LlmApiException("Cancelled")

        val apiKey = settings.apiKey.ifBlank { "EMPTY" }
        val body = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", settings.maxTokens)
            put("temperature", settings.temperature.toDouble())
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
            })
            put("stream", false)
            put("extra_body", JSONObject().apply {
                put("local_model_path", settings.localModelPath)
                put("profile", settings.localProfile.name.lowercase())
            })
        }

        val request = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(json))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .build()

        val fullResponse = httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from local runtime")
            if (!response.isSuccessful) throw LlmApiException("Local runtime error ${response.code}: $responseBody")
            val jsonObj = JSONObject(responseBody)
            jsonObj.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }

        fullResponse.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .forEach { token ->
                if (cancellation.isCancelled) throw LlmApiException("Cancelled")
                onToken("$token ")
            }

        return fullResponse
    }
}

/**
 * HTTP client for external LLM APIs (Anthropic, OpenAI, Gemini).
 * Used to request AI assistance when designing a mind graph.
 */
class LlmApiClient(
    localBackend: LocalOnDeviceBackend? = null
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private val onDeviceBackends: Map<String, LocalOnDeviceBackend>

    init {
        val defaultBackend = localBackend ?: LocalOpenAiRuntimeBackend(httpClient, json)
        onDeviceBackends = mapOf(defaultBackend.backendId to defaultBackend)
    }

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
            LlmProvider.LOCAL_ON_DEVICE -> resolveOnDeviceBackend(settings).streamCompletion(
                settings = settings,
                systemPrompt = systemPrompt,
                userMessage = userMessage
            )
        }
    }

    fun localMetadata(settings: LlmSettings): LocalModelMetadata? {
        if (settings.provider != LlmProvider.LOCAL_ON_DEVICE) return null
        return resolveOnDeviceBackend(settings).metadata(settings)
    }

    private fun resolveOnDeviceBackend(settings: LlmSettings): LocalOnDeviceBackend {
        return onDeviceBackends.values.firstOrNull()
            ?: throw LlmApiException("No on-device backend is registered")
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
