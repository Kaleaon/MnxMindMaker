package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        if (state.sessions.isNotEmpty()) {
            val seededExisting = state.copy(
                schemaVersion = ChatPersistenceSchema.CURRENT_VERSION,
                createdTimestamp = state.createdTimestamp.takeIf { it > 0 } ?: now,
                updatedTimestamp = now,
                activeSessionId = state.sessions.first().sessionId
            )
            saveStateLocked(seededExisting)
            return@synchronized seededExisting
        }

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
            val normalized = decoded.copy(
                schemaVersion = ChatPersistenceSchema.CURRENT_VERSION,
                activeSessionId = decoded.activeSessionId.ifBlank { decoded.sessions.firstOrNull()?.sessionId.orEmpty() }
            )
            if (normalized != decoded || version != ChatPersistenceSchema.CURRENT_VERSION || migratedPayload != payload) {
                saveStateLocked(normalized)
            }
            normalized
        } catch (_: SerializationException) {
            recoverFromCorruptionLocked()
        } catch (_: IllegalArgumentException) {
            recoverFromCorruptionLocked()
        }
    }

    private fun migratePayload(payload: JsonObject, version: Int): JsonObject {
        var migrated = payload
        var workingVersion = version

        if (workingVersion < 2) {
            migrated = migrateV1ToV2(migrated)
            workingVersion = 2
        }

        migrated = migratedWithSafeTurns(migrated)

        return if (workingVersion == ChatPersistenceSchema.CURRENT_VERSION) migrated else {
            buildJsonObject {
                migrated.forEach { (key, value) -> put(key, value) }
                put(SCHEMA_VERSION_FIELD, ChatPersistenceSchema.CURRENT_VERSION)
            }
        }
    }

    private fun migrateV1ToV2(payload: JsonObject): JsonObject {
        val sessions = payload[SESSIONS_FIELD]?.jsonArray ?: JsonArray(emptyList())
        val migratedSessions = buildJsonArray {
            sessions.forEach { sessionElement ->
                val session = sessionElement.jsonObject
                add(
                    buildJsonObject {
                        session.forEach { (key, value) -> put(key, value) }
                        val messages = session[MESSAGES_FIELD]?.jsonArray ?: JsonArray(emptyList())
                        putJsonArray(MESSAGES_FIELD) {
                            messages.forEach { messageElement ->
                                add(migrateV1MessageToV2(messageElement.jsonObject))
                            }
                        }
                    }
                )
            }
        }
        return buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put(SCHEMA_VERSION_FIELD, 2)
            put(SESSIONS_FIELD, migratedSessions)
        }
    }

    private fun migrateV1MessageToV2(message: JsonObject): JsonObject {
        val prompt = message[PROMPT_FIELD]?.jsonPrimitive?.contentOrNull.orEmpty()
        val response = message[RESPONSE_FIELD]?.jsonPrimitive?.contentOrNull.orEmpty()
        val created = message[CREATED_TIMESTAMP_FIELD]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        val assistantActor = message[PROVIDER_FIELD]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?: ASSISTANT_ACTOR

        return buildJsonObject {
            message.forEach { (key, value) -> put(key, value) }
            putJsonArray(TURNS_FIELD) {
                if (prompt.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put(ACTOR_FIELD, USER_ACTOR)
                            put(CONTENT_FIELD, prompt)
                            put(CREATED_TIMESTAMP_FIELD, created)
                        }
                    )
                }
                if (response.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put(ACTOR_FIELD, assistantActor)
                            put(CONTENT_FIELD, response)
                            put(CREATED_TIMESTAMP_FIELD, created)
                        }
                    )
                }
            }
        }
    }

    private fun migratedWithSafeTurns(payload: JsonObject): JsonObject {
        val sessions = payload[SESSIONS_FIELD]?.jsonArray ?: return payload
        val repairedSessions = buildJsonArray {
            sessions.forEach { sessionElement ->
                val session = sessionElement.jsonObject
                val messages = session[MESSAGES_FIELD]?.jsonArray ?: JsonArray(emptyList())
                add(
                    buildJsonObject {
                        session.forEach { (key, value) -> put(key, value) }
                        putJsonArray(MESSAGES_FIELD) {
                            messages.forEach { messageElement ->
                                add(repairMessageTurns(messageElement.jsonObject))
                            }
                        }
                    }
                )
            }
        }
        return buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            put(SESSIONS_FIELD, repairedSessions)
            put(SCHEMA_VERSION_FIELD, ChatPersistenceSchema.CURRENT_VERSION)
        }
    }

    private fun repairMessageTurns(message: JsonObject): JsonObject {
        val prompt = message[PROMPT_FIELD]?.jsonPrimitive?.contentOrNull.orEmpty()
        val response = message[RESPONSE_FIELD]?.jsonPrimitive?.contentOrNull.orEmpty()
        val created = message[CREATED_TIMESTAMP_FIELD]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        val assistantActor = message[PROVIDER_FIELD]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?: ASSISTANT_ACTOR
        val existingTurns = message[TURNS_FIELD]?.jsonArray

        val hasUsableTurns = existingTurns != null && existingTurns.any { turnElement ->
            val turn = turnElement.jsonObject
            val actor = turn[ACTOR_FIELD]?.jsonPrimitive?.contentOrNull
            val content = turn[CONTENT_FIELD]?.jsonPrimitive?.contentOrNull
            !actor.isNullOrBlank() && !content.isNullOrBlank()
        }

        if (hasUsableTurns) return message

        return buildJsonObject {
            message.forEach { (key, value) -> put(key, value) }
            putJsonArray(TURNS_FIELD) {
                if (prompt.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put(ACTOR_FIELD, USER_ACTOR)
                            put(CONTENT_FIELD, prompt)
                            put(CREATED_TIMESTAMP_FIELD, created)
                        }
                    )
                }
                if (response.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put(ACTOR_FIELD, assistantActor)
                            put(CONTENT_FIELD, response)
                            put(CREATED_TIMESTAMP_FIELD, created)
                        }
                    )
                }
            }
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
        private const val SESSIONS_FIELD = "sessions"
        private const val MESSAGES_FIELD = "messages"
        private const val TURNS_FIELD = "turns"
        private const val PROMPT_FIELD = "prompt"
        private const val RESPONSE_FIELD = "response"
        private const val PROVIDER_FIELD = "provider"
        private const val CREATED_TIMESTAMP_FIELD = "createdTimestamp"
        private const val ACTOR_FIELD = "actor"
        private const val CONTENT_FIELD = "content"
        private const val USER_ACTOR = "USER"
        private const val ASSISTANT_ACTOR = "ASSISTANT"

        internal fun migratePayloadForVersion(payload: JsonObject, version: Int): JsonObject {
            if (version >= ChatPersistenceSchema.CURRENT_VERSION) return payload

            var migrated = payload
            var currentVersion = version
            while (currentVersion < ChatPersistenceSchema.CURRENT_VERSION) {
                migrated = when (currentVersion) {
                    1 -> migrateV1ToV2(migrated)
                    else -> migrated
                }
                currentVersion += 1
            }
            return migrated
        }

        private fun migrateV1ToV2(payload: JsonObject): JsonObject {
            val sessions = payload["sessions"]?.jsonArray ?: JsonArray(emptyList())
            val upgradedSessions = buildJsonArray {
                sessions.forEach { element ->
                    val session = element.jsonObject
                    val upgradedMessages = buildJsonArray {
                        session["messages"]?.jsonArray?.forEach { messageElement ->
                            val message = messageElement.jsonObject
                            add(
                                buildJsonObject {
                                    message.forEach { (key, value) -> put(key, value) }
                                    if (message["actorLabel"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                                        put("actorLabel", "assistant")
                                    }
                                }
                            )
                        }
                    }
                    add(
                        buildJsonObject {
                            session.forEach { (key, value) -> put(key, value) }
                            if (session["conversationMode"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                                put("conversationMode", "multi_actor")
                            }
                            put("messages", upgradedMessages)
                        }
                    )
                }
            }

            return buildJsonObject {
                payload.forEach { (key, value) -> put(key, value) }
                put(SCHEMA_VERSION_FIELD, ChatPersistenceSchema.CURRENT_VERSION)
                put("sessions", upgradedSessions)
            }
        }
    }
}
