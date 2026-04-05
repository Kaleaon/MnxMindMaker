package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry(
    private val getGraph: () -> MindGraph,
    private val setGraph: (MindGraph) -> Unit
) {

    private val specs = listOf(
        ToolSpec(
            name = "get_graph_summary",
            description = "Return graph-level summary with node/edge counts and name.",
            operationClass = ToolOperationClass.READ_ONLY
        ),
        ToolSpec(
            name = "list_nodes",
            description = "List nodes in the graph with id, label, type.",
            operationClass = ToolOperationClass.READ_ONLY
        ),
        ToolSpec(
            name = "get_node",
            description = "Get detailed node by id.",
            operationClass = ToolOperationClass.READ_ONLY,
            inputSchema = JSONObject().put("type", "object").put("properties", JSONObject().put("node_id", JSONObject().put("type", "string")))
        ),
        ToolSpec(
            name = "add_node",
            description = "Create a node. Args: label, type, description(optional), parent_id(optional).",
            operationClass = ToolOperationClass.MUTATING
        ),
        ToolSpec(
            name = "link_nodes",
            description = "Create edge between source_node_id and target_node_id.",
            operationClass = ToolOperationClass.MUTATING
        ),
        ToolSpec(
            name = "set_node_attribute",
            description = "Set node attributes map key/value for a node id.",
            operationClass = ToolOperationClass.MUTATING
        )
    )

    fun specs(): List<ToolSpec> = specs

    fun findSpec(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    fun invoke(invocation: ToolInvocation): ToolResult {
        return when (invocation.name) {
            "get_graph_summary" -> getGraphSummary(invocation)
            "list_nodes" -> listNodes(invocation)
            "get_node" -> getNode(invocation)
            "add_node" -> addNode(invocation)
            "link_nodes" -> linkNodes(invocation)
            "set_node_attribute" -> setNodeAttribute(invocation)
            else -> ToolResult(invocation.id, invocation.name, false, "Unknown tool: ${invocation.name}")
        }
    }

    private fun getGraphSummary(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val payload = JSONObject()
            .put("graph_id", graph.id)
            .put("name", graph.name)
            .put("node_count", graph.nodes.size)
            .put("edge_count", graph.edges.size)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun listNodes(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val payload = JSONObject().put("nodes", JSONArray().apply {
            graph.nodes.forEach { node ->
                put(JSONObject()
                    .put("id", node.id)
                    .put("label", node.label)
                    .put("type", node.type.name)
                    .put("parent_id", node.parentId)
                )
            }
        })
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun getNode(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val nodeId = invocation.arguments.optString("node_id")
        val node = graph.nodes.firstOrNull { it.id == nodeId }
            ?: return ToolResult(invocation.id, invocation.name, false, "Node not found: $nodeId")
        val payload = nodeToJson(node)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun addNode(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val label = invocation.arguments.optString("label").trim()
        if (label.isBlank()) {
            return ToolResult(invocation.id, invocation.name, false, "label is required")
        }
        val type = parseNodeType(invocation.arguments.optString("type", NodeType.CUSTOM.name))
        val description = invocation.arguments.optString("description", "")
        val parentId = invocation.arguments.optString("parent_id", "").ifBlank { null }
        val x = invocation.arguments.optDouble("x", (100..700).random().toDouble()).toFloat()
        val y = invocation.arguments.optDouble("y", (100..600).random().toDouble()).toFloat()

        val node = MindNode(
            label = label,
            type = type,
            description = description,
            x = x,
            y = y,
            parentId = parentId,
            dimensions = DimensionMapper.defaultDimensions(type)
        )
        val updatedNodes = graph.nodes.toMutableList().also { it.add(node) }
        val updatedEdges = graph.edges.toMutableList()
        if (parentId != null) {
            updatedEdges.add(MindEdge(fromNodeId = parentId, toNodeId = node.id))
        }
        setGraph(graph.copy(nodes = updatedNodes, edges = updatedEdges, modifiedAt = System.currentTimeMillis()))

        val payload = nodeToJson(node)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun linkNodes(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val sourceId = invocation.arguments.optString("source_node_id")
        val targetId = invocation.arguments.optString("target_node_id")
        if (sourceId.isBlank() || targetId.isBlank()) {
            return ToolResult(invocation.id, invocation.name, false, "source_node_id and target_node_id are required")
        }
        if (graph.nodes.none { it.id == sourceId } || graph.nodes.none { it.id == targetId }) {
            return ToolResult(invocation.id, invocation.name, false, "source or target node not found")
        }
        val alreadyLinked = graph.edges.any { it.fromNodeId == sourceId && it.toNodeId == targetId }
        if (alreadyLinked) {
            return ToolResult(invocation.id, invocation.name, true, "Edge already exists")
        }
        val edge = MindEdge(fromNodeId = sourceId, toNodeId = targetId)
        val updatedEdges = graph.edges.toMutableList().also { it.add(edge) }
        setGraph(graph.copy(edges = updatedEdges, modifiedAt = System.currentTimeMillis()))
        val payload = JSONObject()
            .put("edge_id", edge.id)
            .put("from_node_id", edge.fromNodeId)
            .put("to_node_id", edge.toNodeId)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun setNodeAttribute(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val nodeId = invocation.arguments.optString("node_id")
        val key = invocation.arguments.optString("key")
        val value = invocation.arguments.optString("value")
        if (nodeId.isBlank() || key.isBlank()) {
            return ToolResult(invocation.id, invocation.name, false, "node_id and key are required")
        }
        val updatedNodes = graph.nodes.map { node ->
            if (node.id != nodeId) {
                node
            } else {
                val updatedAttrs = node.attributes.toMutableMap()
                updatedAttrs[key] = value
                node.copy(attributes = updatedAttrs)
            }
        }
        if (updatedNodes.none { it.id == nodeId }) {
            return ToolResult(invocation.id, invocation.name, false, "Node not found: $nodeId")
        }
        setGraph(graph.copy(nodes = updatedNodes.toMutableList(), modifiedAt = System.currentTimeMillis()))
        val payload = JSONObject().put("node_id", nodeId).put("key", key).put("value", value)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun parseNodeType(typeRaw: String): NodeType {
        return NodeType.entries.firstOrNull {
            it.name.equals(typeRaw, ignoreCase = true) || it.displayName.equals(typeRaw, ignoreCase = true)
        } ?: NodeType.CUSTOM
    }

    private fun nodeToJson(node: MindNode): JSONObject {
        return JSONObject()
            .put("id", node.id)
            .put("label", node.label)
            .put("type", node.type.name)
            .put("description", node.description)
            .put("x", node.x)
            .put("y", node.y)
            .put("parent_id", node.parentId)
            .put("attributes", JSONObject().apply { node.attributes.forEach { (k, v) -> put(k, v) } })
    }
}
