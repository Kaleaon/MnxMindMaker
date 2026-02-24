package com.kaleaon.mnxmindmaker.mnx

/**
 * Portable data models for each .mnx section.
 * Ported from github.com/Kaleaon/TronProtocol (mindnexus/MnxSections.kt).
 */

// ---- IDENTITY ---------------------------------------------------------------
data class MnxIdentity(
    val name: String,
    val createdAt: Long,
    val species: String = "",
    val pronouns: String = "",
    val coreTraits: List<String> = emptyList(),
    val biography: String = "",
    val attributes: Map<String, String> = emptyMap()
)

// ---- MEMORY_STORE -----------------------------------------------------------
data class MnxMemoryChunk(
    val chunkId: String,
    val content: String,
    val source: String = "",
    val sourceType: String = "memory",
    val timestamp: String = "",
    val tokenCount: Int = 0,
    val qValue: Float = 0.5f,
    val retrievalCount: Int = 0,
    val successCount: Int = 0,
    val memoryStage: String = "WORKING",
    val embedding: FloatArray? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MnxMemoryChunk) return false
        return chunkId == other.chunkId
    }
    override fun hashCode(): Int = chunkId.hashCode()
}

data class MnxMemoryStore(val chunks: List<MnxMemoryChunk>)

// ---- KNOWLEDGE_GRAPH --------------------------------------------------------
data class MnxEntityNode(
    val entityId: String,
    val name: String,
    val entityType: String,
    val description: String,
    val mentionCount: Int = 1,
    val lastSeen: Long = 0L
)

data class MnxChunkNode(
    val chunkId: String,
    val summary: String,
    val entityIds: List<String> = emptyList()
)

data class MnxRelationshipEdge(
    val sourceEntityId: String,
    val targetEntityId: String,
    val relationship: String,
    val strength: Float = 1.0f,
    val keywords: List<String> = emptyList()
)

data class MnxKnowledgeGraph(
    val entities: List<MnxEntityNode>,
    val chunkNodes: List<MnxChunkNode>,
    val edges: List<MnxRelationshipEdge>
)

// ---- AFFECT_STATE -----------------------------------------------------------
data class MnxAffectState(
    val dimensions: Map<String, Float>,
    val timestamp: Long = System.currentTimeMillis()
)

// ---- PERSONALITY ------------------------------------------------------------
data class MnxPersonality(
    val traits: Map<String, Float> = emptyMap(),
    val biases: Map<String, Float> = emptyMap(),
    val curiosityLevel: Float = 0.5f,
    val openness: Float = 0.5f,
    val conscientiousness: Float = 0.5f,
    val extraversion: Float = 0.5f,
    val agreeableness: Float = 0.5f,
    val neuroticism: Float = 0.5f
)

// ---- BELIEF_STORE -----------------------------------------------------------
data class MnxBelief(
    val beliefId: String,
    val statement: String,
    val confidence: Float = 0.5f,
    val evidence: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class MnxBeliefStore(val beliefs: List<MnxBelief>)

// ---- VALUE_ALIGNMENT --------------------------------------------------------
data class MnxValue(
    val valueId: String,
    val name: String,
    val weight: Float = 1.0f,
    val description: String = ""
)

data class MnxValueAlignment(val values: List<MnxValue>)

// ---- RELATIONSHIP_WEB -------------------------------------------------------
data class MnxRelationship(
    val entityId: String,
    val name: String,
    val relationshipType: String,
    val bond: Float = 0.5f,
    val lastInteraction: Long = 0L,
    val notes: String = ""
)

data class MnxRelationshipWeb(val relationships: List<MnxRelationship>)

// ---- META -------------------------------------------------------------------
data class MnxMeta(val entries: Map<String, String>)
