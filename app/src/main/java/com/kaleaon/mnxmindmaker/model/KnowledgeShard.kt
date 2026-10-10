package com.kaleaon.mnxmindmaker.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Schema versioning for knowledge shard records.
 */
data class KnowledgeShardSchemaVersion(
    val major: Int = 1,
    val minor: Int = 0
) {
    override fun toString(): String = "$major.$minor"

    companion object {
        fun parse(versionStr: String?): KnowledgeShardSchemaVersion {
            if (versionStr.isNullOrBlank()) return KnowledgeShardSchemaVersion(1, 0)
            val parts = versionStr.trim().split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return KnowledgeShardSchemaVersion(major, minor)
        }
    }
}

/**
 * Contributor provenance metadata tracking origin agent/user identity and model details.
 */
data class ContributorProvenance(
    val id: String,
    val role: String = "agent",
    val model: String? = null,
    val timestampEpochMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("role", role)
        if (!model.isNullOrBlank()) put("model", model)
        put("timestamp_epoch_ms", timestampEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject): ContributorProvenance {
            return ContributorProvenance(
                id = json.optString("id", "unknown_contributor"),
                role = json.optString("role", "agent"),
                model = json.optString("model").ifBlank { null },
                timestampEpochMs = json.optLong("timestamp_epoch_ms", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Citation reference linking a knowledge claim or shard to source evidence.
 */
data class EvidenceReference(
    val id: String = UUID.randomUUID().toString(),
    val sourceUrlOrPath: String,
    val title: String = "",
    val snippet: String = "",
    val confidenceScore: Float = 1.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("source_url_or_path", sourceUrlOrPath)
        if (title.isNotBlank()) put("title", title)
        if (snippet.isNotBlank()) put("snippet", snippet)
        put("confidence_score", confidenceScore.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): EvidenceReference {
            return EvidenceReference(
                id = json.optString("id", UUID.randomUUID().toString()),
                sourceUrlOrPath = json.optString("source_url_or_path", ""),
                title = json.optString("title", ""),
                snippet = json.optString("snippet", ""),
                confidenceScore = json.optDouble("confidence_score", 1.0).toFloat()
            )
        }
    }
}

/**
 * Domain representation of a modular native Knowledge Shard containing versioned graph concepts,
 * contributor provenance, and verifiable evidence links.
 */
data class KnowledgeShard(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val description: String = "",
    val schemaVersion: KnowledgeShardSchemaVersion = KnowledgeShardSchemaVersion(),
    val contributor: ContributorProvenance,
    val evidenceReferences: List<EvidenceReference> = emptyList(),
    val nodeIds: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("description", description)
        put("schema_version", schemaVersion.toString())
        put("contributor", contributor.toJson())
        put("evidence_references", JSONArray().apply { evidenceReferences.forEach { put(it.toJson()) } })
        put("node_ids", JSONArray(nodeIds))
        put("topics", JSONArray(topics))
        put("created_at", createdAt)
        put("modified_at", modifiedAt)
    }

    fun toMindNode(x: Float = 0f, y: Float = 0f, parentId: String? = null): MindNode {
        val attributes = mutableMapOf(
            "shard_schema_version" to schemaVersion.toString(),
            "shard_contributor_id" to contributor.id,
            "shard_contributor_role" to contributor.role,
            "shard_evidence_references" to JSONArray().apply { evidenceReferences.forEach { put(it.toJson()) } }.toString(),
            "shard_node_ids" to JSONArray(nodeIds).toString(),
            "shard_topics" to JSONArray(topics).toString(),
            "created_at" to createdAt.toString(),
            "modified_at" to modifiedAt.toString()
        )
        contributor.model?.let { attributes["shard_contributor_model"] = it }
        attributes["shard_contributor_timestamp"] = contributor.timestampEpochMs.toString()

        return MindNode(
            id = id,
            label = label,
            type = NodeType.KNOWLEDGE_SHARD,
            description = description,
            x = x,
            y = y,
            parentId = parentId,
            attributes = attributes,
            dimensions = mapOf(
                "confidence" to 1.0f,
                "evidence_strength" to (if (evidenceReferences.isNotEmpty()) 0.9f else 0.5f),
                "relevance" to 0.8f,
                "coherence" to 0.85f,
                "recency" to 1.0f
            )
        )
    }

    companion object {
        fun fromJson(json: JSONObject): KnowledgeShard {
            val schemaVersionStr = json.optString("schema_version", "1.0")
            val contributorObj = json.optJSONObject("contributor") ?: JSONObject()
            val evidenceArray = json.optJSONArray("evidence_references") ?: JSONArray()
            val nodeIdsArray = json.optJSONArray("node_ids") ?: JSONArray()
            val topicsArray = json.optJSONArray("topics") ?: JSONArray()

            val evidences = mutableListOf<EvidenceReference>()
            for (i in 0 until evidenceArray.length()) {
                val item = evidenceArray.optJSONObject(i) ?: continue
                evidences.add(EvidenceReference.fromJson(item))
            }

            val nodeIdsList = mutableListOf<String>()
            for (i in 0 until nodeIdsArray.length()) {
                val nid = nodeIdsArray.optString(i)
                if (nid.isNotBlank()) nodeIdsList.add(nid)
            }

            val topicsList = mutableListOf<String>()
            for (i in 0 until topicsArray.length()) {
                val top = topicsArray.optString(i)
                if (top.isNotBlank()) topicsList.add(top)
            }

            return KnowledgeShard(
                id = json.optString("id", UUID.randomUUID().toString()),
                label = json.optString("label", "Untitled Shard"),
                description = json.optString("description", ""),
                schemaVersion = KnowledgeShardSchemaVersion.parse(schemaVersionStr),
                contributor = ContributorProvenance.fromJson(contributorObj),
                evidenceReferences = evidences,
                nodeIds = nodeIdsList,
                topics = topicsList,
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                modifiedAt = json.optLong("modified_at", System.currentTimeMillis())
            )
        }

        fun fromMindNode(node: MindNode): KnowledgeShard? {
            if (node.type != NodeType.KNOWLEDGE_SHARD && !node.attributes.containsKey("shard_schema_version")) {
                return null
            }

            val schemaVer = KnowledgeShardSchemaVersion.parse(node.attributes["shard_schema_version"])
            val contributorId = node.attributes["shard_contributor_id"] ?: "unknown"
            val contributorRole = node.attributes["shard_contributor_role"] ?: "agent"
            val contributorModel = node.attributes["shard_contributor_model"]
            val contributorTs = node.attributes["shard_contributor_timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

            val contributor = ContributorProvenance(
                id = contributorId,
                role = contributorRole,
                model = contributorModel,
                timestampEpochMs = contributorTs
            )

            val evidenceList = mutableListOf<EvidenceReference>()
            val evidenceRaw = node.attributes["shard_evidence_references"]
            if (!evidenceRaw.isNullOrBlank()) {
                try {
                    val array = JSONArray(evidenceRaw)
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        evidenceList.add(EvidenceReference.fromJson(item))
                    }
                } catch (_: Exception) {
                    // Fall back cleanly if string is not valid JSON
                }
            }

            val nodeIdsList = mutableListOf<String>()
            val nodeIdsRaw = node.attributes["shard_node_ids"]
            if (!nodeIdsRaw.isNullOrBlank()) {
                try {
                    val array = JSONArray(nodeIdsRaw)
                    for (i in 0 until array.length()) {
                        val id = array.optString(i)
                        if (id.isNotBlank()) nodeIdsList.add(id)
                    }
                } catch (_: Exception) {
                    nodeIdsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { nodeIdsList.add(it) }
                }
            }

            val topicsList = mutableListOf<String>()
            val topicsRaw = node.attributes["shard_topics"]
            if (!topicsRaw.isNullOrBlank()) {
                try {
                    val array = JSONArray(topicsRaw)
                    for (i in 0 until array.length()) {
                        val top = array.optString(i)
                        if (top.isNotBlank()) topicsList.add(top)
                    }
                } catch (_: Exception) {
                    topicsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { topicsList.add(it) }
                }
            }

            val createdAt = node.attributes["created_at"]?.toLongOrNull() ?: System.currentTimeMillis()
            val modifiedAt = node.attributes["modified_at"]?.toLongOrNull() ?: System.currentTimeMillis()

            return KnowledgeShard(
                id = node.id,
                label = node.label,
                description = node.description,
                schemaVersion = schemaVer,
                contributor = contributor,
                evidenceReferences = evidenceList,
                nodeIds = nodeIdsList,
                topics = topicsList,
                createdAt = createdAt,
                modifiedAt = modifiedAt
            )
        }
    }
}
