package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONObject
import java.io.File

data class ProviderRequest(
    val settings: LlmSettings,
    val systemPrompt: String,
    val transcript: List<JSONObject>,
    val tools: List<ToolSpec> = emptyList()
)

data class ProviderHealth(
    val ok: Boolean,
    val message: String
)

interface AssistantProvider {
    val id: String

    fun supports(settings: LlmSettings): Boolean

    fun chat(request: ProviderRequest): AssistantTurn

    fun streamChat(request: ProviderRequest, onToken: (String) -> Unit = {}): AssistantTurn {
        val turn = chat(request)
        if (turn.text.isNotBlank()) {
            turn.text.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
                onToken("$token ")
            }
        }
        return turn
    }

    fun embed(settings: LlmSettings, input: List<String>): List<List<Float>> {
        throw UnsupportedOperationException("Embedding not implemented for provider $id")
    }

    fun transcribe(settings: LlmSettings, audioFile: File): String {
        throw UnsupportedOperationException("Transcription not implemented for provider $id")
    }

    fun healthCheck(settings: LlmSettings): ProviderHealth
}
