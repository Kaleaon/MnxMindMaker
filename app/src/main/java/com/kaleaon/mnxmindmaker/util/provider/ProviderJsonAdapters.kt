package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object ProviderJsonAdapters {

    fun openAiBody(
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

    fun openAiTools(tools: List<ToolSpec>): JSONArray {
        return JSONArray().apply {
            tools.forEach { spec ->
                put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function", JSONObject()
                                .put("name", spec.name)
                                .put("description", spec.description)
                                .put("parameters", spec.inputSchema.takeIf { it.length() > 0 } ?: JSONObject().put("type", "object"))
                        )
                )
            }
        }
    }

    fun geminiBody(
        settings: LlmSettings,
        systemPrompt: String,
        transcript: List<JSONObject>,
        tools: List<ToolSpec>
    ): JSONObject {
        return JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", settings.temperature.toDouble())
                    .put("maxOutputTokens", settings.maxTokens)
            )
            put("contents", geminiContents(transcript))
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("functionDeclarations", geminiFunctionDeclarations(tools))
                    )
                )
            }
        }
    }

    fun anthropicTools(tools: List<ToolSpec>): JSONArray {
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

    fun anthropicMessages(transcript: List<JSONObject>): JSONArray {
        return JSONArray().apply {
            transcript.forEach { msg ->
                put(JSONObject().put("role", msg.optString("role", "user")).put("content", msg.opt("content") ?: ""))
            }
        }
    }

    fun geminiContents(transcript: List<JSONObject>): JSONArray {
        return JSONArray().apply {
            transcript.forEach { msg ->
                when (msg.optString("role", "user")) {
                    "assistant" -> put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", msg.optString("content")))))
                    "tool" -> {
                        val content = msg.optJSONArray("content") ?: JSONArray()
                        for (i in 0 until content.length()) {
                            val toolResult = content.optJSONObject(i) ?: continue
                            put(
                                JSONObject()
                                    .put("role", "user")
                                    .put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "functionResponse",
                                                JSONObject()
                                                    .put("name", toolResult.optString("name", "tool_result"))
                                                    .put(
                                                        "response",
                                                        toolResult.optJSONObject("result")
                                                            ?: safeJsonObject(toolResult.toString())
                                                    )
                                            )
                                        )
                                    )
                            )
                        }
                    }
                    else -> put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", msg.optString("content")))))
                }
            }
        }
    }

    fun parseOpenAiTurn(response: JSONObject): AssistantTurn {
        val message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val text = message.optString("content", "")
        val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()
        val invocations = (0 until toolCalls.length()).mapNotNull { i ->
            val call = toolCalls.optJSONObject(i) ?: return@mapNotNull null
            val functionObj = call.optJSONObject("function") ?: return@mapNotNull null
            ToolInvocation(
                id = call.optString("id", UUID.randomUUID().toString()),
                toolName = functionObj.optString("name"),
                argumentsJson = safeJsonObject(functionObj.optString("arguments", "{}"))
            )
        }
        return AssistantTurn(text = text, toolInvocations = invocations, raw = response)
    }

    fun parseAnthropicTurn(response: JSONObject): AssistantTurn {
        val content = response.optJSONArray("content") ?: JSONArray()
        val textParts = mutableListOf<String>()
        val invocations = mutableListOf<ToolInvocation>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> textParts += block.optString("text")
                "tool_use" -> invocations += ToolInvocation(
                    id = block.optString("id", UUID.randomUUID().toString()),
                    toolName = block.optString("name"),
                    argumentsJson = block.optJSONObject("input") ?: JSONObject()
                )
            }
        }
        return AssistantTurn(text = textParts.joinToString("\n").trim(), toolInvocations = invocations, raw = response)
    }

    fun parseGeminiTurn(response: JSONObject): AssistantTurn {
        val candidates = response.optJSONArray("candidates") ?: JSONArray()
        val first = candidates.optJSONObject(0) ?: JSONObject()
        val content = first.optJSONObject("content") ?: JSONObject()
        val parts = content.optJSONArray("parts") ?: JSONArray()
        val textParts = mutableListOf<String>()
        val invocations = mutableListOf<ToolInvocation>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = part.optString("text")
            if (text.isNotBlank()) textParts += text

            val functionCall = part.optJSONObject("functionCall")
            if (functionCall != null) {
                val argsAny = functionCall.opt("args")
                val args = when (argsAny) {
                    is JSONObject -> argsAny
                    is String -> safeJsonObject(argsAny)
                    else -> JSONObject()
                }
                invocations += ToolInvocation(
                    id = UUID.randomUUID().toString(),
                    toolName = functionCall.optString("name"),
                    argumentsJson = args
                )
            }
        }
        return AssistantTurn(text = textParts.joinToString("\n").trim(), toolInvocations = invocations, raw = response)
    }

    private fun geminiFunctionDeclarations(tools: List<ToolSpec>): JSONArray {
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

    fun safeJsonObject(raw: String): JSONObject {
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw) }
    }
}
