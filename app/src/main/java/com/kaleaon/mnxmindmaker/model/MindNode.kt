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
    val isExpanded: Boolean = true
)

enum class NodeType(val displayName: String, val colorHex: String) {
    IDENTITY("Identity", "#E91E63"),
    MEMORY("Memory", "#2196F3"),
    KNOWLEDGE("Knowledge", "#4CAF50"),
    AFFECT("Affect / Emotion", "#FF9800"),
    PERSONALITY("Personality", "#9C27B0"),
    BELIEF("Beliefs", "#00BCD4"),
    VALUE("Values", "#F44336"),
    RELATIONSHIP("Relationships", "#607D8B"),
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
