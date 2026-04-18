package com.kaleaon.mnxmindmaker.util.memory

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class MemoryManager(
    private val storage: MemoryStorage = InMemoryMemoryStorage(),
    private val persistenceStore: MemoryPersistenceStore = NoOpMemoryPersistenceStore,
    private val expiryTelemetry: MemoryExpiryTelemetry = StdoutMemoryExpiryTelemetry,
    private val embeddingPolicy: EmbeddingPolicy = EmbeddingPolicy(),
    private val localEmbeddingProvider: EmbeddingProvider = BagOfWordsEmbeddingProvider(),
    private val remoteEmbeddingProvider: EmbeddingProvider? = null
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

    data class MemoryMaintenancePolicy(
        val enabled: Boolean = true,
        val cleanupIntervalMs: Long = 6L * 60 * 60 * 1000,
        val archiveDormantAfterMs: Map<MemoryCategory, Long> = mapOf(
            MemoryCategory.SESSION to 3L * 24 * 60 * 60 * 1000,
            MemoryCategory.SEMANTIC to 120L * 24 * 60 * 60 * 1000,
            MemoryCategory.PROFILE to 180L * 24 * 60 * 60 * 1000
        ),
        val retentionCountByCategory: Map<MemoryCategory, Int> = mapOf(
            MemoryCategory.SESSION to 500,
            MemoryCategory.SEMANTIC to 5_000,
            MemoryCategory.PROFILE to 500
        ),
        val deleteArchivedAfterMs: Long = 365L * 24 * 60 * 60 * 1000,
        val sessionCompactionEnabled: Boolean = true,
        val sessionCompactionMinAgeMs: Long = 12L * 60 * 60 * 1000,
        val maxCompactedSessionChars: Int = 4_000
    )

    data class EmbeddingPolicy(
        val privacyMode: PrivacyMode = PrivacyMode.HYBRID,
        val preferLocal: Boolean = true
    )

    data class MemoryPolicySettings(
        val mode: MemoryPolicyMode = MemoryPolicyMode.SESSION_ONLY,
        val expiryByCategoryMs: Map<MemoryCategory, Long> = mapOf(
            MemoryCategory.SESSION to 12 * 60 * 60 * 1000L,
            MemoryCategory.SEMANTIC to 180L * 24 * 60 * 60 * 1000,
            MemoryCategory.PROFILE to 365L * 24 * 60 * 60 * 1000
        ),
        val maintenancePolicy: MemoryMaintenancePolicy = MemoryMaintenancePolicy()
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

    data class ArchivedMemoryRecord(
        val id: String,
        val category: MemoryCategory,
        val payload: MindNode,
        val archivedAtMs: Long,
        val reason: String
    )

    data class ColdStorageExport(
        val exportedAtMs: Long,
        val records: List<ArchivedMemoryRecord>,
        val lineDelimitedJson: String
    )

    data class MaintenanceReport(
        val runAtMs: Long,
        val purgedByExpiry: Map<MemoryCategory, Int>,
        val archivedByCategory: Map<MemoryCategory, Int>,
        val compactedSessionTurns: Int,
        val deletedArchivedCount: Int
    )
    interface EmbeddingProvider {
        val id: String
        val isLocal: Boolean
        fun embed(input: String): Map<String, Float>
    }

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
        val semanticMemories: List<MindNode> = emptyList(),
        val archivedMemories: List<ArchivedMemoryRecord> = emptyList(),
        val lastMaintenanceRunMs: Long? = null
    )

    interface MemoryStorage {
        fun loadSnapshot(): PersistedMemorySnapshot
        fun saveSnapshot(snapshot: PersistedMemorySnapshot)
    }

    private val archivedMemories = ConcurrentHashMap<String, ArchivedMemoryRecord>()
    private val sessionTurns = mutableListOf<SessionTurn>()
    private val profileMemories = ConcurrentHashMap<String, MindNode>()
    private val semanticIndex = SemanticMemoryVectorIndex(
        policy = embeddingPolicy,
        localProvider = localEmbeddingProvider,
        remoteProvider = remoteEmbeddingProvider
    )
    private val expiryPurgeCounters = ConcurrentHashMap<MemoryCategory, Int>()

    @Volatile
    private var policySettings: MemoryPolicySettings = MemoryPolicySettings()

    constructor() : this(storage = InMemoryMemoryStorage())

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
                "sensitivity" to sensitivity,
                "timestamp" to timestampMs.toString(),
                "last_accessed_timestamp" to timestampMs.toString(),
                "current_relevance" to "0.85",
                "memory_category" to MemoryCategory.PROFILE.name.lowercase()
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
        val archivedRemoved = archivedMemories.remove(memoryId) != null
        if (semanticRemoved || profileRemoved || archivedRemoved) {
            persistAndRefresh()
        }
        return semanticRemoved || profileRemoved || archivedRemoved
    }

    fun getMemory(memoryId: String): MindNode? =
        semanticIndex.get(memoryId) ?: profileMemories[memoryId] ?: archivedMemories[memoryId]?.payload

    fun searchMemories(query: String, limit: Int, nowEpochMs: Long = System.currentTimeMillis()): List<MindNode> {
        if (query.isBlank() || limit <= 0) return emptyList()
        runScheduledCleanup(nowEpochMs)
        val sessionNodes = sessionTurns.map { it.toMindNode() }
        val profileNodes = profileMemories.values.toList()
        val semanticNodes = if (policySettings.mode == MemoryPolicyMode.PERSISTENT) {
            semanticIndex.query(query, topK = limit * 3)
        } else emptyList()

        val all = sessionNodes + profileNodes + semanticNodes
        return MemoryRetrievalService.retrieveWithSuggestions(
            memories = all,
            context = MemoryRetrievalService.RetrievalContext(prompt = query, task = "memory_search", nowEpochMs = nowEpochMs),
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
        ).memories
    }

    fun status(nowEpochMs: Long = System.currentTimeMillis()): MemoryStatus {
        runScheduledCleanup(nowEpochMs)
        return MemoryStatus(
            mode = policySettings.mode,
            sessionTurnCount = sessionTurns.size,
            profileMemoryCount = profileMemories.size,
            semanticMemoryCount = semanticIndex.size(),
            archivedMemoryCount = archivedMemories.size,
            expiryByCategoryMs = policySettings.expiryByCategoryMs,
            lastMaintenanceRunMs = lastMaintenanceRunMs
        )
    }

    data class MemoryStatus(
        val mode: MemoryPolicyMode,
        val sessionTurnCount: Int,
        val profileMemoryCount: Int,
        val semanticMemoryCount: Int,
        val archivedMemoryCount: Int,
        val expiryByCategoryMs: Map<MemoryCategory, Long>,
        val lastMaintenanceRunMs: Long?
    )
    fun clearSession(clearAllCategories: Boolean = false) {
        sessionTurns.clear()
        if (clearAllCategories) {
            profileMemories.clear()
            semanticIndex.clearAll()
            archivedMemories.clear()
        }
        persistAndRefresh()
    }

    fun retrieveForPromptInjection(
        prompt: String,
        task: String,
        limit: Int,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<MindNode> {
        runScheduledCleanup(nowEpochMs)
        if (policySettings.mode == MemoryPolicyMode.OFF || limit <= 0) return emptyList()

        val context = MemoryRetrievalService.RetrievalContext(prompt = prompt, task = task, nowEpochMs = nowEpochMs)
        val sessionMemories = sessionTurns.map { it.toMindNode() }
        val profile = profileMemories.values.toList()
        val semantic = if (policySettings.mode == MemoryPolicyMode.PERSISTENT) {
            semanticIndex.query(prompt = "$prompt $task", topK = limit * 3)
        } else emptyList()

        val rawCandidates = when (policySettings.mode) {
            MemoryPolicyMode.OFF -> emptyList()
            MemoryPolicyMode.SESSION_ONLY -> sessionMemories + profile
            MemoryPolicyMode.PERSISTENT -> sessionMemories + profile + semantic
        }

        val route = MemoryRouting.inferRoute(prompt = prompt, task = task)
        val routedCandidates = rawCandidates.map { MemoryRouting.applyCanonicalRoute(it, route) }
        val tiered = MemoryRouting.tierCandidates(routedCandidates, route)

        val result = MemoryRetrievalService.retrieveForPromptInjectionWithSuggestions(
            memories = tiered.flattened(),
            context = context,
            limit = limit
        ).memories
        touchLastAccess(result, nowEpochMs)
        return result
    }

    fun runScheduledCleanup(nowEpochMs: Long = System.currentTimeMillis()): MaintenanceReport? {
        val maintenancePolicy = policySettings.maintenancePolicy
        if (!maintenancePolicy.enabled) return null
        val shouldRun = lastMaintenanceRunMs == null ||
            nowEpochMs - (lastMaintenanceRunMs ?: 0L) >= maintenancePolicy.cleanupIntervalMs
        if (!shouldRun) return null
        val report = runMaintenance(nowEpochMs)
        lastMaintenanceRunMs = nowEpochMs
        persistAndRefresh()
        return report
    }

    fun runMaintenance(nowEpochMs: Long = System.currentTimeMillis()): MaintenanceReport {
        val purgedByExpiry = purgeExpired(nowEpochMs)
        val archivedByCategory = archiveDormantAndOverRetention(nowEpochMs)
        val compacted = compactSessionTurns(nowEpochMs)
        val deletedArchived = deleteExpiredArchived(nowEpochMs)
        return MaintenanceReport(
            runAtMs = nowEpochMs,
            purgedByExpiry = purgedByExpiry,
            archivedByCategory = archivedByCategory,
            compactedSessionTurns = compacted,
            deletedArchivedCount = deletedArchived
        )
    }

    fun exportDormantMemoriesToColdStorage(
        nowEpochMs: Long = System.currentTimeMillis(),
        maxRecords: Int = 500
    ): ColdStorageExport {
        runScheduledCleanup(nowEpochMs)
        val records = archivedMemories.values
            .sortedBy { it.archivedAtMs }
            .take(maxRecords)

        val jsonLines = records.joinToString("\n") { record ->
            val escapedLabel = record.payload.label.replace("\"", "\\\"")
            val escapedDescription = record.payload.description.replace("\"", "\\\"")
            "{" +
                "\"id\":\"${record.id}\"," +
                "\"category\":\"${record.category.name.lowercase()}\"," +
                "\"archived_at_ms\":${record.archivedAtMs}," +
                "\"reason\":\"${record.reason}\"," +
                "\"label\":\"$escapedLabel\"," +
                "\"description\":\"$escapedDescription\"" +
                "}"
        }

        return ColdStorageExport(
            exportedAtMs = nowEpochMs,
            records = records,
            lineDelimitedJson = jsonLines
        )
    }

    private fun touchLastAccess(memories: List<MindNode>, nowEpochMs: Long) {
        memories.forEach { memory ->
            val updated = memory.copy(attributes = memory.attributes.toMutableMap().apply {
                put("last_accessed_timestamp", nowEpochMs.toString())
            })
            if (semanticIndex.get(memory.id) != null) {
                semanticIndex.upsert(updated)
            } else if (profileMemories.containsKey(memory.id)) {
                profileMemories[memory.id] = updated
            }
        }
    }

    private fun purgeExpired(nowEpochMs: Long): Map<MemoryCategory, Int> {
        val expiryMap = policySettings.expiryByCategoryMs
        val removed = mutableMapOf<MemoryCategory, Int>()

        purgeExpiredByCategory(MemoryCategory.SESSION, expiryMap[MemoryCategory.SESSION]) { maxAgeMs ->
            PurgeSelection(
                expiredIds = sessionTurns
                    .filter { nowEpochMs - it.timestampMs > maxAgeMs }
                    .map { it.id },
                malformedTimestampCount = 0
            )
        }?.let { expiredIds ->
            sessionTurns.removeAll { it.id in expiredIds.toSet() }
            removed[MemoryCategory.SESSION] = expiredIds.size
        }

        purgeExpiredByCategory(MemoryCategory.PROFILE, expiryMap[MemoryCategory.PROFILE]) { maxAgeMs ->
            profileMemories.values.selectExpiredByTimestamp(
                nowEpochMs = nowEpochMs,
                maxAgeMs = maxAgeMs
            )
        }?.let { expiredIds ->
            expiredIds.forEach { profileMemories.remove(it) }
            removed[MemoryCategory.PROFILE] = expiredIds.size
        }

        purgeExpiredByCategory(MemoryCategory.SEMANTIC, expiryMap[MemoryCategory.SEMANTIC]) { maxAgeMs ->
            semanticIndex.memories().selectExpiredByTimestamp(
                nowEpochMs = nowEpochMs,
                maxAgeMs = maxAgeMs
            )
        }?.let { expiredIds ->
            semanticIndex.deleteMany(expiredIds)
            removed[MemoryCategory.SEMANTIC] = expiredIds.size
        }

        return removed
        }.forEach { expiredId -> sessionTurns.removeAll { it.id == expiredId } }

        purgeExpiredByCategory(MemoryCategory.PROFILE, expiryMap[MemoryCategory.PROFILE]) { maxAgeMs ->
            profileMemories.values.selectExpiredByTimestamp(nowEpochMs = nowEpochMs, maxAgeMs = maxAgeMs)
        }.forEach { profileMemories.remove(it) }

        purgeExpiredByCategory(MemoryCategory.SEMANTIC, expiryMap[MemoryCategory.SEMANTIC]) { maxAgeMs ->
            semanticIndex.memories().selectExpiredByTimestamp(nowEpochMs = nowEpochMs, maxAgeMs = maxAgeMs)
        }.let { semanticIndex.deleteMany(it) }

        if (sessionTurns.isEmpty() && profileMemories.isEmpty() && semanticIndex.size() == 0) return
    }

    private fun purgeExpiredByCategory(
        category: MemoryCategory,
        maxAgeMs: Long?,
        selectExpired: (Long) -> PurgeSelection
    ): List<String> {
        if (maxAgeMs == null) return emptyList()
        val selection = selectExpired(maxAgeMs)
        persistenceStore.deleteExpired(category, selection.expiredIds)
        expiryPurgeCounters.compute(category) { _, count -> (count ?: 0) + selection.expiredIds.size }
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
            val ts = memory.attributes["timestamp"]?.toLongOrNull()
            if (ts == null) malformedTimestampCount += 1
            nowEpochMs - (ts ?: nowEpochMs) > maxAgeMs
        }.map { it.id }
        return PurgeSelection(expiredIds = expiredIds, malformedTimestampCount = malformedTimestampCount)
    }

    private fun parseTimestamp(rawTimestamp: String?): Long? = rawTimestamp?.toLongOrNull()

    private fun archiveDormantAndOverRetention(nowEpochMs: Long): Map<MemoryCategory, Int> {
        val policy = policySettings.maintenancePolicy
        val archivedCounts = mutableMapOf<MemoryCategory, Int>()

        val archivedSession = archiveSessionTurns(nowEpochMs, policy)
        if (archivedSession > 0) archivedCounts[MemoryCategory.SESSION] = archivedSession

        val archivedProfile = archiveMindNodeCategory(
            category = MemoryCategory.PROFILE,
            nowEpochMs = nowEpochMs,
            nodes = profileMemories.values.toList(),
            remove = { id -> profileMemories.remove(id) },
            policy = policy
        )
        if (archivedProfile > 0) archivedCounts[MemoryCategory.PROFILE] = archivedProfile

        val archivedSemantic = archiveMindNodeCategory(
            category = MemoryCategory.SEMANTIC,
            nowEpochMs = nowEpochMs,
            nodes = semanticIndex.memories().toList(),
            remove = { id -> semanticIndex.delete(id) },
            policy = policy
        )
        if (archivedSemantic > 0) archivedCounts[MemoryCategory.SEMANTIC] = archivedSemantic

        return archivedCounts
    }

    private fun archiveSessionTurns(nowEpochMs: Long, policy: MemoryMaintenancePolicy): Int {
        val maxCount = policy.retentionCountByCategory[MemoryCategory.SESSION] ?: Int.MAX_VALUE
        val dormantAfterMs = policy.archiveDormantAfterMs[MemoryCategory.SESSION] ?: Long.MAX_VALUE

        val sortedByAge = sessionTurns.sortedBy { it.timestampMs }
        val overflow = (sortedByAge.size - maxCount).coerceAtLeast(0)
        val byRetention = sortedByAge.take(overflow).map { it.id }.toSet()
        val byDormancy = sortedByAge.filter { nowEpochMs - it.timestampMs > dormantAfterMs }.map { it.id }.toSet()
        val toArchive = byRetention + byDormancy

        if (toArchive.isEmpty()) return 0

        val archiveRecords = sessionTurns
            .filter { it.id in toArchive }
            .map { turn ->
                ArchivedMemoryRecord(
                    id = turn.id,
                    category = MemoryCategory.SESSION,
                    payload = turn.toMindNode(),
                    archivedAtMs = nowEpochMs,
                    reason = if (turn.id in byRetention) "retention_limit" else "dormant"
                )
            }
        archiveRecords.forEach { archivedMemories[it.id] = it }
        sessionTurns.removeAll { it.id in toArchive }
        return archiveRecords.size
    }

    private fun archiveMindNodeCategory(
        category: MemoryCategory,
        nowEpochMs: Long,
        nodes: List<MindNode>,
        remove: (String) -> Unit,
        policy: MemoryMaintenancePolicy
    ): Int {
        val maxCount = policy.retentionCountByCategory[category] ?: Int.MAX_VALUE
        val dormantAfterMs = policy.archiveDormantAfterMs[category] ?: Long.MAX_VALUE
        val sortedByAge = nodes.sortedBy { resolveMemoryTimestamp(it) }

        val overflow = (sortedByAge.size - maxCount).coerceAtLeast(0)
        val byRetention = sortedByAge.take(overflow).map { it.id }.toSet()
        val byDormancy = sortedByAge
            .filter { node -> nowEpochMs - resolveLastTouchedTimestamp(node) > dormantAfterMs }
            .map { it.id }
            .toSet()

        val toArchive = byRetention + byDormancy
        if (toArchive.isEmpty()) return 0

        sortedByAge.filter { it.id in toArchive }.forEach { node ->
            archivedMemories[node.id] = ArchivedMemoryRecord(
                id = node.id,
                category = category,
                payload = node,
                archivedAtMs = nowEpochMs,
                reason = if (node.id in byRetention) "retention_limit" else "dormant"
            )
            remove(node.id)
        }
        return toArchive.size
    }

    private fun compactSessionTurns(nowEpochMs: Long): Int {
        val maintenance = policySettings.maintenancePolicy
        if (!maintenance.sessionCompactionEnabled) return 0
        val oldTurns = sessionTurns.filter { nowEpochMs - it.timestampMs >= maintenance.sessionCompactionMinAgeMs }
        if (oldTurns.size < 3) return 0

        val grouped = oldTurns.groupBy { "${it.conversationId.orEmpty()}::${it.role}" }
        var compactedOut = 0
        grouped.values.forEach { group ->
            if (group.size < 3) return@forEach
            val sorted = group.sortedBy { it.timestampMs }
            val summaryText = sorted.joinToString("\n") { "[${it.role}] ${it.content}" }
                .take(maintenance.maxCompactedSessionChars)
            val first = sorted.first()
            val compactedTurn = SessionTurn(
                role = "system",
                source = "maintenance_compaction",
                content = "Compacted ${sorted.size} turns:\n$summaryText",
                conversationId = first.conversationId,
                turnIndex = first.turnIndex,
                chunkSpan = "compacted",
                timestampMs = first.timestampMs,
                sensitivity = "low"
            )
            sessionTurns.removeAll { it.id in sorted.map { turn -> turn.id }.toSet() }
            sessionTurns.add(compactedTurn)
            compactedOut += (sorted.size - 1)
        }
        return compactedOut
    }

    private fun deleteExpiredArchived(nowEpochMs: Long): Int {
        val maxArchivedAge = policySettings.maintenancePolicy.deleteArchivedAfterMs
        if (maxArchivedAge <= 0) return 0
        val toDelete = archivedMemories.values
            .filter { nowEpochMs - it.archivedAtMs >= maxArchivedAge }
            .map { it.id }

        toDelete.forEach { archivedMemories.remove(it) }
        return toDelete.size
    }

    private fun resolveMemoryTimestamp(node: MindNode): Long =
        node.attributes["timestamp"]?.toLongOrNull() ?: Long.MAX_VALUE

    private fun resolveLastTouchedTimestamp(node: MindNode): Long =
        node.attributes["last_accessed_timestamp"]?.toLongOrNull()
            ?: node.attributes["timestamp"]?.toLongOrNull()
            ?: Long.MAX_VALUE

    private fun restoreFromStorage() {
        val snapshot = storage.loadSnapshot()
        sessionTurns.clear()
        sessionTurns.addAll(snapshot.sessionTurns)
        profileMemories.clear()
        snapshot.profileMemories.forEach { profileMemories[it.id] = it }
        semanticIndex.replaceAll(snapshot.semanticMemories)
        archivedMemories.clear()
        snapshot.archivedMemories.forEach { archivedMemories[it.id] = it }
        lastMaintenanceRunMs = snapshot.lastMaintenanceRunMs
    }

    private fun persistSnapshot() {
        storage.saveSnapshot(
            PersistedMemorySnapshot(
                sessionTurns = sessionTurns.toList(),
                profileMemories = profileMemories.values.toList(),
                semanticMemories = semanticIndex.allMemories(),
                archivedMemories = archivedMemories.values.toList(),
                lastMaintenanceRunMs = lastMaintenanceRunMs
            )
        )
    }

    private fun persistAndRefresh() {
        persistSnapshot()
        restoreFromStorage()
    }

    private fun SessionTurn.toMindNode(): MindNode = MindNode(
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

private class InMemoryMemoryStorage : MemoryManager.MemoryStorage {
    @Volatile
    private var snapshot: MemoryManager.PersistedMemorySnapshot = MemoryManager.PersistedMemorySnapshot()

    override fun loadSnapshot(): MemoryManager.PersistedMemorySnapshot = snapshot

    override fun saveSnapshot(snapshot: MemoryManager.PersistedMemorySnapshot) {
        this.snapshot = snapshot
    }
}

private class BagOfWordsEmbeddingProvider : MemoryManager.EmbeddingProvider {
    override val id: String = "local-bow"
    override val isLocal: Boolean = true

    override fun embed(input: String): Map<String, Float> = tokenizeToUnitVector(input)

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
}

private class SemanticMemoryVectorIndex(
    private val policy: MemoryManager.EmbeddingPolicy,
    private val localProvider: MemoryManager.EmbeddingProvider,
    private val remoteProvider: MemoryManager.EmbeddingProvider?
) {
    private data class VectorCacheEntry(
        val fingerprint: String,
        val vector: Map<String, Float>,
        val providerId: String
    )

    private val memories = ConcurrentHashMap<String, MindNode>()
    private val vectorCache = ConcurrentHashMap<String, VectorCacheEntry>()

    fun upsert(node: MindNode) {
        val fingerprint = fingerprintFor(node)
        val cached = vectorCache[node.id]
        val freshVector = if (cached != null && cached.fingerprint == fingerprint) {
            cached.vector
        } else {
            val provider = selectProvider()
            val vector = provider.embed(contentForEmbedding(node))
            vectorCache[node.id] = VectorCacheEntry(
                fingerprint = fingerprint,
                vector = vector,
                providerId = provider.id
            )
            vector
        }

        val providerId = vectorCache[node.id]?.providerId ?: "unknown"
        memories[node.id] = node.copy(attributes = node.attributes.toMutableMap().apply {
            put("embedding_provider", providerId)
            put("embedding_cache_fingerprint", fingerprint)
            put("memory_category", MemoryManager.MemoryCategory.SEMANTIC.name.lowercase())
            putIfAbsent("semantic_subtype", "semantic")
        })

        if (freshVector.isEmpty()) {
            vectorCache.remove(node.id)
        }
    }

    fun get(id: String): MindNode? = memories[id]

    fun delete(id: String): Boolean {
        val removed = memories.remove(id) != null
        vectorCache.remove(id)
        return removed
    }

    fun deleteMany(ids: List<String>) {
        ids.forEach {
            memories.remove(it)
            vectors.remove(it)
            vectorCache.remove(it)
        }
    }

    fun clearAll() {
        memories.clear()
        vectorCache.clear()
    }

    fun allMemories(): List<MindNode> = memories.values.toList()

    fun replaceAll(nodes: List<MindNode>) {
        clearAll()
        nodes.forEach { upsert(it) }
    }

    fun query(prompt: String, topK: Int): List<MindNode> {
        if (prompt.isBlank() || topK <= 0) return emptyList()
        val queryVector = selectProvider().embed(prompt)
        if (queryVector.isEmpty()) return emptyList()

        return memories.values
            .asSequence()
            .mapNotNull { memory ->
                val vector = vectorCache[memory.id]?.vector ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryVector, vector)
                if (similarity <= 0f) return@mapNotNull null
                memory.copy(attributes = memory.attributes.toMutableMap().apply {
                    put("vector_similarity", "%.4f".format(similarity))
                }) to similarity
            }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(topK)
            .map { (memory, _) -> memory }
            .toList()
    }

    fun memories(): Collection<MindNode> = memories.values.toList()

    fun size(): Int = memories.size

    fun cacheSize(): Int = vectorCache.size

    private fun contentForEmbedding(node: MindNode): String =
        "${node.label} ${node.description} ${node.attributes["tags"] ?: ""}".trim()

    private fun fingerprintFor(node: MindNode): String =
        "${node.label}|${node.description}|${node.attributes["tags"] ?: ""}".trim()

    private fun selectProvider(): MemoryManager.EmbeddingProvider {
        val canUseRemote = policy.privacyMode != PrivacyMode.STRICT_LOCAL_ONLY
        return when {
            policy.preferLocal -> localProvider
            canUseRemote && remoteProvider != null -> remoteProvider
            else -> localProvider
        }
    }

    private fun cosineSimilarity(a: Map<String, Float>, b: Map<String, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.entries.sumOf { (token, weight) -> (weight * (b[token] ?: 0f)).toDouble() }.toFloat()
    }
}
