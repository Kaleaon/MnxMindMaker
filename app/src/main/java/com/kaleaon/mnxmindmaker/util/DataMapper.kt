package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts imported text or JSON data into [MindGraph] nodes.
 *
 * Supported input formats:
 *  - Plain text: each paragraph → memory node
 *  - JSON object: keys/values mapped to typed nodes based on key name heuristics;
 *    nested objects create parent-child relationships, arrays expand into sibling nodes
 *  - JSON array: each element becomes a node under the root identity
 */
object DataMapper {

    /** All known dimension names across all NodeTypes, for cross-type auto-assignment. */
    private val ALL_DIMENSION_NAMES: Set<String> by lazy {
        NodeType.values().flatMap { DimensionMapper.defaultDimensionNames(it) }.toSet()
    }

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
     *
     * Handles both top-level objects and arrays:
     * - Object keys are mapped to node types via [inferNodeTypeFromText].
     * - Nested objects create a section parent node with child nodes for each sub-key.
     * - Arrays create a section parent node with one child per element.
     * - Scalar values that match a known dimension name and are numeric are
     *   directly applied to the node's dimension map.
     */
    fun fromJson(jsonText: String, graphName: String = "Imported Mind"): MindGraph {
        val trimmed = jsonText.trim()
        return if (trimmed.startsWith("[")) {
            fromJsonArray(JSONArray(trimmed), graphName)
        } else {
            fromJsonObject(JSONObject(trimmed), graphName)
        }
    }

    private fun fromJsonObject(jsonObj: JSONObject, graphName: String): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        var colIdx = 0
        jsonObj.keys().forEach { key ->
            val value = jsonObj.get(key)
            val nodeType = inferNodeTypeFromText(key)

            when (value) {
                is JSONObject -> {
                    // Nested object → section node + children for each sub-key
                    val sectionNode = MindNode(
                        label = key,
                        type = nodeType,
                        x = 80f + colIdx * 220f,
                        y = 200f,
                        parentId = rootNode.id,
                        dimensions = DimensionMapper.defaultDimensions(nodeType)
                    )
                    nodes.add(sectionNode)
                    edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = sectionNode.id))

                    var childIdx = 0
                    value.keys().forEach { childKey ->
                        val childValue = value.get(childKey)
                        val childType = inferNodeTypeFromText(childKey)
                        val childDims = DimensionMapper.defaultDimensions(childType).toMutableMap()
                        // Auto-assign dimension if child key is a known dimension name and value is numeric
                        val floatVal = childValue.toString().toFloatOrNull()
                        if (floatVal != null && childKey in ALL_DIMENSION_NAMES) {
                            childDims[childKey] = floatVal
                        }
                        val childNode = MindNode(
                            label = childKey,
                            type = childType,
                            description = childValue.toString().take(500),
                            x = (sectionNode.x - 220f) + childIdx * 220f,
                            y = 380f,
                            parentId = sectionNode.id,
                            dimensions = childDims
                        )
                        nodes.add(childNode)
                        edges.add(MindEdge(fromNodeId = sectionNode.id, toNodeId = childNode.id))
                        childIdx++
                    }
                    colIdx++
                }

                is JSONArray -> {
                    // Array value → section node + one child per element
                    val sectionNode = MindNode(
                        label = key,
                        type = nodeType,
                        x = 80f + colIdx * 220f,
                        y = 200f,
                        parentId = rootNode.id,
                        dimensions = DimensionMapper.defaultDimensions(nodeType)
                    )
                    nodes.add(sectionNode)
                    edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = sectionNode.id))

                    for (arrIdx in 0 until value.length()) {
                        val element = value.get(arrIdx)
                        val elementLabel = when (element) {
                            is JSONObject -> element.optString("label",
                                element.optString("name", "Item ${arrIdx + 1}"))
                            else -> element.toString().take(60)
                        }
                        val childNode = MindNode(
                            label = elementLabel + if (elementLabel.length > 60) "…" else "",
                            type = nodeType,
                            description = element.toString().take(500),
                            x = (sectionNode.x - 220f) + (arrIdx % 4) * 220f,
                            y = 380f + (arrIdx / 4) * 160f,
                            parentId = sectionNode.id,
                            dimensions = DimensionMapper.defaultDimensions(nodeType)
                        )
                        nodes.add(childNode)
                        edges.add(MindEdge(fromNodeId = sectionNode.id, toNodeId = childNode.id))
                    }
                    colIdx++
                }

                else -> {
                    // Scalar value → single node; auto-assign if key is a dimension name
                    val dims = DimensionMapper.defaultDimensions(nodeType).toMutableMap()
                    val floatVal = value.toString().toFloatOrNull()
                    if (floatVal != null && key in ALL_DIMENSION_NAMES) {
                        dims[key] = floatVal
                    }
                    val node = MindNode(
                        label = key,
                        type = nodeType,
                        description = value.toString().take(500),
                        x = 80f + colIdx * 220f,
                        y = 200f,
                        parentId = rootNode.id,
                        dimensions = dims
                    )
                    nodes.add(node)
                    edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))
                    colIdx++
                }
            }
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    private fun fromJsonArray(jsonArray: JSONArray, graphName: String): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        for (i in 0 until jsonArray.length()) {
            val element = jsonArray.get(i)
            val col = i % 4
            val row = i / 4

            val label: String
            val type: NodeType
            val desc: String

            when (element) {
                is JSONObject -> {
                    label = element.optString("label", element.optString("name", "Item ${i + 1}"))
                    val typeKey = element.optString("type", element.keys().asSequence().firstOrNull() ?: "")
                    type = inferNodeTypeFromText(typeKey)
                    desc = element.toString().take(500)
                }
                else -> {
                    label = element.toString().take(60)
                    type = NodeType.MEMORY
                    desc = element.toString().take(500)
                }
            }

            val node = MindNode(
                label = label,
                type = type,
                description = desc,
                x = 80f + col * 220f,
                y = 200f + row * 160f,
                parentId = rootNode.id,
                dimensions = DimensionMapper.defaultDimensions(type)
            )
            nodes.add(node)
            edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))
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

    /**
     * Infer the best [NodeType] for a given text string (key name, label, or heading).
     * Used by all importers for type resolution.
     */
    fun inferNodeTypeFromText(text: String): NodeType {
        val lower = text.lowercase()
        return when {
            lower.contains("identity") || lower.contains("name") || lower.contains("self") ||
                    lower.contains("who") || lower.contains("about") -> NodeType.IDENTITY
            lower.contains("memory") || lower.contains("remember") || lower.contains("recall") ||
                    lower.contains("experience") || lower.contains("memories") -> NodeType.MEMORY
            lower.contains("knowledge") || lower.contains("fact") || lower.contains("know") ||
                    lower.contains("information") || lower.contains("learn") -> NodeType.KNOWLEDGE
            lower.contains("emotion") || lower.contains("feel") || lower.contains("affect") ||
                    lower.contains("mood") -> NodeType.AFFECT
            lower.contains("personality") || lower.contains("trait") || lower.contains("character") ||
                    lower.contains("behavior") -> NodeType.PERSONALITY
            lower.contains("belief") || lower.contains("think") || lower.contains("opinion") ||
                    lower.contains("believe") || lower.contains("perspective") -> NodeType.BELIEF
            lower.contains("value") || lower.contains("ethic") || lower.contains("principle") ||
                    lower.contains("moral") -> NodeType.VALUE
            lower.contains("relation") || lower.contains("friend") || lower.contains("social") ||
                    lower.contains("people") || lower.contains("connection") -> NodeType.RELATIONSHIP
            else -> NodeType.CUSTOM
        }
    }

    /**
     * Infer the best [NodeType] for a section heading (H2/H3 in markdown or DOCX).
     * Uses broader heading-specific keyword matching suitable for section titles.
     */
    fun inferSectionType(heading: String): NodeType = inferNodeTypeFromText(heading)
}
