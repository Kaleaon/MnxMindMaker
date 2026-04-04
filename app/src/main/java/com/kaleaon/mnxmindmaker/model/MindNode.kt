package com.kaleaon.mnxmindmaker.model

import java.util.UUID

/**
 * A node in the AI mind map designer.
 * Each node represents a domain of the AI's mind (identity, memory, etc.)
 * and can be connected to other nodes to form the overall mind structure.
 */
data class MindNode(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val type: NodeType,
    val description: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val parentId: String? = null,
    val attributes: MutableMap<String, String> = mutableMapOf(),
    val isExpanded: Boolean = true,
    /**
     * N-dimensional coordinate map for this node.
     *
     * Keys are named semantic dimensions (e.g. "confidence", "valence",
     * "ethical_weight", "social_impact"). The canvas only renders [x]/[y],
     * but the full dimensionality is persisted in the .mnx DIMENSIONAL_REFS
     * section so higher-order relationships remain intact.
     *
     * Values are normalised floats in [0.0, 1.0] unless the dimension is
     * bipolar (e.g. "valence" in [-1.0, 1.0]).
     */
    val dimensions: Map<String, Float> = emptyMap()
)

enum class NodeType(val displayName: String, val colorHex: String) {
    IDENTITY("Identity", "#E91E63"),
    MEMORY("Memory", "#2196F3"),
    KNOWLEDGE("Knowledge", "#4CAF50"),
    STATE("State", "#FF7043"),
    AFFECT("Affect / Emotion", "#FF9800"),
    PERSONALITY("Personality", "#9C27B0"),
    BELIEF("Beliefs", "#00BCD4"),
    VALUE("Values", "#F44336"),
    RELATIONSHIP("Relationships", "#607D8B"),
    DRIFT_RULE("Drift Rule", "#7E57C2"),
    CUSTOM("Custom", "#795548")
}

/**
 * An edge connecting two mind nodes.
 */
data class MindEdge(
    val id: String = UUID.randomUUID().toString(),
    val fromNodeId: String,
    val toNodeId: String,
    val label: String = "",
    val strength: Float = 1.0f
)

/**
 * The entire in-memory mind graph — nodes + edges.
 */
data class MindGraph(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled Mind",
    val nodes: MutableList<MindNode> = mutableListOf(),
    val edges: MutableList<MindEdge> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)
