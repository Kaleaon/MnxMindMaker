package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class ChatSessionRepository(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val storageFile = File(context.filesDir, fileName)

    fun loadState(): PersistedChatStore = synchronized(lock) {
        loadStateLocked()
    }

    fun createSession(displayName: String?, providerLabel: String = "Auto"): PersistedChatStore = synchronized(lock) {
        val state = loadStateLocked()
        val now = System.currentTimeMillis()
        val nextIndex = (state.sessions.maxOfOrNull { sessionNameIndex(it.displayName) } ?: 0) + 1
        val session = PersistedChatSession(
            sessionId = UUID.randomUUID().toString(),
            displayName = displayName?.takeIf { it.isNotBlank() } ?: "Thread $nextIndex",
            createdTimestamp = now,
            updatedTimestamp = now,
            providerLabel = providerLabel
        )
        val updated = state.copy(
            updatedTimestamp = now,
            activeSessionId = session.sessionId,
            sessions = state.sessions + session
        )
        saveStateLocked(updated)
        updated
    }

    fun setActiveSession(sessionId: String): PersistedChatStore = synchronized(lock) {
        val state = loadStateLocked()
        val resolvedActiveId = if (state.sessions.any { it.sessionId == sessionId }) sessionId else state.activeSessionId
        val updated = state.copy(
            updatedTimestamp = System.currentTimeMillis(),
            activeSessionId = resolvedActiveId
        )
        saveStateLocked(updated)
        updated
    }

    fun upsertSession(session: PersistedChatSession): PersistedChatStore = synchronized(lock) {
        val state = loadStateLocked()
        val now = System.currentTimeMillis()
        val updatedSession = session.copy(updatedTimestamp = now)
        val updatedSessions = state.sessions
            .filterNot { it.sessionId == session.sessionId } + updatedSession
        val updated = state.copy(
            updatedTimestamp = now,
            activeSessionId = state.activeSessionId.ifBlank { updatedSession.sessionId },
            sessions = updatedSessions.sortedBy { it.createdTimestamp }
        )
        saveStateLocked(updated)
        updated
    }

    fun appendMessage(sessionId: String, message: PersistedChatMessage): PersistedChatStore = synchronized(lock) {
        val state = loadStateLocked()
        val now = System.currentTimeMillis()
        val updatedSessions = state.sessions.map { session ->
            if (session.sessionId != sessionId) {
                session
            } else {
                session.copy(
                    updatedTimestamp = now,
                    providerLabel = message.provider,
                    modelLabel = message.model,
                    messages = session.messages + message
                )
            }
        }
        val updated = state.copy(
            updatedTimestamp = now,
            activeSessionId = sessionId,
            sessions = updatedSessions
        )
        saveStateLocked(updated)
        updated
    }

    fun replaceMessages(sessionId: String, messages: List<PersistedChatMessage>): PersistedChatStore = synchronized(lock) {
        val state = loadStateLocked()
        val now = System.currentTimeMillis()
        val updatedSessions = state.sessions.map { session ->
            if (session.sessionId != sessionId) session else session.copy(updatedTimestamp = now, messages = messages)
        }
        val updated = state.copy(updatedTimestamp = now, sessions = updatedSessions)
        saveStateLocked(updated)
        updated
    }

    fun updateActiveParticipants(sessionId: String, participantIds: Set<String>): PersistedChatStore = synchronized(lock) {
        val state = loadStateLocked()
        val now = System.currentTimeMillis()
        val normalized = participantIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
        val updatedSessions = state.sessions.map { session ->
            if (session.sessionId != sessionId) session else session.copy(
                updatedTimestamp = now,
                activeParticipants = normalized
            )
        }
        val updated = state.copy(updatedTimestamp = now, sessions = updatedSessions)
        saveStateLocked(updated)
        updated
    }

    fun ensureDefaultSession(): PersistedChatStore = synchronized(lock) {
        val state = loadStateLocked()
        if (state.sessions.isNotEmpty() && state.activeSessionId.isNotBlank()) return@synchronized state

        val now = System.currentTimeMillis()
        val default = PersistedChatSession(
            sessionId = UUID.randomUUID().toString(),
            displayName = "Thread 1",
            createdTimestamp = now,
            updatedTimestamp = now,
            providerLabel = "Auto"
        )
        val seeded = state.copy(
            schemaVersion = ChatPersistenceSchema.CURRENT_VERSION,
            createdTimestamp = state.createdTimestamp.takeIf { it > 0 } ?: now,
            updatedTimestamp = now,
            activeSessionId = default.sessionId,
            sessions = listOf(default)
        )
        saveStateLocked(seeded)
        seeded
    }

    private fun loadStateLocked(): PersistedChatStore {
        if (!storageFile.exists()) {
            val initial = PersistedChatStore()
            saveStateLocked(initial)
            return initial
        }

        return try {
            val raw = storageFile.readText()
            val payload = json.parseToJsonElement(raw).jsonObject
            val version = payload[SCHEMA_VERSION_FIELD]?.jsonPrimitive?.intOrNull ?: 1
            val migratedPayload = migratePayload(payload, version)
            val decoded = json.decodeFromString<PersistedChatStore>(migratedPayload.toString())
            decoded.copy(
                schemaVersion = ChatPersistenceSchema.CURRENT_VERSION,
                activeSessionId = decoded.activeSessionId.ifBlank { decoded.sessions.firstOrNull()?.sessionId.orEmpty() }
            )
        } catch (_: SerializationException) {
            recoverFromCorruptionLocked()
        } catch (_: IllegalArgumentException) {
            recoverFromCorruptionLocked()
        }
    }

    private fun migratePayload(payload: JsonObject, version: Int): JsonObject {
        return when {
            version >= ChatPersistenceSchema.CURRENT_VERSION -> payload
            else -> payload
        }
    }

    private fun recoverFromCorruptionLocked(): PersistedChatStore {
        val resetState = PersistedChatStore()
        saveStateLocked(resetState)
        return resetState
    }

    private fun saveStateLocked(state: PersistedChatStore) {
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(json.encodeToString(state))
    }

    private fun sessionNameIndex(name: String): Int {
        return name.removePrefix("Thread ").toIntOrNull() ?: 0
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "chat_sessions.json"
        private const val SCHEMA_VERSION_FIELD = "schemaVersion"
    }
}
