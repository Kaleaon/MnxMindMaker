package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * HTTP client for external LLM APIs (Anthropic, OpenAI, Gemini).
 * Includes structured tool-call adapters with text fallback.
 */
class LlmApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * Backward-compatible plain text completion.
     */
    fun complete(settings: LlmSettings, systemPrompt: String, userMessage: String): String {
        val turn = completeAssistantTurn(
            settings = settings,
            systemPrompt = systemPrompt,
            transcript = listOf(JSONObject().put("role", "user").put("content", userMessage)),
            tools = emptyList()
        )
        return turn.text
    }

    /**
     * Structured completion supporting provider-native tool call formats.
     * Transcript format: [{"role":"user|assistant|tool", "content": ... }]
     */
    fun completeAssistantTurn(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): AssistantTurn {
        return when (settings.provider) {
            LlmProvider.ANTHROPIC -> callAnthropicTurn(settings, systemPrompt, transcript, tools)
            LlmProvider.OPENAI -> callOpenAITurn(settings, systemPrompt, transcript, tools)
            LlmProvider.GEMINI -> callGeminiTurn(settings, systemPrompt, transcript, tools)
            LlmProvider.VLLM_GEMMA4 -> callOpenAICompatibleTurn(settings, systemPrompt, transcript, tools)
        }
    }

    private fun callAnthropicTurn(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): AssistantTurn {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", settings.maxTokens)
            put("system", systemPrompt)
            put("messages", anthropicMessages(transcript))
            if (tools.isNotEmpty()) put("tools", anthropicTools(tools))
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
            parseAnthropicTurn(JSONObject(responseBody))
        }
    }

    private fun callOpenAITurn(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): AssistantTurn {
        val body = openAiBody(settings, systemPrompt, transcript, tools)
        val request = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(json))
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("content-type", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from OpenAI")
            if (!response.isSuccessful) throw LlmApiException("OpenAI error ${response.code}: $responseBody")
            parseOpenAITurn(JSONObject(responseBody))
        }
    }

    private fun callOpenAICompatibleTurn(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): AssistantTurn {
        val apiKey = settings.apiKey.ifBlank { "EMPTY" }
        val body = openAiBody(settings, systemPrompt, transcript, tools)
        val request = Request.Builder()
            .url("${settings.baseUrl}/chat/completions")
            .post(body.toString().toRequestBody(json))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from vLLM Gemma 4")
            if (!response.isSuccessful) throw LlmApiException("vLLM Gemma 4 error ${response.code}: $responseBody")
            parseOpenAITurn(JSONObject(responseBody))
        }
    }

    private fun callGeminiTurn(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): AssistantTurn {
        val fullPrompt = buildString {
            append(systemPrompt)
            append("\n\n")
            transcript.forEach { msg ->
                append(msg.optString("role", "user").uppercase())
                append(": ")
                append(msg.opt("content")?.toString() ?: "")
                append("\n")
            }
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", fullPrompt)))))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", settings.maxTokens)
                put("temperature", settings.temperature.toDouble())
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", geminiToolDeclarations(tools))))
            }
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
            parseGeminiTurn(JSONObject(responseBody))
        }
    }

    private fun openAiBody(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): JSONObject {
        return JSONObject().apply {
            put("model", settings.model)
            put("max_tokens", settings.maxTokens)
            put("temperature", settings.temperature.toDouble())
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                transcript.forEach { msg ->
                    val role = msg.optString("role", "user")
                    if (role == "tool") {
                        val content = msg.optJSONArray("content") ?: JSONArray()
                        for (i in 0 until content.length()) {
                            val toolResult = content.optJSONObject(i) ?: continue
                            put(
                                JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", toolResult.optString("tool_call_id", UUID.randomUUID().toString()))
                                    .put("content", toolResult.toString())
                            )
                        }
                    } else {
                        put(JSONObject().put("role", role).put("content", msg.opt("content")?.toString() ?: ""))
                    }
                }
            })
            if (tools.isNotEmpty()) put("tools", openAiTools(tools))
        }
    }

    private fun parseOpenAITurn(response: JSONObject): AssistantTurn {
        val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val text = message.optString("content", "")
        val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
        return AssistantTurn(
            text = text,
            toolInvocations = (0 until toolCalls.length()).mapNotNull { i ->
                val call = toolCalls.optJSONObject(i) ?: return@mapNotNull null
                val functionObj = call.optJSONObject("function") ?: return@mapNotNull null
                val argsRaw = functionObj.optString("arguments", "{}")
                val args = safeJsonObject(argsRaw)
                ToolInvocation(
                    id = call.optString("id", UUID.randomUUID().toString()),
                    name = functionObj.optString("name"),
                    arguments = args,
                    raw = call
                )
            },
            raw = response
        )
    }

    private fun parseAnthropicTurn(response: JSONObject): AssistantTurn {
        val content = response.optJSONArray("content") ?: JSONArray()
        val textParts = mutableListOf<String>()
        val invocations = mutableListOf<ToolInvocation>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> textParts += block.optString("text")
                "tool_use" -> invocations += ToolInvocation(
                    id = block.optString("id", UUID.randomUUID().toString()),
                    name = block.optString("name"),
                    arguments = block.optJSONObject("input") ?: JSONObject(),
                    raw = block
                )
            }
        }
        return AssistantTurn(text = textParts.joinToString("\n").trim(), toolInvocations = invocations, raw = response)
    }

    private fun parseGeminiTurn(response: JSONObject): AssistantTurn {
        val candidates = response.optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return AssistantTurn(text = "", raw = response)

        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val textParts = mutableListOf<String>()
        val invocations = mutableListOf<ToolInvocation>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.has("text")) {
                textParts += part.optString("text")
            }
            val functionCall = part.optJSONObject("functionCall")
            if (functionCall != null) {
                invocations += ToolInvocation(
                    id = functionCall.optString("id", UUID.randomUUID().toString()),
                    name = functionCall.optString("name"),
                    arguments = functionCall.optJSONObject("args") ?: JSONObject(),
                    raw = functionCall
                )
            }
        }
        return AssistantTurn(text = textParts.joinToString("\n").trim(), toolInvocations = invocations, raw = response)
    }

    private fun openAiTools(tools: List<ToolSpec>): JSONArray {
        return JSONArray().apply {
            tools.forEach { spec ->
                put(
                    JSONObject()
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", spec.inputSchema.takeIf { it.length() > 0 } ?: JSONObject().put("type", "object"))
                        )
                )
            }
        }
    }

    private fun anthropicTools(tools: List<ToolSpec>): JSONArray {
        return JSONArray().apply {
            tools.forEach { spec ->
                put(
                    JSONObject()
                        .put("name", spec.name)
                        .put("description", spec.description)
                        .put("input_schema", spec.inputSchema.takeIf { it.length() > 0 } ?: JSONObject().put("type", "object"))
                )
            }
        }
    }

    private fun anthropicMessages(transcript: List<JSONObject>): JSONArray {
        return JSONArray().apply {
            transcript.forEach { msg ->
                put(
                    JSONObject().put("role", msg.optString("role", "user")).put("content", msg.opt("content") ?: "")
                )
            }
        }
    }

    private fun geminiToolDeclarations(tools: List<ToolSpec>): JSONArray {
        return JSONArray().apply {
            tools.forEach { spec ->
                put(
                    JSONObject()
                        .put("name", spec.name)
                        .put("description", spec.description)
                        .put("parameters", spec.inputSchema.takeIf { it.length() > 0 } ?: JSONObject().put("type", "object"))
                )
            }
        }
    }

    private fun safeJsonObject(raw: String): JSONObject {
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject().put("raw", raw)
        }
    }
}

class LlmApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
