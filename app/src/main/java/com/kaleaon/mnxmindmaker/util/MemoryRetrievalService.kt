package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindNode
import kotlin.math.abs

object MemoryRetrievalService {

    data class SuggestedNodeUpdate(
        val nodeId: String,
        val deltas: Map<String, String>
    )

    data class RetrievalResult(
        val memories: List<MindNode>,
        val suggestedUpdates: List<SuggestedNodeUpdate> = emptyList()
    )

    enum class SensitivityLevel {
        LOW,
        MEDIUM,
        HIGH,
        RESTRICTED
    }

    data class RetrievalContext(
        val prompt: String = "",
        val task: String = "",
        val roomHint: String? = null,
        val hallHint: String? = null,
        val wingHint: String? = null,
        val queryVector: Map<String, Float> = emptyMap(),
        val policyProfile: RetrievalPolicyProfile = RetrievalPolicyProfile.ASSIST,
        val nowEpochMs: Long = System.currentTimeMillis()
    )

    enum class RetrievalPolicyProfile {
        ASSIST,
        AUDIT,
        DEPLOYMENT,
        RECOVERY
    }

    data class RetrievalFilters(
        val minRelevance: Float = 0f,
        val allowedSensitivity: Set<SensitivityLevel> = setOf(
            SensitivityLevel.LOW,
            SensitivityLevel.MEDIUM,
            SensitivityLevel.HIGH,
            SensitivityLevel.RESTRICTED
        ),
        val allowSensitiveBoot: Boolean = false
    )

    private data class ScoredMemory(
        val node: MindNode,
        val score: Float,
        val confidence: Float,
        val recency: Float,
        val importance: Float,
        val relevance: Float,
        val vectorSimilarity: Float,
        val graphProximity: Float,
        val riskPenalty: Float,
        val tier: RetrievalTier
    )

    private enum class RetrievalTier {
        EXACT_ROOM,
        HALL,
        WING,
        GLOBAL_FALLBACK
    }

    private data class RouteHints(
        val room: String?,
        val hall: String?,
        val wing: String?
    )

    private data class PolicyWeights(
        val relevance: Float,
        val confidence: Float,
        val recency: Float,
        val importance: Float,
        val vectorSimilarity: Float,
        val graphProximity: Float,
        val riskPenaltyWeight: Float
    )

    fun retrieve(
        memories: List<MindNode>,
        context: RetrievalContext,
        limit: Int,
        filters: RetrievalFilters = RetrievalFilters()
    ): List<MindNode> = retrieveWithSuggestions(
        memories = memories,
        context = context,
        limit = limit,
        filters = filters
    ).memories

    fun retrieveWithSuggestions(
        memories: List<MindNode>,
        context: RetrievalContext,
        limit: Int,
        filters: RetrievalFilters = RetrievalFilters()
    ): RetrievalResult {
        if (limit <= 0 || memories.isEmpty()) return RetrievalResult(memories = emptyList())

        val routeHints = buildRouteHints(context)
        val graphProximityByNode = graphProximityScores(memories, context)
        val policyWeights = weightsFor(context.policyProfile)

        val scored = memories
            .asSequence()
            .mapNotNull { node ->
                val relevance = relevanceScore(node, context)
                if (!passesProtectionAndSensitivity(node, relevance, filters)) return@mapNotNull null

                val confidence = node.attributeAsFloat("confidence", default = 0.55f)
                val recency = recencyScore(node, context.nowEpochMs)
                val importance = importanceScore(node)
                val vectorSimilarity = vectorSimilarityScore(node, context)
                val graphProximity = graphProximityByNode[node.id] ?: 0f
                val riskPenalty = confabulationRiskPenalty(node)
                val stabilityBonus = (1f - abs(confidence - relevance)).coerceIn(0f, 1f) * 0.08f

                val score = (
                    relevance * policyWeights.relevance +
                        confidence * policyWeights.confidence +
                        recency * policyWeights.recency +
                        importance * policyWeights.importance +
                        vectorSimilarity * policyWeights.vectorSimilarity +
                        graphProximity * policyWeights.graphProximity +
                        stabilityBonus -
                        riskPenalty * policyWeights.riskPenaltyWeight
                    ).coerceIn(0f, 1f)

                ScoredMemory(
                    node = node,
                    score = score,
                    confidence = confidence,
                    recency = recency,
                    importance = importance,
                    relevance = relevance,
                    vectorSimilarity = vectorSimilarity,
                    graphProximity = graphProximity,
                    riskPenalty = riskPenalty,
                    tier = classifyTier(node, routeHints)
                )
            }
            .sortedWith(
                compareBy<ScoredMemory> { it.tier.ordinal }
                    .thenByDescending { it.score }
                    .thenBy { it.node.id }
            )
            .take(limit)
            .toList()

        return RetrievalResult(
            memories = scored.map { it.node },
            suggestedUpdates = buildRevalidationUpdates(scored, context.nowEpochMs)
        )
    }

    /**
     * Guardrail preset for context/prompt injection: enforce relevance and deny highly sensitive content.
     */
    fun retrieveForPromptInjection(
        memories: List<MindNode>,
        context: RetrievalContext,
        limit: Int
    ): List<MindNode> = retrieve(
        memories = memories,
        context = context,
        limit = limit,
        filters = RetrievalFilters(
            minRelevance = 0.25f,
            allowedSensitivity = setOf(SensitivityLevel.LOW, SensitivityLevel.MEDIUM),
            allowSensitiveBoot = false
        )
    )

    fun retrieveForPromptInjectionWithSuggestions(
        memories: List<MindNode>,
        context: RetrievalContext,
        limit: Int
    ): RetrievalResult = retrieveWithSuggestions(
        memories = memories,
        context = context,
        limit = limit,
        filters = RetrievalFilters(
            minRelevance = 0.25f,
            allowedSensitivity = setOf(SensitivityLevel.LOW, SensitivityLevel.MEDIUM),
            allowSensitiveBoot = false
        )
    )

    fun policyProfileForUseCase(useCase: String): RetrievalPolicyProfile = when (useCase.trim().lowercase()) {
        "assist", "assistant", "chat", "help" -> RetrievalPolicyProfile.ASSIST
        "audit", "compliance", "trace" -> RetrievalPolicyProfile.AUDIT
        "deployment", "deploy", "release", "runtime" -> RetrievalPolicyProfile.DEPLOYMENT
        "recovery", "incident", "fallback", "restore" -> RetrievalPolicyProfile.RECOVERY
        else -> RetrievalPolicyProfile.ASSIST
    }

    private fun passesProtectionAndSensitivity(
        node: MindNode,
        relevance: Float,
        filters: RetrievalFilters
    ): Boolean {
        if (relevance < filters.minRelevance) return false

        val protectionLevel = node.attributes["protection_level"]?.lowercase()
        if (protectionLevel == "sealed") return false

        val sensitivity = node.sensitivityLevel()
        val allowSensitive = filters.allowSensitiveBoot ||
            (node.attributes["allow_sensitive_boot"]?.toBoolean() ?: false)

        if (!allowSensitive && sensitivity in setOf(SensitivityLevel.HIGH, SensitivityLevel.RESTRICTED)) {
            return false
        }

        return sensitivity in filters.allowedSensitivity
    }

    private fun relevanceScore(node: MindNode, context: RetrievalContext): Float {
        val queryTokens = tokenize("${context.prompt} ${context.task}")
        if (queryTokens.isEmpty()) {
            return node.attributeAsFloat("current_relevance", default = 0.5f)
        }

        val memoryTokens = tokenize(
            listOf(node.label, node.description, node.attributes["semantic_subtype"], node.attributes["tags"])
                .filterNotNull()
                .joinToString(" ")
        )
        if (memoryTokens.isEmpty()) return 0f

        val overlap = queryTokens.intersect(memoryTokens).size.toFloat()
        val lexical = (overlap / queryTokens.size.toFloat()).coerceIn(0f, 1f)
        val intrinsic = node.attributeAsFloat("current_relevance", default = 0.5f)

        return (lexical * 0.65f + intrinsic * 0.35f).coerceIn(0f, 1f)
    }

    private fun buildRouteHints(context: RetrievalContext): RouteHints {
        val corpus = "${context.prompt} ${context.task}".lowercase()
        val room = normalizeRouteHint(context.roomHint ?: extractNamedHint(corpus, "room"))
        val hall = normalizeRouteHint(context.hallHint ?: extractNamedHint(corpus, "hall"))
        val wing = normalizeRouteHint(context.wingHint ?: extractNamedHint(corpus, "wing"))
        return RouteHints(room = room, hall = hall, wing = wing)
    }

    private fun classifyTier(node: MindNode, hints: RouteHints): RetrievalTier {
        val nodeRoom = node.attributeNormalized("room")
        val nodeHall = node.attributeNormalized("hall")
        val nodeWing = node.attributeNormalized("wing")

        return when {
            hints.room != null && hints.room == nodeRoom -> RetrievalTier.EXACT_ROOM
            hints.hall != null && hints.hall == nodeHall -> RetrievalTier.HALL
            hints.wing != null && hints.wing == nodeWing -> RetrievalTier.WING
            else -> RetrievalTier.GLOBAL_FALLBACK
        }
    }

    private fun extractNamedHint(corpus: String, key: String): String? {
        val regex = Regex("""\b$key\s*[:=]\s*([a-z0-9_-]+)\b""")
        return regex.find(corpus)?.groupValues?.getOrNull(1)
    }

    private fun normalizeRouteHint(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    private fun recencyScore(node: MindNode, nowEpochMs: Long): Float {
        val sourceTs = node.attributeAsLong("last_revalidated")
            ?: node.attributeAsLong("last_used")
            ?: node.attributeAsLong("timestamp")
            ?: return 0.4f

        val ageDays = ((nowEpochMs - sourceTs).coerceAtLeast(0L) / MILLIS_PER_DAY).toFloat()
        return when {
            ageDays <= 1f -> 1f
            ageDays <= 7f -> 0.85f
            ageDays <= 30f -> 0.65f
            ageDays <= 90f -> 0.45f
            else -> 0.25f
        }
    }

    private fun importanceScore(node: MindNode): Float =
        node.attributeAsFloat("importance", default = node.dimensions["importance"]?.coerceIn(0f, 1f) ?: 0.5f)

    private fun vectorSimilarityScore(node: MindNode, context: RetrievalContext): Float {
        val queryVector = context.queryVector
            .filterValues { it.isFinite() }
            .mapValues { (_, value) -> value.coerceIn(-1f, 1f) }
        if (queryVector.isEmpty()) {
            return node.attributeAsFloat("vector_similarity", default = 0.5f)
        }

        val nodeVector = node.dimensions
            .filterKeys { it in queryVector.keys }
            .mapValues { (_, value) -> value.coerceIn(-1f, 1f) }
        if (nodeVector.isEmpty()) return 0f

        val dot = nodeVector.entries.sumOf { (key, value) -> (value * (queryVector[key] ?: 0f)).toDouble() }
        val nodeNorm = kotlin.math.sqrt(nodeVector.values.sumOf { (it * it).toDouble() })
        val queryNorm = kotlin.math.sqrt(queryVector.values.sumOf { (it * it).toDouble() })
        if (nodeNorm == 0.0 || queryNorm == 0.0) return 0f

        val cosine = (dot / (nodeNorm * queryNorm)).toFloat().coerceIn(-1f, 1f)
        return ((cosine + 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun graphProximityScores(
        memories: List<MindNode>,
        context: RetrievalContext
    ): Map<String, Float> {
        if (memories.isEmpty()) return emptyMap()

        val seedIds = memories
            .map { it to relevanceScore(it, context) }
            .sortedByDescending { (_, score) -> score }
            .take(3)
            .mapNotNull { (node, score) -> node.id.takeIf { score >= 0.3f } }
            .toSet()
        if (seedIds.isEmpty()) return emptyMap()

        val adjacency = mutableMapOf<String, MutableSet<String>>()
        memories.forEach { node ->
            adjacency.getOrPut(node.id) { linkedSetOf() }
            val parentId = node.parentId ?: return@forEach
            adjacency.getOrPut(node.id) { linkedSetOf() }.add(parentId)
            adjacency.getOrPut(parentId) { linkedSetOf() }.add(node.id)
        }

        val distanceById = mutableMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        seedIds.forEach { seedId ->
            distanceById[seedId] = 0
            queue.add(seedId)
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentDistance = distanceById[current] ?: continue
            if (currentDistance >= 2) continue

            adjacency[current].orEmpty().forEach { neighbor ->
                if (neighbor !in distanceById) {
                    distanceById[neighbor] = currentDistance + 1
                    queue.add(neighbor)
                }
            }
        }

        return memories.associate { node ->
            val distance = distanceById[node.id]
            val proximity = when (distance) {
                0 -> 1f
                1 -> 0.7f
                2 -> 0.4f
                else -> 0f
            }
            node.id to proximity
        }
    }

    private fun weightsFor(profile: RetrievalPolicyProfile): PolicyWeights = when (profile) {
        RetrievalPolicyProfile.ASSIST -> PolicyWeights(
            relevance = 0.28f,
            confidence = 0.14f,
            recency = 0.18f,
            importance = 0.12f,
            vectorSimilarity = 0.14f,
            graphProximity = 0.14f,
            riskPenaltyWeight = 1f
        )
        RetrievalPolicyProfile.AUDIT -> PolicyWeights(
            relevance = 0.24f,
            confidence = 0.2f,
            recency = 0.12f,
            importance = 0.2f,
            vectorSimilarity = 0.14f,
            graphProximity = 0.1f,
            riskPenaltyWeight = 1.15f
        )
        RetrievalPolicyProfile.DEPLOYMENT -> PolicyWeights(
            relevance = 0.2f,
            confidence = 0.12f,
            recency = 0.22f,
            importance = 0.2f,
            vectorSimilarity = 0.14f,
            graphProximity = 0.12f,
            riskPenaltyWeight = 0.95f
        )
        RetrievalPolicyProfile.RECOVERY -> PolicyWeights(
            relevance = 0.22f,
            confidence = 0.1f,
            recency = 0.26f,
            importance = 0.1f,
            vectorSimilarity = 0.1f,
            graphProximity = 0.22f,
            riskPenaltyWeight = 0.9f
        )
    }

    private fun confabulationRiskPenalty(node: MindNode): Float {
        val confabRisk = node.attributeAsFloat("confabulation_risk", default = 0.15f)
        val sourceQuality = node.attributeAsFloat("source_quality", default = 0.6f)
        val uncertainty = node.attributeAsFloat("uncertainty", default = 0.2f)

        return (confabRisk * 0.5f + (1f - sourceQuality) * 0.3f + uncertainty * 0.2f).coerceIn(0f, 1f) * 0.45f
    }

    private fun buildRevalidationUpdates(
        scoredMemories: List<ScoredMemory>,
        nowEpochMs: Long
    ): List<SuggestedNodeUpdate> = scoredMemories.map { scoredMemory ->
        val node = scoredMemory.node
        val previousConfidence = node.attributeAsFloat("confidence", default = 0.55f)
        val targetConfidence = (
            scoredMemory.confidence * 0.7f +
                scoredMemory.relevance * 0.2f +
                scoredMemory.recency * 0.1f -
                scoredMemory.riskPenalty * 0.15f
            ).coerceIn(0.05f, 0.99f)

        val confidenceDrift = (targetConfidence - previousConfidence)

        SuggestedNodeUpdate(
            nodeId = node.id,
            deltas = mapOf(
                "last_revalidated" to nowEpochMs.toString(),
                "last_retrieval_score" to "%.4f".format(scoredMemory.score),
                "confidence_drift" to "%.4f".format(confidenceDrift),
                "confidence" to "%.4f".format(targetConfidence),
                "last_importance_score" to "%.4f".format(scoredMemory.importance),
                "last_vector_similarity" to "%.4f".format(scoredMemory.vectorSimilarity),
                "last_graph_proximity" to "%.4f".format(scoredMemory.graphProximity)
            )
        )
    }

    fun applyRevalidationUpdates(
        nodes: List<MindNode>,
        suggestedUpdates: List<SuggestedNodeUpdate>
    ) {
        if (nodes.isEmpty() || suggestedUpdates.isEmpty()) return
        val byId = nodes.associateBy { it.id }
        suggestedUpdates.forEach { update ->
            val node = byId[update.nodeId] ?: return@forEach
            update.deltas.forEach { (key, value) ->
                node.attributes[key] = value
            }
        }
    }

    private fun tokenize(raw: String): Set<String> =
        raw
            .lowercase()
            .split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 2 }
            .toSet()

    private fun MindNode.attributeAsFloat(key: String, default: Float): Float =
        attributes[key]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: default

    private fun MindNode.attributeAsLong(key: String): Long? = attributes[key]?.toLongOrNull()

    private fun MindNode.attributeNormalized(key: String): String? = attributes[key]
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    private fun MindNode.sensitivityLevel(): SensitivityLevel {
        return when (attributes["sensitivity"]?.lowercase()) {
            "restricted" -> SensitivityLevel.RESTRICTED
            "high" -> SensitivityLevel.HIGH
            "medium" -> SensitivityLevel.MEDIUM
            else -> SensitivityLevel.LOW
        }
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
