package com.kaleaon.mnxmindmaker.repository

import kotlinx.serialization.Serializable

@Serializable
data class PersistedChatStore(
    val schemaVersion: Int = ChatPersistenceSchema.CURRENT_VERSION,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val updatedTimestamp: Long = System.currentTimeMillis(),
    val activeSessionId: String = "",
    val sessions: List<PersistedChatSession> = emptyList()
)

@Serializable
data class PersistedChatSession(
    val sessionId: String,
    val displayName: String = "New Thread",
    val createdTimestamp: Long = System.currentTimeMillis(),
    val updatedTimestamp: Long = System.currentTimeMillis(),
    val conversationMode: String = "multi_actor",
    val providerLabel: String = "Auto",
    val modelLabel: String = "",
    val activeParticipants: List<String> = emptyList(),
    val messages: List<PersistedChatMessage> = emptyList()
)

@Serializable
data class PersistedChatMessage(
    val id: String,
    val prompt: String = "",
    val response: String = "",
    val turns: List<PersistedChatTurn> = emptyList(),
    val role: String = "MIND",
    val actorId: String = "mind",
    val actorLabel: String = "Mind",
    val isAiGenerated: Boolean = true,
    val content: String = "",
    val addressedActorIds: List<String>? = null,
    val replyToMessageId: String? = null,
    val providerChoice: String = "AUTO",
    val provider: String = "OPENAI",
    val model: String = "",
    val toolCalls: List<String> = emptyList(),
    val latencyMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val compareProvider: String? = null,
    val compareModel: String? = null,
    val compareResponse: String? = null,
    val compareLatencyMs: Long? = null,
    val compareTotalTokens: Int? = null,
    val createdTimestamp: Long = System.currentTimeMillis()
)

@Serializable
data class PersistedChatTurn(
    val actor: String,
    val content: String,
    val createdTimestamp: Long = System.currentTimeMillis()
)

object ChatPersistenceSchema {
    const val CURRENT_VERSION = 2
}
