package com.kaleaon.mnxmindmaker.util.memory.persistence

import android.content.Context
import com.kaleaon.mnxmindmaker.security.EncryptedArtifactStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class MemoryStoreRepository(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME
) : MemoryStore {

    private val encryptedStore = EncryptedArtifactStore(context)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val storageFile = File(context.filesDir, fileName)
    private val migrations = mutableListOf<MigrationStep>()

    override fun currentSchemaVersion(): Int = SCHEMA_VERSION

    override fun registerMigration(migration: MemoryStoreMigration) {
        synchronized(lock) {
            val nextVersion = migrations.maxOfOrNull { it.toVersion } ?: SCHEMA_VERSION
            migrations += MigrationStep(
                fromVersion = nextVersion,
                toVersion = nextVersion + 1,
                migration = migration
            )
        }
    }

    override fun putSession(record: SessionMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.SESSION) {
            "Session record metadata category must be SESSION"
        }
        synchronized(lock) {
            val state = loadStateLocked()
            val updated = state.records.sessions
                .filterNot { it.metadata.id == record.metadata.id } + record
            saveStateLocked(
                state.copy(
                    updatedTimestamp = System.currentTimeMillis(),
                    records = state.records.copy(sessions = updated)
                )
            )
        }
    }

    override fun putProfile(record: ProfileMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.PROFILE) {
            "Profile record metadata category must be PROFILE"
        }
        synchronized(lock) {
            val state = loadStateLocked()
            val updated = state.records.profiles
                .filterNot { it.metadata.id == record.metadata.id } + record
            saveStateLocked(
                state.copy(
                    updatedTimestamp = System.currentTimeMillis(),
                    records = state.records.copy(profiles = updated)
                )
            )
        }
    }

    override fun putSemantic(record: SemanticMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.SEMANTIC) {
            "Semantic record metadata category must be SEMANTIC"
        }
        synchronized(lock) {
            val state = loadStateLocked()
            val updated = state.records.semantics
                .filterNot { it.metadata.id == record.metadata.id } + record
            saveStateLocked(
                state.copy(
                    updatedTimestamp = System.currentTimeMillis(),
                    records = state.records.copy(semantics = updated)
                )
            )
        }
    }

    override fun getSessions(): List<SessionMemoryRecord> = synchronized(lock) {
        loadStateLocked().records.sessions
    }

    override fun getProfiles(): List<ProfileMemoryRecord> = synchronized(lock) {
        loadStateLocked().records.profiles
    }

    override fun getSemantics(): List<SemanticMemoryRecord> = synchronized(lock) {
        loadStateLocked().records.semantics
    }

    override fun deleteRecord(category: MemoryCategory, id: String): Boolean = synchronized(lock) {
        val state = loadStateLocked()
        val updatedRecords = when (category) {
            MemoryCategory.SESSION -> state.records.copy(
                sessions = state.records.sessions.filterNot { it.metadata.id == id }
            )
            MemoryCategory.PROFILE -> state.records.copy(
                profiles = state.records.profiles.filterNot { it.metadata.id == id }
            )
            MemoryCategory.SEMANTIC -> state.records.copy(
                semantics = state.records.semantics.filterNot { it.metadata.id == id }
            )
        }

        if (updatedRecords == state.records) return@synchronized false
        saveStateLocked(
            state.copy(
                updatedTimestamp = System.currentTimeMillis(),
                records = updatedRecords
            )
        )
        true
    }

    override fun clearAll() {
        synchronized(lock) {
            saveStateLocked(defaultState())
        }
    }

    private fun loadStateLocked(): PersistedMemoryStore {
        if (!storageFile.exists()) {
            val initial = defaultState()
            saveStateLocked(initial)
            return initial
        }

        return try {
            val raw = String(encryptedStore.readDecryptedBytes(storageFile, "memory_index"))
            val payload = json.parseToJsonElement(raw).jsonObject
            val payloadVersion = payload[SCHEMA_VERSION_FIELD]?.jsonPrimitive?.intOrNull ?: 1
            val migratedPayload = applyMigrationsLocked(payload, payloadVersion)
            val decoded = json.decodeFromString<PersistedMemoryStore>(migratedPayload.toString())
            if (decoded.schemaVersion < SCHEMA_VERSION) {
                val upgraded = decoded.copy(
                    schemaVersion = SCHEMA_VERSION,
                    updatedTimestamp = System.currentTimeMillis()
                )
                saveStateLocked(upgraded)
                upgraded
            } else {
                decoded
            }
        } catch (_: SerializationException) {
            recoverFromCorruptionLocked()
        } catch (_: IllegalArgumentException) {
            recoverFromCorruptionLocked()
        }
    }

    private fun saveStateLocked(state: PersistedMemoryStore) {
        storageFile.parentFile?.mkdirs()
        encryptedStore.writeEncryptedBytes(storageFile, json.encodeToString(state).toByteArray(), "memory_index")
    }

    private fun defaultState(now: Long = System.currentTimeMillis()): PersistedMemoryStore {
        return PersistedMemoryStore(
            schemaVersion = SCHEMA_VERSION,
            createdTimestamp = now,
            updatedTimestamp = now,
            records = MemoryRecordCollections()
        )
    }

    private fun applyMigrationsLocked(payload: JsonObject, payloadVersion: Int): JsonObject {
        var currentVersion = payloadVersion
        var currentPayload = payload

        while (currentVersion < SCHEMA_VERSION) {
            val migrationStep = migrations.firstOrNull { it.fromVersion == currentVersion }
                ?: return currentPayload
            currentPayload = migrationStep.migration.migrate(currentPayload)
            currentVersion = migrationStep.toVersion
        }

        return currentPayload
    }

    private fun recoverFromCorruptionLocked(): PersistedMemoryStore {
        val resetState = defaultState()
        saveStateLocked(resetState)
        return resetState
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "memory_store.json"
        private const val SCHEMA_VERSION_FIELD = "schemaVersion"
        private const val SCHEMA_VERSION = 1
    }
}
