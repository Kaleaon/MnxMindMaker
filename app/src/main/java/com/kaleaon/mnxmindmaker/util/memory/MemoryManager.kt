package com.kaleaon.mnxmindmaker.util.memory

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class MemoryManager(
    private val storage: MemoryStorage = InMemoryMemoryStorage(),
    private val persistenceStore: MemoryPersistenceStore = NoOpMemoryPersistenceStore,
    private val expiryTelemetry: MemoryExpiryTelemetry = StdoutMemoryExpiryTelemetry
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

    /**
     * Query DSL for fast memory search across labels, attributes, dimensions,
     * relations, timestamps, and semantic similarity.
     */
    data class MemoryQuery(
        val rawQuery: String = "",
        val categories: Set<MemoryCategory> = setOf(MemoryCategory.SESSION, MemoryCategory.PROFILE, MemoryCategory.SEMANTIC),
        val labelsAny: Set<String> = emptySet(),
        val attributesAll: Map<String, String> = emptyMap(),
        val dimensions: Map<String, ClosedFloatingPointRange<Float>> = emptyMap(),
        val relationIdsAny: Set<String> = emptySet(),
        val timestampRange: LongRange? = null,
        val minSemanticSimilarity: Float = 0f,
        val limit: Int = 20
    )

    data class MemoryView(
        val name: String,
        val filterName: String,
        val defaultLimit: Int = 20
    )

    class QueryBuilder {
        private var rawQuery: String = ""
        private val categories = mutableSetOf(MemoryCategory.SESSION, MemoryCategory.PROFILE, MemoryCategory.SEMANTIC)
        private val labelsAny = mutableSetOf<String>()
        private val attributesAll = mutableMapOf<String, String>()
        private val dimensions = mutableMapOf<String, ClosedFloatingPointRange<Float>>()
        private val relationIdsAny = mutableSetOf<String>()
        private var timestampRange: LongRange? = null
        private var minSemanticSimilarity: Float = 0f
        private var limit: Int = 20

        fun text(value: String) = apply { rawQuery = value }

        fun category(category: MemoryCategory) = apply {
            if (categories.size == MemoryCategory.entries.size) categories.clear()
            categories += category
        }

        fun anyLabel(vararg labels: String) = apply {
            labels.mapTo(labelsAny) { it.trim().lowercase() }.removeAll { it.isBlank() }
        }

        fun attribute(key: String, value: String) = apply {
            attributesAll[key.trim()] = value.trim()
        }

        fun dimension(name: String, min: Float, max: Float) = apply {
            dimensions[name.trim()] = min.coerceAtLeast(0f)..max.coerceAtMost(1f)
        }

        fun relatedTo(vararg ids: String) = apply {
            ids.mapTo(relationIdsAny) { it.trim() }.removeAll { it.isBlank() }
        }

        fun betweenTimestamps(startInclusive: Long, endInclusive: Long) = apply {
            timestampRange = startInclusive..endInclusive
        }

        fun minSimilarity(value: Float) = apply {
            minSemanticSimilarity = value.coerceIn(0f, 1f)
        }

        fun limit(value: Int) = apply { limit = value.coerceAtLeast(1) }

        fun build(): MemoryQuery = MemoryQuery(
            rawQuery = rawQuery,
            categories = categories.toSet(),
            labelsAny = labelsAny.toSet(),
            attributesAll = attributesAll.toMap(),
            dimensions = dimensions.toMap(),
            relationIdsAny = relationIdsAny.toSet(),
            timestampRange = timestampRange,
            minSemanticSimilarity = minSemanticSimilarity,
            limit = limit
        )
    }

    interface MemoryPersistenceStore {
        fun deleteExpired(category: MemoryCategory, memoryIds: List<String>)
    }

    interface MemoryExpiryTelemetry {
        fun onExpiredRemoved(category: MemoryCategory, removedCount: Int, malformedTimestampCount: Int)
    }

    data class PersistedMemorySnapshot(
        val sessionTurns: List<SessionTurn> = emptyList(),
        val profileMemories: List<MindNode> = emptyList(),
        val semanticMemories: List<MindNode> = emptyList()
    )

    interface MemoryStorage {
        fun loadSnapshot(): PersistedMemorySnapshot
        fun saveSnapshot(snapshot: PersistedMemorySnapshot)
    }

    data class MemoryStatus(
        val mode: MemoryPolicyMode,
        val sessionTurnCount: Int,
        val profileMemoryCount: Int,
        val semanticMemoryCount: Int,
        val expiryByCategoryMs: Map<MemoryCategory, Long>
    )

    private data class PurgeSelection(val expiredIds: List<String>, val malformedTimestampCount: Int)

    private val sessionTurns = mutableListOf<SessionTurn>()
    private val profileMemories = ConcurrentHashMap<String, MindNode>()
    private val semanticIndex = SemanticMemoryVectorIndex()
    private val expiryPurgeCounters = ConcurrentHashMap<MemoryCategory, Int>()
    private val savedFilters = ConcurrentHashMap<String, MemoryQuery>()
    private val savedViews = ConcurrentHashMap<String, MemoryView>()

    @Volatile
    private var policySettings: MemoryPolicySettings = MemoryPolicySettings()

    constructor() : this(
        storage = InMemoryMemoryStorage(),
        persistenceStore = NoOpMemoryPersistenceStore,
        expiryTelemetry = StdoutMemoryExpiryTelemetry
    )

    constructor(
        persistenceStore: MemoryPersistenceStore,
        expiryTelemetry: MemoryExpiryTelemetry = StdoutMemoryExpiryTelemetry
    ) : this(
        storage = InMemoryMemoryStorage(),
        persistenceStore = persistenceStore,
        expiryTelemetry = expiryTelemetry
    )

    init {
        MemoryCategory.entries.forEach { expiryPurgeCounters[it] = 0 }
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
                "memory_category" to MemoryCategory.PROFILE.name.lowercase(),
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

    fun getMemory(memoryId: String): MindNode? = semanticIndex.get(memoryId) ?: profileMemories[memoryId]

    fun clearSession(clearAllCategories: Boolean = false) {
        sessionTurns.clear()
        if (clearAllCategories) {
            profileMemories.clear()
            semanticIndex.clearAll()
        }
        persistAndRefresh()
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

    fun saveFilter(name: String, query: MemoryQuery) {
        savedFilters[name.trim().lowercase()] = query
    }

    fun runSavedFilter(name: String): List<MindNode> {
        val query = savedFilters[name.trim().lowercase()] ?: return emptyList()
        return searchMemories(query)
    }

    fun saveView(view: MemoryView) {
        savedViews[view.name.trim().lowercase()] = view
    }

    fun runView(name: String): List<MindNode> {
        val view = savedViews[name.trim().lowercase()] ?: return emptyList()
        val filter = savedFilters[view.filterName.trim().lowercase()] ?: return emptyList()
        return searchMemories(filter.copy(limit = view.defaultLimit))
    }

    fun searchMemories(query: String, limit: Int): List<MindNode> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return searchMemories(
            QueryBuilder()
                .text(query)
                .minSimilarity(0f)
                .limit(limit)
                .build()
        )
    }

    fun searchMemories(query: MemoryQuery): List<MindNode> {
        if (query.limit <= 0) return emptyList()
        val candidatePool = gatherCandidates(query.categories)
        val scored = candidatePool
            .asSequence()
            .map { it to semanticSimilarity(it, query.rawQuery) }
            .filter { (node, similarity) ->
                matchesLabels(node, query.labelsAny) &&
                    matchesAttributes(node, query.attributesAll) &&
                    matchesDimensions(node, query.dimensions) &&
                    matchesRelations(node, query.relationIdsAny) &&
                    matchesTimestamp(node, query.timestampRange) &&
                    similarity >= query.minSemanticSimilarity
            }
            .sortedWith(
                compareByDescending<Pair<MindNode, Float>> { it.second }
                    .thenByDescending { parseTimestamp(it.first.attributes["timestamp"]) ?: 0L }
                    .thenBy { it.first.id }
            )
            .take(query.limit * 3)
            .map { (node, similarity) ->
                node.copy(attributes = node.attributes.toMutableMap().apply {
                    put("vector_similarity", "%.4f".format(similarity))
                })
            }
            .toList()

        return MemoryRetrievalService.retrieveWithSuggestions(
            memories = scored,
            context = MemoryRetrievalService.RetrievalContext(prompt = query.rawQuery, task = "memory_query_dsl"),
            limit = query.limit,
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
        ).memories
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

        return MemoryRetrievalService.retrieveForPromptInjectionWithSuggestions(
            memories = tiered.flattened(),
            context = context,
            limit = limit
        ).memories
    }

    private fun gatherCandidates(categories: Set<MemoryCategory>): List<MindNode> {
        val includeSession = MemoryCategory.SESSION in categories
        val includeProfile = MemoryCategory.PROFILE in categories
        val includeSemantic = MemoryCategory.SEMANTIC in categories && policySettings.mode == MemoryPolicyMode.PERSISTENT

        val sessions = if (includeSession) sessionTurns.map { it.toMindNode() } else emptyList()
        val profiles = if (includeProfile) profileMemories.values.toList() else emptyList()
        val semantics = if (includeSemantic) semanticIndex.allMemories() else emptyList()
        return sessions + profiles + semantics
    }

    private fun matchesLabels(node: MindNode, labelsAny: Set<String>): Boolean {
        if (labelsAny.isEmpty()) return true
        val label = node.label.lowercase()
        return labelsAny.any { label.contains(it) }
    }

    private fun matchesAttributes(node: MindNode, attributesAll: Map<String, String>): Boolean {
        if (attributesAll.isEmpty()) return true
        return attributesAll.all { (key, value) -> node.attributes[key] == value }
    }

    private fun matchesDimensions(
        node: MindNode,
        dimensions: Map<String, ClosedFloatingPointRange<Float>>
    ): Boolean {
        if (dimensions.isEmpty()) return true
        return dimensions.all { (key, range) ->
            val value = node.dimensions[key] ?: return@all false
            value in range
        }
    }

    private fun matchesRelations(node: MindNode, relationIdsAny: Set<String>): Boolean {
        if (relationIdsAny.isEmpty()) return true
        val relatedRaw = node.attributes["related_to"].orEmpty()
        val relatedSet = relatedRaw.split(',', ';', '|').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        return relationIdsAny.any { it == node.parentId || it in relatedSet }
    }

    private fun matchesTimestamp(node: MindNode, range: LongRange?): Boolean {
        if (range == null) return true
        val timestamp = parseTimestamp(node.attributes["timestamp"]) ?: return false
        return timestamp in range
    }

    private fun semanticSimilarity(node: MindNode, rawQuery: String): Float {
        if (rawQuery.isBlank()) return 1f
        return semanticIndex.similarity(rawQuery, node)
    }

    private fun purgeExpired(nowEpochMs: Long) {
        val expiryMap = policySettings.expiryByCategoryMs

        purgeExpiredByCategory(MemoryCategory.SESSION, expiryMap[MemoryCategory.SESSION]) { maxAgeMs ->
            PurgeSelection(
                expiredIds = sessionTurns.filter { nowEpochMs - it.timestampMs > maxAgeMs }.map { it.id },
                malformedTimestampCount = 0
            )
        }?.let { expiredIds ->
            if (expiredIds.isNotEmpty()) {
                val expiredSet = expiredIds.toSet()
                sessionTurns.removeAll { it.id in expiredSet }
            }
        }

        purgeExpiredByCategory(MemoryCategory.PROFILE, expiryMap[MemoryCategory.PROFILE]) { maxAgeMs ->
            profileMemories.values.selectExpiredByTimestamp(nowEpochMs, maxAgeMs)
        }?.let { expiredIds ->
            expiredIds.forEach { profileMemories.remove(it) }
        }

        purgeExpiredByCategory(MemoryCategory.SEMANTIC, expiryMap[MemoryCategory.SEMANTIC]) { maxAgeMs ->
            semanticIndex.memories().selectExpiredByTimestamp(nowEpochMs, maxAgeMs)
        }?.let { expiredIds ->
            semanticIndex.deleteMany(expiredIds)
        }
    }

    private fun purgeExpiredByCategory(
        category: MemoryCategory,
        maxAgeMs: Long?,
        selectExpired: (Long) -> PurgeSelection
    ): List<String>? {
        if (maxAgeMs == null) return null
        val selection = selectExpired(maxAgeMs)
        if (selection.expiredIds.isNotEmpty()) {
            persistenceStore.deleteExpired(category, selection.expiredIds)
            expiryPurgeCounters.compute(category) { _, count -> (count ?: 0) + selection.expiredIds.size }
        }
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

    private fun parseTimestamp(rawTimestamp: String?): Long? = rawTimestamp?.toLongOrNull()

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
        vectors[node.id] = tokenizeToUnitVector(toEmbeddingText(node))
    }

    fun get(id: String): MindNode? = memories[id]

    fun delete(id: String): Boolean {
        val removed = memories.remove(id) != null
        vectors.remove(id)
        return removed
    }

    fun deleteMany(ids: List<String>) {
        ids.forEach {
            memories.remove(it)
            vectors.remove(it)
        }
    }

    fun clearAll() {
        memories.clear()
        vectors.clear()
    }

    fun allMemories(): List<MindNode> = memories.values.toList()

    fun memories(): Collection<MindNode> = memories.values.toList()

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

    fun similarity(query: String, memory: MindNode): Float {
        if (query.isBlank()) return 1f
        val queryVector = tokenizeToUnitVector(query)
        if (queryVector.isEmpty()) return 0f
        val memoryVector = vectors[memory.id] ?: tokenizeToUnitVector(toEmbeddingText(memory))
        return cosineSimilarity(queryVector, memoryVector)
    }

    fun size(): Int = memories.size

    private fun toEmbeddingText(memory: MindNode): String {
        return buildString {
            append(memory.label)
            append(' ')
            append(memory.description)
            append(' ')
            append(memory.attributes["tags"].orEmpty())
            append(' ')
            append(memory.attributes.values.joinToString(" "))
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
