package com.kaleaon.mnxmindmaker.util.memory.persistence

import kotlinx.serialization.json.JsonObject

interface MemoryStore {
    fun currentSchemaVersion(): Int

    fun registerMigration(migration: MemoryStoreMigration)

    fun putSession(record: SessionMemoryRecord)

    fun putProfile(record: ProfileMemoryRecord)

    fun putSemantic(record: SemanticMemoryRecord)

    fun getSessions(): List<SessionMemoryRecord>

    fun getProfiles(): List<ProfileMemoryRecord>

    fun getSemantics(): List<SemanticMemoryRecord>

    fun deleteRecord(category: MemoryCategory, id: String): Boolean

    fun clearAll()
}

fun interface MemoryStoreMigration {
    /**
     * Returns the migrated JSON document for the target schema version.
     */
    fun migrate(payload: JsonObject): JsonObject
}

data class MigrationStep(
    val fromVersion: Int,
    val toVersion: Int,
    val migration: MemoryStoreMigration
)
