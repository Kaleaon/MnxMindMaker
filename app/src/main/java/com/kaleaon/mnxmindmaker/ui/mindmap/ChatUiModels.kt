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

enum class ChatRole {
    USER,
    MIND,
    SYSTEM
}

data class FailoverEvent(
    val reasonCode: String,
    val message: String
)

data class ChatMessage(
    val id: String,
    val role: ChatRole = ChatRole.MIND,
    val actorId: String = "mind",
    val actorLabel: String = "Mind",
    val content: String = "",
    val createdTimestamp: Long = System.currentTimeMillis(),
    val isAiGenerated: Boolean = true,
    val providerChoice: ComposerProviderChoice = ComposerProviderChoice.AUTO,
    val provenance: MessageProvenance? = null,
    val addressedActorIds: List<String>? = null,
    val replyToMessageId: String? = null,
    val compareCandidate: CompareCandidate? = null,
    // Legacy fields kept for backward compatibility during schema migration.
    val prompt: String? = null,
    val response: String? = null
)

data class ChatSessionSummary(
    val sessionId: String,
    val displayName: String,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val providerLabel: String,
    val modelLabel: String,
    val messageCount: Int
)

data class CompareCandidate(
    val provider: LlmProvider,
    val model: String,
    val response: String,
    val latencyMs: Long? = null,
    val totalTokens: Int? = null
)
