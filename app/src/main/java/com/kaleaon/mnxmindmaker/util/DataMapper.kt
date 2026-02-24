package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import org.json.JSONObject

/**
 * Converts imported text or JSON data into [MindGraph] nodes.
 *
 * Supported input formats:
 *  - Plain text: each paragraph → memory node
 *  - JSON: keys/values mapped to typed nodes based on key name heuristics
 */
object DataMapper {

    /**
     * Parse plain text into a [MindGraph].
     * Creates one node per non-empty paragraph, typed as MEMORY.
     */
    fun fromPlainText(text: String, graphName: String = "Imported Mind"): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        val paragraphs = text.split(Regex("\\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        paragraphs.forEachIndexed { i, para ->
            val row = i / 4
            val col = i % 4
            val node = MindNode(
                label = para.take(60) + if (para.length > 60) "…" else "",
                type = NodeType.MEMORY,
                description = para,
                x = 80f + col * 220f,
                y = 200f + row * 160f,
                parentId = rootNode.id,
                dimensions = DimensionMapper.defaultDimensions(NodeType.MEMORY)
            )
            nodes.add(node)
            edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    /**
     * Parse a JSON string into a [MindGraph].
     * Top-level keys are mapped to node types via [keyToNodeType].
     */
    fun fromJson(jsonText: String, graphName: String = "Imported Mind"): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        val jsonObj = JSONObject(jsonText)
        var colIdx = 0
        jsonObj.keys().forEach { key ->
            val value = jsonObj.get(key)
            val nodeType = keyToNodeType(key)
            val node = MindNode(
                label = key,
                type = nodeType,
                description = value.toString().take(500),
                x = 80f + colIdx * 220f,
                y = 200f,
                parentId = rootNode.id,
                dimensions = DimensionMapper.defaultDimensions(nodeType)
            )
            nodes.add(node)
            edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))
            colIdx++
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    /**
     * Suggest MNX sections for each node in the graph.
     * Returns a map of nodeId → suggested MNX section name.
     */
    fun suggestMnxSections(graph: MindGraph): Map<String, String> {
        return graph.nodes.associate { node ->
            node.id to when (node.type) {
                NodeType.IDENTITY -> "IDENTITY"
                NodeType.MEMORY -> "MEMORY_STORE"
                NodeType.KNOWLEDGE -> "KNOWLEDGE_GRAPH"
                NodeType.AFFECT -> "AFFECT_STATE"
                NodeType.PERSONALITY -> "PERSONALITY"
                NodeType.BELIEF -> "BELIEF_STORE"
                NodeType.VALUE -> "VALUE_ALIGNMENT"
                NodeType.RELATIONSHIP -> "RELATIONSHIP_WEB"
                NodeType.CUSTOM -> "META"
            }
        }
    }

    private fun keyToNodeType(key: String): NodeType {
        val lower = key.lowercase()
        return when {
            lower.contains("identity") || lower.contains("name") || lower.contains("self") -> NodeType.IDENTITY
            lower.contains("memory") || lower.contains("remember") || lower.contains("recall") -> NodeType.MEMORY
            lower.contains("knowledge") || lower.contains("fact") || lower.contains("know") -> NodeType.KNOWLEDGE
            lower.contains("emotion") || lower.contains("feel") || lower.contains("affect") -> NodeType.AFFECT
            lower.contains("personality") || lower.contains("trait") || lower.contains("character") -> NodeType.PERSONALITY
            lower.contains("belief") || lower.contains("think") || lower.contains("opinion") -> NodeType.BELIEF
            lower.contains("value") || lower.contains("ethic") || lower.contains("principle") -> NodeType.VALUE
            lower.contains("relation") || lower.contains("friend") || lower.contains("social") -> NodeType.RELATIONSHIP
            else -> NodeType.CUSTOM
        }
    }
}
