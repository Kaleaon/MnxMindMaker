package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindNode
import kotlin.math.abs

object MemoryRetrievalService {

    data class RetrievalContext(
        val prompt: String = "",
        val task: String = "",
        val nowEpochMs: Long = System.currentTimeMillis()
    )

    private data class ScoredMemory(
        val node: MindNode,
        val score: Float,
        val confidence: Float,
        val recency: Float,
        val relevance: Float,
        val riskPenalty: Float
    )

    fun retrieve(memories: List<MindNode>, context: RetrievalContext, limit: Int): List<MindNode> {
        if (limit <= 0 || memories.isEmpty()) return emptyList()

        return memories
            .asSequence()
            .filter(::passesProtectionConstraints)
            .map { node ->
                val relevance = relevanceScore(node, context)
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
                    riskPenalty = riskPenalty
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
            .onEach { updateRevalidationMetadata(it, context.nowEpochMs) }
            .map { it.node }
            .toList()
    }

    private fun passesProtectionConstraints(node: MindNode): Boolean {
        val sensitivity = node.attributes["sensitivity"]?.lowercase()
        val protectionLevel = node.attributes["protection_level"]?.lowercase()
        val allowSensitive = node.attributes["allow_sensitive_boot"]?.toBoolean() ?: false

        if (!allowSensitive && (sensitivity == "high" || sensitivity == "restricted")) return false
        if (protectionLevel == "sealed") return false
        return true
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

    private const val MILLIS_PER_DAY = 86_400_000L
}
