package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindNode
import kotlin.math.abs

object MemoryRetrievalService {

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
        val nowEpochMs: Long = System.currentTimeMillis()
    )

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
        val relevance: Float,
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

    fun retrieve(
        memories: List<MindNode>,
        context: RetrievalContext,
        limit: Int,
        filters: RetrievalFilters = RetrievalFilters()
    ): List<MindNode> {
        if (limit <= 0 || memories.isEmpty()) return emptyList()

        val routeHints = buildRouteHints(context)

        return memories
            .asSequence()
            .mapNotNull { node ->
                val relevance = relevanceScore(node, context)
                if (!passesProtectionAndSensitivity(node, relevance, filters)) return@mapNotNull null

                val confidence = node.attributeAsFloat("confidence", default = 0.55f)
                val recency = recencyScore(node, context.nowEpochMs)
                val riskPenalty = confabulationRiskPenalty(node)
                val stabilityBonus = (1f - abs(confidence - relevance)).coerceIn(0f, 1f) * 0.08f

                val score = (
                    relevance * 0.48f +
                        confidence * 0.2f +
                        recency * 0.2f +
                        stabilityBonus -
                        riskPenalty
                    ).coerceIn(0f, 1f)

                ScoredMemory(
                    node = node,
                    score = score,
                    confidence = confidence,
                    recency = recency,
                    relevance = relevance,
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
            .onEach { updateRevalidationMetadata(it, context.nowEpochMs) }
            .map { it.node }
            .toList()
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

    private fun confabulationRiskPenalty(node: MindNode): Float {
        val confabRisk = node.attributeAsFloat("confabulation_risk", default = 0.15f)
        val sourceQuality = node.attributeAsFloat("source_quality", default = 0.6f)
        val uncertainty = node.attributeAsFloat("uncertainty", default = 0.2f)

        return (confabRisk * 0.5f + (1f - sourceQuality) * 0.3f + uncertainty * 0.2f).coerceIn(0f, 1f) * 0.45f
    }

    private fun updateRevalidationMetadata(scoredMemory: ScoredMemory, nowEpochMs: Long) {
        val node = scoredMemory.node
        val previousConfidence = node.attributeAsFloat("confidence", default = 0.55f)
        val targetConfidence = (
            scoredMemory.confidence * 0.7f +
                scoredMemory.relevance * 0.2f +
                scoredMemory.recency * 0.1f -
                scoredMemory.riskPenalty * 0.15f
            ).coerceIn(0.05f, 0.99f)

        val confidenceDrift = (targetConfidence - previousConfidence)

        node.attributes["last_revalidated"] = nowEpochMs.toString()
        node.attributes["last_retrieval_score"] = "%.4f".format(scoredMemory.score)
        node.attributes["confidence_drift"] = "%.4f".format(confidenceDrift)
        node.attributes["confidence"] = "%.4f".format(targetConfidence)
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
