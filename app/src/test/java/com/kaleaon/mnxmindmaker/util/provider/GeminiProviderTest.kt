package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.defaultModel
import com.kaleaon.mnxmindmaker.util.tooling.ToolOperationClass
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProviderTest {

    @Test
    fun `supports matches only gemini provider`() {
        val provider = GeminiProvider()

        assertTrue(provider.supports(LlmSettings(provider = LlmProvider.GEMINI)))
        assertFalse(provider.supports(LlmSettings(provider = LlmProvider.OPENAI)))
        assertFalse(provider.supports(LlmSettings(provider = LlmProvider.ANTHROPIC)))
        assertFalse(provider.supports(LlmSettings(provider = LlmProvider.LOCAL_ON_DEVICE)))
    }

    @Test
    fun `gemini body maps transcript and tools`() {
        val body = ProviderJsonAdapters.geminiBody(
            settings = LlmSettings(provider = LlmProvider.GEMINI, maxTokens = 321, temperature = 0.25f),
            systemPrompt = "You are concise",
            transcript = listOf(
                JSONObject().put("role", "user").put("content", "hello"),
                JSONObject().put("role", "assistant").put("content", "hi"),
                JSONObject().put(
                    "role",
                    "tool"
                ).put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "fetch_weather")
                            .put("result", JSONObject().put("temp", 72))
                    )
                )
            ),
            tools = listOf(
                ToolSpec(
                    name = "fetch_weather",
                    description = "Fetch weather by city",
                    operationClass = ToolOperationClass.READ_ONLY,
                    inputSchema = JSONObject().put("type", "object")
                )
            )
        )

        assertEquals("You are concise", body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(321, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertEquals(0.25, body.getJSONObject("generationConfig").getDouble("temperature"), 0.0001)
        assertEquals(3, body.getJSONArray("contents").length())
        assertEquals("functionDeclarations", body.getJSONArray("tools").getJSONObject(0).keys().next())
    }

    @Test
    fun `parse gemini turn returns text and tool call`() {
        val turn = ProviderJsonAdapters.parseGeminiTurn(
            JSONObject(
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text":"I'll check that."},
                          {"functionCall":{"name":"lookup","args":{"q":"mnx"}}}
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertEquals("I'll check that.", turn.text)
        assertEquals(1, turn.toolInvocations.size)
        assertEquals("lookup", turn.toolInvocations.first().toolName)
        assertEquals("mnx", turn.toolInvocations.first().argumentsJson.getString("q"))
    }

    @Test
    fun `gemini defaults remain compatible with settings`() {
        val defaults = LlmSettings(provider = LlmProvider.GEMINI)

        assertEquals(LlmProvider.GEMINI.baseUrl, defaults.baseUrl)
        assertEquals(LlmProvider.GEMINI.defaultModel(), defaults.model)
    }
}
