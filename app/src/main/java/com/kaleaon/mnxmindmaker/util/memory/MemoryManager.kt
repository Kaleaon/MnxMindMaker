package com.kaleaon.mnxmindmaker.util.memory

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class MemoryManager {

    enum class MemoryPolicyMode {
        OFF,
        SESSION_ONLY,
        PERSISTENT
    }

    enum class MemoryCategory {
        SESSION,
        SEMANTIC,
        PROFILE
    }

    data class MemoryPolicySettings(
        val mode: MemoryPolicyMode = MemoryPolicyMode.SESSION_ONLY,
        val expiryByCategoryMs: Map<MemoryCategory, Long> = mapOf(
            MemoryCategory.SESSION to 12 * 60 * 60 * 1000L,
            MemoryCategory.SEMANTIC to 180L * 24 * 60 * 60 * 1000,
            MemoryCategory.PROFILE to 365L * 24 * 60 * 60 * 1000
        )
    )

    data class SessionTurn(
        val id: String = UUID.randomUUID().toString(),
        val role: String,
        val content: String,
        val timestampMs: Long = System.currentTimeMillis(),
        val sensitivity: String = "low"
    )

    private val sessionTurns = mutableListOf<SessionTurn>()
    private val profileMemories = ConcurrentHashMap<String, MindNode>()
    private val semanticIndex = SemanticMemoryVectorIndex()

    @Volatile
    private var policySettings: MemoryPolicySettings = MemoryPolicySettings()

    fun setPolicy(settings: MemoryPolicySettings) {
        policySettings = settings
    }

    fun getPolicy(): MemoryPolicySettings = policySettings

    fun appendSessionTurn(turn: SessionTurn) {
        if (policySettings.mode == MemoryPolicyMode.OFF) return
        sessionTurns.add(turn)
    }

    fun upsertSemanticMemory(node: MindNode) {
        if (policySettings.mode != MemoryPolicyMode.PERSISTENT) return
        semanticIndex.upsert(node)
    }

    fun upsertProfileMemory(
        key: String,
        value: String,
        writingStyle: String? = null,
        sensitivity: String = "low",
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (policySettings.mode == MemoryPolicyMode.OFF) return
        val node = MindNode(
            id = key,
            label = key,
            type = NodeType.MEMORY,
            description = value,
            attributes = mutableMapOf(
                "semantic_subtype" to "profile",
                "preference_key" to key,
                "sensitivity" to sensitivity,
                "timestamp" to timestampMs.toString(),
                "current_relevance" to "0.85"
            ).apply {
                if (!writingStyle.isNullOrBlank()) put("writing_style", writingStyle)
            }
        )
        profileMemories[key] = node
    }

    fun editMemory(memoryId: String, update: (MindNode) -> MindNode): Boolean {
        semanticIndex.get(memoryId)?.let {
            semanticIndex.upsert(update(it))
            return true
        }
        profileMemories[memoryId]?.let {
            profileMemories[memoryId] = update(it)
            return true
        }
        return false
    }

    fun deleteMemory(memoryId: String): Boolean {
        val semanticRemoved = semanticIndex.delete(memoryId)
        val profileRemoved = profileMemories.remove(memoryId) != null
        return semanticRemoved || profileRemoved
    }

    fun clearSession() {
        sessionTurns.clear()
    }

    fun retrieveForPromptInjection(
        prompt: String,
        task: String,
        limit: Int,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<MindNode> {
        purgeExpired(nowEpochMs)
        if (policySettings.mode == MemoryPolicyMode.OFF || limit <= 0) return emptyList()

        val context = MemoryRetrievalService.RetrievalContext(prompt = prompt, task = task, nowEpochMs = nowEpochMs)

        val sessionMemories = sessionTurns.map { it.toMindNode() }
        val profile = profileMemories.values.toList()
        val semantic = if (policySettings.mode == MemoryPolicyMode.PERSISTENT) {
            semanticIndex.query(prompt = "$prompt $task", topK = limit * 3)
        } else {
            emptyList()
        }

        val candidates = when (policySettings.mode) {
            MemoryPolicyMode.OFF -> emptyList()
            MemoryPolicyMode.SESSION_ONLY -> sessionMemories + profile
            MemoryPolicyMode.PERSISTENT -> sessionMemories + profile + semantic
        }

        return MemoryRetrievalService.retrieveForPromptInjection(candidates, context, limit)
    }

    private fun purgeExpired(nowEpochMs: Long) {
        val expiryMap = policySettings.expiryByCategoryMs

        val sessionExpiry = expiryMap[MemoryCategory.SESSION]
        if (sessionExpiry != null) {
            sessionTurns.removeAll { nowEpochMs - it.timestampMs > sessionExpiry }
        }

        val profileExpiry = expiryMap[MemoryCategory.PROFILE]
        if (profileExpiry != null) {
            val toRemove = profileMemories.values
                .filter { nowEpochMs - (it.attributes["timestamp"]?.toLongOrNull() ?: nowEpochMs) > profileExpiry }
                .map { it.id }
            toRemove.forEach { profileMemories.remove(it) }
        }

        semanticIndex.purgeExpired(nowEpochMs, expiryMap[MemoryCategory.SEMANTIC])
    }

    private fun SessionTurn.toMindNode(): MindNode {
        return MindNode(
            id = id,
            label = "${role.uppercase()}: ${content.take(32)}",
            type = NodeType.MEMORY,
            description = content,
            attributes = mutableMapOf(
                "semantic_subtype" to "session",
                "memory_category" to MemoryCategory.SESSION.name.lowercase(),
                "timestamp" to timestampMs.toString(),
                "current_relevance" to "0.75",
                "sensitivity" to sensitivity
            )
        )
    }
}

private class SemanticMemoryVectorIndex {
    private val memories = ConcurrentHashMap<String, MindNode>()
    private val vectors = ConcurrentHashMap<String, Map<String, Float>>()

    fun upsert(node: MindNode) {
        memories[node.id] = node
        vectors[node.id] = tokenizeToUnitVector("${node.label} ${node.description} ${node.attributes["tags"] ?: ""}")
    }

    fun get(id: String): MindNode? = memories[id]

    fun delete(id: String): Boolean {
        val removed = memories.remove(id) != null
        vectors.remove(id)
        return removed
    }

    fun query(prompt: String, topK: Int): List<MindNode> {
        if (prompt.isBlank() || topK <= 0) return emptyList()
        val queryVector = tokenizeToUnitVector(prompt)
        if (queryVector.isEmpty()) return emptyList()

        return memories.values
            .asSequence()
            .map { memory ->
                val vector = vectors[memory.id].orEmpty()
                val similarity = cosineSimilarity(queryVector, vector)
                val updated = memory.copy(attributes = memory.attributes.toMutableMap().apply {
                    put("vector_similarity", "%.4f".format(similarity))
                    putIfAbsent("memory_category", MemoryManager.MemoryCategory.SEMANTIC.name.lowercase())
                })
                updated to similarity
            }
            .filter { (_, similarity) -> similarity > 0f }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(topK)
            .map { (memory, _) -> memory }
            .toList()
    }

    fun purgeExpired(nowEpochMs: Long, maxAgeMs: Long?) {
        if (maxAgeMs == null) return
        val expiredIds = memories.values
            .filter { memory ->
                val ts = memory.attributes["timestamp"]?.toLongOrNull() ?: nowEpochMs
                nowEpochMs - ts > maxAgeMs
            }
            .map { it.id }

        expiredIds.forEach {
            memories.remove(it)
            vectors.remove(it)
        }
    }

    private fun tokenizeToUnitVector(text: String): Map<String, Float> {
        val counts = text.lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 2 }
            .groupingBy { it }
            .eachCount()
            .mapValues { it.value.toFloat() }

        val norm = sqrt(counts.values.sumOf { (it * it).toDouble() }).toFloat()
        if (norm == 0f) return emptyMap()
        return counts.mapValues { (_, value) -> value / norm }
    }

    private fun cosineSimilarity(a: Map<String, Float>, b: Map<String, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.entries.sumOf { (token, weight) -> (weight * (b[token] ?: 0f)).toDouble() }.toFloat()
    }
}
