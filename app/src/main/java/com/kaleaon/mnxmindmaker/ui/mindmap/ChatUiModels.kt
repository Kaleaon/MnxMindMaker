package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.model.LlmProvider

enum class ComposerProviderChoice(val label: String) {
    AUTO("Auto"),
    LOCAL("Local"),
    CLAUDE("Claude"),
    CHATGPT("ChatGPT"),
    GEMINI("Gemini"),
    VLLM("vLLM")
}

data class MessageProvenance(
    val provider: LlmProvider,
    val model: String,
    val toolCalls: List<String> = emptyList(),
    val failoverEvents: List<FailoverEvent> = emptyList(),
    val latencyMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

data class FailoverEvent(
    val reasonCode: String,
    val message: String
)

data class ChatMessage(
    val id: String,
    val prompt: String,
    val response: String,
    val providerChoice: ComposerProviderChoice,
    val provenance: MessageProvenance,
    val compareCandidate: CompareCandidate? = null
)

data class CompareCandidate(
    val provider: LlmProvider,
    val model: String,
    val response: String,
    val latencyMs: Long? = null,
    val totalTokens: Int? = null
)
