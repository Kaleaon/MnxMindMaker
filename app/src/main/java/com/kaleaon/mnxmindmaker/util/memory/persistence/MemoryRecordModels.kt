package com.kaleaon.mnxmindmaker.util.memory.persistence

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryCategory {
    SESSION,
    PROFILE,
    SEMANTIC,
    GRAPH,
    EPISODIC
}

@Serializable
data class MemoryRecordMetadata(
    val id: String,
    val timestamp: Long,
    val sensitivity: String,
    val memoryCategory: MemoryCategory,
    val routingTags: List<String> = emptyList()
)

@Serializable
data class SessionMemoryRecord(
    val metadata: MemoryRecordMetadata,
    val role: String,
    val content: String
)

@Serializable
data class ProfileMemoryRecord(
    val metadata: MemoryRecordMetadata,
    val key: String,
    val value: String,
    val writingStyle: String? = null
)

@Serializable
data class SemanticMemoryRecord(
    val metadata: MemoryRecordMetadata,
    val label: String,
    val description: String,
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class GraphMemoryRecord(
    val metadata: MemoryRecordMetadata,
    val nodeId: String,
    val edges: Map<String, List<String>> = emptyMap(),
    val properties: Map<String, String> = emptyMap()
)

@Serializable
data class MetadataIndexEntry(
    val id: String,
    val category: MemoryCategory,
    val lastUpdatedTimestamp: Long,
    val sensitivity: String,
    val routingTags: List<String> = emptyList(),
    val localRevision: Long = 0,
    val remoteRevision: Long? = null
)

@Serializable
data class MetadataIndexStore(
    val schemaVersion: Int,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val entries: List<MetadataIndexEntry> = emptyList()
)

@Serializable
data class GraphMemoryStore(
    val schemaVersion: Int,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val profiles: List<ProfileMemoryRecord> = emptyList(),
    val graphRecords: List<GraphMemoryRecord> = emptyList()
)

@Serializable
data class SemanticMemoryStore(
    val schemaVersion: Int,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val semantics: List<SemanticMemoryRecord> = emptyList()
)

@Serializable
data class EpisodicTimelineStore(
    val schemaVersion: Int,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val episodes: List<SessionMemoryRecord> = emptyList()
)

@Serializable
data class MemoryRecordCollections(
    val sessions: List<SessionMemoryRecord> = emptyList(),
    val profiles: List<ProfileMemoryRecord> = emptyList(),
    val semantics: List<SemanticMemoryRecord> = emptyList()
)

@Serializable
data class PersistedMemoryStore(
    val schemaVersion: Int,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val records: MemoryRecordCollections = MemoryRecordCollections()
)
