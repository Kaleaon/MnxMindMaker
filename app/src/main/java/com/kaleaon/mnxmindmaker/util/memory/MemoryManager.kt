package com.kaleaon.mnxmindmaker.util.memory

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class MemoryManager(
    private val storage: MemoryStorage = InMemoryMemoryStorage()
) {

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
        val conversationId: String? = null,
        val turnIndex: Int? = null,
        val chunkSpan: String? = null,
        val source: String = role,
        val timestampMs: Long = System.currentTimeMillis(),
        val sensitivity: String = "low"
    )

    interface MemoryPersistenceStore {
        fun deleteExpired(category: MemoryCategory, memoryIds: List<String>)
    }

    interface MemoryExpiryTelemetry {
        fun onExpiredRemoved(category: MemoryCategory, removedCount: Int, malformedTimestampCount: Int)
    }

    private object NoOpMemoryPersistenceStore : MemoryPersistenceStore {
        override fun deleteExpired(category: MemoryCategory, memoryIds: List<String>) = Unit
    }

    private object StdoutMemoryExpiryTelemetry : MemoryExpiryTelemetry {
        override fun onExpiredRemoved(category: MemoryCategory, removedCount: Int, malformedTimestampCount: Int) {
            println(
                "MemoryManager.purgeExpired category=$category removed=$removedCount malformed_timestamps=$malformedTimestampCount"
            )
        }
    }

    private data class PurgeSelection(val expiredIds: List<String>, val malformedTimestampCount: Int)

    data class PersistedMemorySnapshot(
        val sessionTurns: List<SessionTurn> = emptyList(),
        val profileMemories: List<MindNode> = emptyList(),
        val semanticMemories: List<MindNode> = emptyList()
    )

    interface MemoryStorage {
        fun loadSnapshot(): PersistedMemorySnapshot
        fun saveSnapshot(snapshot: PersistedMemorySnapshot)
    }

    private val sessionTurns = mutableListOf<SessionTurn>()
    private val profileMemories = ConcurrentHashMap<String, MindNode>()
    private val semanticIndex = SemanticMemoryVectorIndex()
    private val expiryPurgeCounters = ConcurrentHashMap<MemoryCategory, Int>()
    private val persistenceStore: MemoryPersistenceStore
    private val expiryTelemetry: MemoryExpiryTelemetry

    @Volatile
    private var policySettings: MemoryPolicySettings = MemoryPolicySettings()

    constructor() : this(
        persistenceStore = NoOpMemoryPersistenceStore,
        expiryTelemetry = StdoutMemoryExpiryTelemetry
    )

    constructor(
        persistenceStore: MemoryPersistenceStore,
        expiryTelemetry: MemoryExpiryTelemetry = StdoutMemoryExpiryTelemetry
    ) {
        this.persistenceStore = persistenceStore
        this.expiryTelemetry = expiryTelemetry
        MemoryCategory.entries.forEach { expiryPurgeCounters[it] = 0 }
    init {
        restoreFromStorage()
    }

    fun setPolicy(settings: MemoryPolicySettings) {
        policySettings = settings
    }

    fun getPolicy(): MemoryPolicySettings = policySettings

    fun getExpiryPurgeCounters(): Map<MemoryCategory, Int> = expiryPurgeCounters.toMap()

    fun appendSessionTurn(turn: SessionTurn) {
        if (policySettings.mode == MemoryPolicyMode.OFF) return
        sessionTurns.add(turn)
        persistAndRefresh()
    }

    fun upsertSemanticMemory(node: MindNode) {
        if (policySettings.mode != MemoryPolicyMode.PERSISTENT) return
        val seedRoute = MemoryRouting.inferRoute(prompt = node.label, task = node.description)
        semanticIndex.upsert(MemoryRouting.applyCanonicalRoute(node, seedRoute))
        semanticIndex.upsert(node)
        persistAndRefresh()
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
                put(MemoryRouting.KEY_MEMORY_WING, "self")
                put(MemoryRouting.KEY_MEMORY_HALL, "preferences")
                put(MemoryRouting.KEY_MEMORY_ROOM, "voice_style")
                if (!writingStyle.isNullOrBlank()) put("writing_style", writingStyle)
            }
        )
        profileMemories[key] = node
        persistAndRefresh()
    }

    fun editMemory(memoryId: String, update: (MindNode) -> MindNode): Boolean {
        semanticIndex.get(memoryId)?.let {
            semanticIndex.upsert(update(it))
            persistAndRefresh()
            return true
        }
        profileMemories[memoryId]?.let {
            profileMemories[memoryId] = update(it)
            persistAndRefresh()
            return true
        }
        return false
    }

    fun deleteMemory(memoryId: String): Boolean {
        val semanticRemoved = semanticIndex.delete(memoryId)
        val profileRemoved = profileMemories.remove(memoryId) != null
        if (semanticRemoved || profileRemoved) {
            persistAndRefresh()
        }
        return semanticRemoved || profileRemoved
    }

    fun getMemory(memoryId: String): MindNode? =
        semanticIndex.get(memoryId) ?: profileMemories[memoryId]

    fun searchMemories(query: String, limit: Int): List<MindNode> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val sessionNodes = sessionTurns.map { it.toMindNode() }
        val profileNodes = profileMemories.values.toList()
        val semanticNodes = if (policySettings.mode == MemoryPolicyMode.PERSISTENT) {
            semanticIndex.query(query, topK = limit * 3)
        } else {
            emptyList()
        }
        val all = sessionNodes + profileNodes + semanticNodes
        return MemoryRetrievalService.retrieve(
            memories = all,
            context = MemoryRetrievalService.RetrievalContext(prompt = query, task = "memory_search"),
            limit = limit,
            filters = MemoryRetrievalService.RetrievalFilters(
                minRelevance = 0f,
                allowedSensitivity = setOf(
                    MemoryRetrievalService.SensitivityLevel.LOW,
                    MemoryRetrievalService.SensitivityLevel.MEDIUM,
                    MemoryRetrievalService.SensitivityLevel.HIGH,
                    MemoryRetrievalService.SensitivityLevel.RESTRICTED
                ),
                allowSensitiveBoot = true
            )
        )
    }

    fun status(nowEpochMs: Long = System.currentTimeMillis()): MemoryStatus {
        purgeExpired(nowEpochMs)
        return MemoryStatus(
            mode = policySettings.mode,
            sessionTurnCount = sessionTurns.size,
            profileMemoryCount = profileMemories.size,
            semanticMemoryCount = semanticIndex.size(),
            expiryByCategoryMs = policySettings.expiryByCategoryMs
        )
    }

    data class MemoryStatus(
        val mode: MemoryPolicyMode,
        val sessionTurnCount: Int,
        val profileMemoryCount: Int,
        val semanticMemoryCount: Int,
        val expiryByCategoryMs: Map<MemoryCategory, Long>
    )

    fun clearSession() {
    fun clearSession(clearAllCategories: Boolean = false) {
        sessionTurns.clear()
        if (clearAllCategories) {
            profileMemories.clear()
            semanticIndex.clearAll()
        }
        persistAndRefresh()
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

        val rawCandidates = when (policySettings.mode) {
            MemoryPolicyMode.OFF -> emptyList()
            MemoryPolicyMode.SESSION_ONLY -> sessionMemories + profile
            MemoryPolicyMode.PERSISTENT -> sessionMemories + profile + semantic
        }

        val route = MemoryRouting.inferRoute(prompt = prompt, task = task)
        val routedCandidates = rawCandidates.map { MemoryRouting.applyCanonicalRoute(it, route) }
        val tiered = MemoryRouting.tierCandidates(routedCandidates, route)

        return MemoryRetrievalService.retrieveForPromptInjection(tiered.flattened(), context, limit)
    }

    private fun purgeExpired(nowEpochMs: Long) {
        val expiryMap = policySettings.expiryByCategoryMs
        purgeExpiredByCategory(MemoryCategory.SESSION, expiryMap[MemoryCategory.SESSION]) { maxAgeMs ->
            PurgeSelection(
                expiredIds = sessionTurns
                    .filter { nowEpochMs - it.timestampMs > maxAgeMs }
                    .map { it.id },
                malformedTimestampCount = 0
            )
        }?.let { expiredIds ->
            sessionTurns.removeAll { it.id in expiredIds.toSet() }
        }

        purgeExpiredByCategory(MemoryCategory.PROFILE, expiryMap[MemoryCategory.PROFILE]) { maxAgeMs ->
            profileMemories.values.selectExpiredByTimestamp(
                nowEpochMs = nowEpochMs,
                maxAgeMs = maxAgeMs
            )
        }?.let { expiredIds ->
            expiredIds.forEach { profileMemories.remove(it) }
        }

        purgeExpiredByCategory(MemoryCategory.SEMANTIC, expiryMap[MemoryCategory.SEMANTIC]) { maxAgeMs ->
            semanticIndex.memories().selectExpiredByTimestamp(
                nowEpochMs = nowEpochMs,
                maxAgeMs = maxAgeMs
            )
        }?.let { expiredIds ->
            semanticIndex.deleteMany(expiredIds)
        var shouldPersist = false

        val sessionExpiry = expiryMap[MemoryCategory.SESSION]
        if (sessionExpiry != null) {
            val removed = sessionTurns.removeAll { nowEpochMs - it.timestampMs > sessionExpiry }
            if (removed) shouldPersist = true
        }
    }

    private fun purgeExpiredByCategory(
        category: MemoryCategory,
        maxAgeMs: Long?,
        selectExpired: (Long) -> PurgeSelection
    ): List<String>? {
        if (maxAgeMs == null) return null
        val selection = selectExpired(maxAgeMs)
        if (selection.expiredIds.isEmpty()) {
            expiryTelemetry.onExpiredRemoved(category, 0, selection.malformedTimestampCount)
            return emptyList()
        val profileExpiry = expiryMap[MemoryCategory.PROFILE]
        if (profileExpiry != null) {
            val toRemove = profileMemories.values
                .filter { nowEpochMs - (it.attributes["timestamp"]?.toLongOrNull() ?: nowEpochMs) > profileExpiry }
                .map { it.id }
            toRemove.forEach { profileMemories.remove(it) }
            if (toRemove.isNotEmpty()) shouldPersist = true
        }
        persistenceStore.deleteExpired(category, selection.expiredIds)
        expiryPurgeCounters.compute(category) { _, count -> (count ?: 0) + selection.expiredIds.size }
        expiryTelemetry.onExpiredRemoved(category, selection.expiredIds.size, selection.malformedTimestampCount)
        return selection.expiredIds
    }

    private fun Collection<MindNode>.selectExpiredByTimestamp(nowEpochMs: Long, maxAgeMs: Long): PurgeSelection {
        var malformedTimestampCount = 0
        val expiredIds = this.filter { memory ->
            val ts = parseTimestamp(memory.attributes["timestamp"])
            if (ts == null) malformedTimestampCount += 1
            nowEpochMs - (ts ?: nowEpochMs) > maxAgeMs
        }.map { it.id }
        return PurgeSelection(expiredIds = expiredIds, malformedTimestampCount = malformedTimestampCount)
    }

    private fun parseTimestamp(rawTimestamp: String?): Long? {
        return rawTimestamp?.toLongOrNull()
        val semanticPurged = semanticIndex.purgeExpired(nowEpochMs, expiryMap[MemoryCategory.SEMANTIC])
        if (semanticPurged) shouldPersist = true

        if (shouldPersist) {
            persistAndRefresh()
        }
    }

    private fun restoreFromStorage() {
        val snapshot = storage.loadSnapshot()
        sessionTurns.clear()
        sessionTurns.addAll(snapshot.sessionTurns)
        profileMemories.clear()
        snapshot.profileMemories.forEach { profileMemories[it.id] = it }
        semanticIndex.replaceAll(snapshot.semanticMemories)
    }

    private fun persistSnapshot() {
        storage.saveSnapshot(
            PersistedMemorySnapshot(
                sessionTurns = sessionTurns.toList(),
                profileMemories = profileMemories.values.toList(),
                semanticMemories = semanticIndex.allMemories()
            )
        )
    }

    private fun persistAndRefresh() {
        persistSnapshot()
        restoreFromStorage()
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
                "conversation_id" to (conversationId ?: ""),
                "turn_index" to (turnIndex?.toString() ?: ""),
                "chunk_span" to (chunkSpan ?: ""),
                "source" to source,
                "role" to role,
                "timestamp" to timestampMs.toString(),
                "current_relevance" to "0.75",
                "sensitivity" to sensitivity,
                MemoryRouting.KEY_MEMORY_WING to "history",
                MemoryRouting.KEY_MEMORY_HALL to "events",
                MemoryRouting.KEY_MEMORY_ROOM to "session_turn"
            )
        )
    }
}

private class InMemoryMemoryStorage : MemoryManager.MemoryStorage {
    @Volatile
    private var snapshot: MemoryManager.PersistedMemorySnapshot = MemoryManager.PersistedMemorySnapshot()

    override fun loadSnapshot(): MemoryManager.PersistedMemorySnapshot = snapshot

    override fun saveSnapshot(snapshot: MemoryManager.PersistedMemorySnapshot) {
        this.snapshot = snapshot
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

    fun clearAll() {
        memories.clear()
        vectors.clear()
    }

    fun allMemories(): List<MindNode> = memories.values.toList()

    fun replaceAll(nodes: List<MindNode>) {
        clearAll()
        nodes.forEach { upsert(it) }
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

    fun memories(): Collection<MindNode> = memories.values.toList()
    fun purgeExpired(nowEpochMs: Long, maxAgeMs: Long?): Boolean {
        if (maxAgeMs == null) return false
        val expiredIds = memories.values
            .filter { memory ->
                val ts = memory.attributes["timestamp"]?.toLongOrNull() ?: nowEpochMs
                nowEpochMs - ts > maxAgeMs
            }
            .map { it.id }

    fun deleteMany(ids: List<String>) {
        ids.forEach {
            memories.remove(it)
            vectors.remove(it)
        }
        return expiredIds.isNotEmpty()
    }

    fun size(): Int = memories.size

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
