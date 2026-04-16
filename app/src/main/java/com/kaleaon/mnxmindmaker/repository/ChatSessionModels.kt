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
    val providerLabel: String = "Auto",
    val modelLabel: String = "",
    val activeParticipants: List<String> = emptyList(),
    val messages: List<PersistedChatMessage> = emptyList()
)

@Serializable
data class PersistedChatMessage(
    val id: String,
    val prompt: String,
    val response: String,
    val role: String = "MIND",
    val actorLabel: String? = null,
    val isAiGenerated: Boolean = true,
    val role: String = "MIND",
    val actorId: String = "mind",
    val actorLabel: String = "Mind",
    val content: String = "",
    val addressedActorIds: List<String>? = null,
    val replyToMessageId: String? = null,
    // Legacy fields retained to preserve backward compatibility for schema migration.
    val prompt: String = "",
    val response: String = "",
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

object ChatPersistenceSchema {
    const val CURRENT_VERSION = 2
}
