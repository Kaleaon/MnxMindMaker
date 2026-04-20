package com.kaleaon.mnxmindmaker.util.tooling

import android.content.Context
import android.util.Log
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import com.kaleaon.mnxmindmaker.util.memory.MemoryManager
import com.kaleaon.mnxmindmaker.util.moderation.ModerationAction
import com.kaleaon.mnxmindmaker.util.moderation.ModerationPipeline
import com.kaleaon.mnxmindmaker.util.moderation.ModerationRequest
import com.kaleaon.mnxmindmaker.util.moderation.ModerationResult
import com.kaleaon.mnxmindmaker.util.moderation.ModerationStage
import com.kaleaon.mnxmindmaker.util.moderation.SensitiveEntityModerationPolicy
import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry(
    private val getGraph: () -> MindGraph,
    private val setGraph: (MindGraph) -> Unit,
    private val memoryManager: MemoryManager? = null,
    private val moderationPipeline: ModerationPipeline = ModerationPipeline(listOf(SensitiveEntityModerationPolicy())),
    context: Context? = null
) {

    private val approvedHandlers: Map<String, (ToolInvocation) -> ToolResult> = mapOf(
        "graph.read.get_summary" to ::getGraphSummary,
        "graph.read.list_nodes" to ::listNodes,
        "graph.read.get_node" to ::getNode,
        "graph.write.add_node" to ::addNode,
        "graph.write.link_nodes" to ::linkNodes,
        "graph.write.set_node_attribute" to ::setNodeAttribute,
        "memory.read.search" to ::memorySearch,
        "memory.write.upsert" to ::memoryUpsert,
        "memory.write.edit" to ::memoryEdit,
        "memory.write.delete" to ::memoryDelete,
        "memory.read.status" to ::memoryStatus,
        "graph.read.graph_refactor_diagnostics" to ::graphRefactorDiagnostics,
        "graph.write.graph_refactor_apply" to ::graphRefactorApply,
        "graph.read.taxonomy_normalization_diagnostics" to ::taxonomyNormalizationDiagnostics,
        "graph.write.taxonomy_normalization_apply" to ::taxonomyNormalizationApply,
        "memory.read.stale_memory_diagnostics" to ::staleMemoryDiagnostics,
        "graph.read.link_repair_diagnostics" to ::linkRepairDiagnostics,
        "graph.write.link_repair_apply" to ::linkRepairApply
    )

    private val builtInToolDefs = listOf(
        BuiltInToolDef(
            spec = ToolSpec(
                name = "get_graph_summary",
                description = "Return graph-level summary with node/edge counts and name.",
                operationClass = ToolOperationClass.READ_ONLY
            ),
            handlerId = "graph.read.get_summary"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "list_nodes",
                description = "List nodes in the graph with id, label, type.",
                operationClass = ToolOperationClass.READ_ONLY
            ),
            handlerId = "graph.read.list_nodes"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "get_node",
                description = "Get detailed node by id.",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject().put("type", "object").put("properties", JSONObject().put("node_id", JSONObject().put("type", "string")))
            ),
            handlerId = "graph.read.get_node"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "add_node",
                description = "Create a node. Args: label, type, description(optional), parent_id(optional).",
                operationClass = ToolOperationClass.MUTATING
            ),
            handlerId = "graph.write.add_node"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "link_nodes",
                description = "Create edge between source_node_id and target_node_id.",
                operationClass = ToolOperationClass.MUTATING
            ),
            handlerId = "graph.write.link_nodes"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "set_node_attribute",
                description = "Set node attributes map key/value for a node id.",
                operationClass = ToolOperationClass.MUTATING
            ),
            handlerId = "graph.write.set_node_attribute"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "memory_search",
                description = "Search memory entries by query text. Safety: read-only operation.",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put("required", JSONArray().put("query"))
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "string").put("minLength", 1))
                        .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50)))
            ),
            handlerId = "memory.read.search"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "memory_upsert",
                description = "Upsert memory by id/category/value/sensitivity. Safety: restricted sensitivity is denied and high sensitivity requires allow_high_sensitivity=true.",
                operationClass = ToolOperationClass.MUTATING,
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put("required", JSONArray().put("memory_id").put("category").put("value").put("sensitivity"))
                    .put("properties", JSONObject()
                        .put("memory_id", JSONObject().put("type", "string").put("minLength", 1))
                        .put("category", JSONObject().put("type", "string").put("enum", JSONArray().put("profile").put("semantic")))
                        .put("value", JSONObject().put("type", "string").put("minLength", 1))
                        .put("label", JSONObject().put("type", "string"))
                        .put("tags", JSONObject().put("type", "string"))
                        .put("writing_style", JSONObject().put("type", "string"))
                        .put("sensitivity", JSONObject().put("type", "string").put("enum", JSONArray().put("low").put("medium").put("high").put("restricted")))
                        .put("allow_high_sensitivity", JSONObject().put("type", "boolean")))
            ),
            handlerId = "memory.write.upsert"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "memory_edit",
                description = "Edit memory by id. Safety: restricted sensitivity is denied and high sensitivity requires allow_high_sensitivity=true.",
                operationClass = ToolOperationClass.MUTATING,
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put("required", JSONArray().put("memory_id"))
                    .put("properties", JSONObject()
                        .put("memory_id", JSONObject().put("type", "string").put("minLength", 1))
                        .put("value", JSONObject().put("type", "string"))
                        .put("label", JSONObject().put("type", "string"))
                        .put("tags", JSONObject().put("type", "string"))
                        .put("writing_style", JSONObject().put("type", "string"))
                        .put("sensitivity", JSONObject().put("type", "string").put("enum", JSONArray().put("low").put("medium").put("high").put("restricted")))
                        .put("allow_high_sensitivity", JSONObject().put("type", "boolean")))
            ),
            handlerId = "memory.write.edit"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "memory_delete",
                description = "Delete memory by id. Safety: restricted sensitivity is denied and high sensitivity requires allow_high_sensitivity=true.",
                operationClass = ToolOperationClass.MUTATING,
                inputSchema = JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put("required", JSONArray().put("memory_id"))
                    .put("properties", JSONObject()
                        .put("memory_id", JSONObject().put("type", "string").put("minLength", 1))
                        .put("allow_high_sensitivity", JSONObject().put("type", "boolean")))
            ),
            handlerId = "memory.write.delete"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "memory_status",
                description = "Return current memory mode and counters. Safety: read-only operation.",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            ),
            handlerId = "memory.read.status"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "graph_refactor_diagnostics",
                description = "Read-only diagnostics for deterministic graph refactoring opportunities: duplicate edges, self loops, duplicate labels, and orphan nodes.",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            ),
            handlerId = "graph.read.graph_refactor_diagnostics"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "graph_refactor_apply",
                description = "Deterministically refactor graph by removing self-loops, removing duplicate edges, and sorting nodes. Requires explicit approval_token=APPROVED.",
                operationClass = ToolOperationClass.MUTATING,
                requiresConfirmation = true,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false).put(
                    "properties",
                    JSONObject()
                        .put("approval_token", JSONObject().put("type", "string"))
                        .put("remove_self_loops", JSONObject().put("type", "boolean"))
                        .put("remove_duplicate_edges", JSONObject().put("type", "boolean"))
                        .put("sort_nodes", JSONObject().put("type", "boolean"))
                )
            ),
            handlerId = "graph.write.graph_refactor_apply"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "taxonomy_normalization_diagnostics",
                description = "Read-only diagnostics for taxonomy normalization drift (taxonomy_key/canonical_type mismatches).",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            ),
            handlerId = "graph.read.taxonomy_normalization_diagnostics"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "taxonomy_normalization_apply",
                description = "Deterministically normalize taxonomy attributes (taxonomy_key and canonical_type). Requires explicit approval_token=APPROVED.",
                operationClass = ToolOperationClass.MUTATING,
                requiresConfirmation = true,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false).put(
                    "properties",
                    JSONObject()
                        .put("approval_token", JSONObject().put("type", "string"))
                        .put("node_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                )
            ),
            handlerId = "graph.write.taxonomy_normalization_apply"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "stale_memory_diagnostics",
                description = "Read-only stale-memory detection for memory nodes using timestamp age threshold.",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false).put(
                    "properties",
                    JSONObject()
                        .put("max_age_ms", JSONObject().put("type", "integer"))
                        .put("include_missing_timestamp", JSONObject().put("type", "boolean"))
                )
            ),
            handlerId = "memory.read.stale_memory_diagnostics"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "link_repair_diagnostics",
                description = "Read-only diagnostics for missing-node edges, duplicate links, and missing parent links.",
                operationClass = ToolOperationClass.READ_ONLY,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false)
            ),
            handlerId = "graph.read.link_repair_diagnostics"
        ),
        BuiltInToolDef(
            spec = ToolSpec(
                name = "link_repair_apply",
                description = "Deterministically repair links by removing broken/duplicate edges and adding missing parent links. Requires explicit approval_token=APPROVED.",
                operationClass = ToolOperationClass.MUTATING,
                requiresConfirmation = true,
                inputSchema = JSONObject().put("type", "object").put("additionalProperties", false).put(
                    "properties",
                    JSONObject()
                        .put("approval_token", JSONObject().put("type", "string"))
                        .put("remove_invalid_edges", JSONObject().put("type", "boolean"))
                        .put("remove_duplicate_edges", JSONObject().put("type", "boolean"))
                        .put("add_missing_parent_links", JSONObject().put("type", "boolean"))
                )
            ),
            handlerId = "graph.write.link_repair_apply"
        )
    )

    private val merged = mergeBuiltInsAndSkillPacks(context)
    private val specs: List<ToolSpec> = merged.specs
    private val handlersByToolName: Map<String, (ToolInvocation) -> ToolResult> = merged.handlersByToolName

    fun specs(): List<ToolSpec> = specs

    fun findSpec(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    fun invoke(invocation: ToolInvocation): ToolResult {
        val handler = handlersByToolName[invocation.name]
            ?: return ToolResult(invocation.id, invocation.name, false, "Unknown tool: ${invocation.name}")
        return handler(invocation)
    }

    private fun mergeBuiltInsAndSkillPacks(context: Context?): MergedRegistry {
        val builtInSpecs = builtInToolDefs.map { it.spec }
        val handlers = builtInToolDefs.associate { it.spec.name to approvedHandlers.getValue(it.handlerId) }.toMutableMap()

        if (context == null) {
            val report = SkillPackLoadReport()
            SkillPackDiagnosticsStore.update(report)
            return MergedRegistry(builtInSpecs, handlers)
        }

        val loader = SkillPackLoader(
            assets = context.assets,
            validator = SkillManifestValidator(approvedHandlerIds = approvedHandlers.keys)
        )
        val report = loader.load()

        val manifestSpecs = mutableListOf<ToolSpec>()
        report.loadedPacks.forEach { pack ->
            val duplicate = pack.tools.firstOrNull { tool -> handlers.containsKey(tool.name) }
            if (duplicate != null) {
                val message = "Skipping skill pack ${pack.source}: duplicate tool '${duplicate.name}'"
                Log.w(TAG, message)
                return@forEach
            }

            pack.tools.forEachIndexed { idx, tool ->
                val manifestTool = pack.manifest.tools[idx]
                val handler = approvedHandlers[manifestTool.handlerId]
                if (handler == null) {
                    Log.w(TAG, "Skipping tool ${tool.name} from ${pack.source}: unknown handler ${manifestTool.handlerId}")
                    return@forEachIndexed
                }
                manifestSpecs += tool
                handlers[tool.name] = handler
            }
        }

        SkillPackDiagnosticsStore.update(report)
        return MergedRegistry(builtInSpecs + manifestSpecs, handlers)
    }

    private fun graphRefactorDiagnostics(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val edgeKeyCounts = graph.edges.groupingBy { EdgePair(it.fromNodeId, it.toNodeId) }.eachCount()
        val duplicates = edgeKeyCounts.filterValues { it > 1 }
        val nodeIds = graph.nodes.map { it.id }.toSet()
        val connectedIds = graph.edges.flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet()
        val orphanIds = graph.nodes
            .asSequence()
            .filter { it.id !in connectedIds && it.parentId.isNullOrBlank() }
            .map { it.id }
            .sorted()
            .toList()
        val labelGroups = graph.nodes
            .groupBy { it.label.trim().lowercase() }
            .filterKeys { it.isNotBlank() }
            .filterValues { it.size > 1 }

        val payload = JSONObject()
            .put("node_count", graph.nodes.size)
            .put("edge_count", graph.edges.size)
            .put("self_loop_count", graph.edges.count { it.fromNodeId == it.toNodeId })
            .put("duplicate_edge_pair_count", duplicates.size)
            .put("duplicate_edge_pairs", JSONArray().apply {
                duplicates.toSortedMap(compareBy<EdgePair> { it.fromNodeId }.thenBy { it.toNodeId }).forEach { (pair, count) ->
                    put(JSONObject().put("pair", pair.serialized()).put("count", count))
                }
            })
            .put("duplicate_label_group_count", labelGroups.size)
            .put("duplicate_label_groups", JSONArray().apply {
                labelGroups.toSortedMap().forEach { (label, nodes) ->
                    put(JSONObject().put("label", label).put("node_ids", JSONArray(nodes.map { it.id }.sorted())))
                }
            })
            .put("orphan_node_ids", JSONArray(orphanIds))
            .put("unknown_edge_endpoint_count", graph.edges.count { it.fromNodeId !in nodeIds || it.toNodeId !in nodeIds })
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun graphRefactorApply(invocation: ToolInvocation): ToolResult {
        requireMutationApproval(invocation)?.let { return it }
        val graph = getGraph()
        val removeSelfLoops = invocation.arguments.optBoolean("remove_self_loops", true)
        val removeDuplicates = invocation.arguments.optBoolean("remove_duplicate_edges", true)
        val sortNodes = invocation.arguments.optBoolean("sort_nodes", true)

        val originalEdgeCount = graph.edges.size
        var nextEdges = graph.edges.toList()
        if (removeSelfLoops) {
            nextEdges = nextEdges.filterNot { it.fromNodeId == it.toNodeId }
        }
        if (removeDuplicates) {
            nextEdges = nextEdges
                .groupBy { EdgePair(it.fromNodeId, it.toNodeId) }
                .toSortedMap()
                .values
                .map { group -> group.minByOrNull { it.id } ?: group.first() }
        }

        val nextNodes = if (sortNodes) {
            graph.nodes.sortedWith(compareBy<MindNode> { it.label.lowercase() }.thenBy { it.id })
        } else {
            graph.nodes.toList()
        }

        setGraph(graph.copy(nodes = nextNodes.toMutableList(), edges = nextEdges.toMutableList(), modifiedAt = System.currentTimeMillis()))
        val payload = JSONObject()
            .put("status", "refactored")
            .put("edges_removed", originalEdgeCount - nextEdges.size)
            .put("node_order_changed", nextNodes.map { it.id } != graph.nodes.map { it.id })
            .put("node_count", nextNodes.size)
            .put("edge_count", nextEdges.size)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun taxonomyNormalizationDiagnostics(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val drifted = graph.nodes.mapNotNull { node ->
            val expectedKey = canonicalTaxonomyKey(node.label)
            val expectedType = node.type.name.lowercase()
            val currentKey = node.attributes["taxonomy_key"].orEmpty()
            val currentType = node.attributes["canonical_type"].orEmpty()
            if (currentKey == expectedKey && currentType == expectedType) {
                null
            } else {
                JSONObject()
                    .put("node_id", node.id)
                    .put("current_taxonomy_key", currentKey)
                    .put("expected_taxonomy_key", expectedKey)
                    .put("current_canonical_type", currentType)
                    .put("expected_canonical_type", expectedType)
            }
        }.sortedBy { it.getString("node_id") }

        val payload = JSONObject()
            .put("drifted_count", drifted.size)
            .put("drifted_nodes", JSONArray(drifted))
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun taxonomyNormalizationApply(invocation: ToolInvocation): ToolResult {
        requireMutationApproval(invocation)?.let { return it }
        val graph = getGraph()
        val scopedIds = invocation.arguments.optJSONArray("node_ids")?.let { ids ->
            buildSet {
                for (i in 0 until ids.length()) {
                    val id = ids.optString(i).trim()
                    if (id.isNotBlank()) add(id)
                }
            }
        }
        var changed = 0
        val updatedNodes = graph.nodes.map { node ->
            if (scopedIds != null && node.id !in scopedIds) return@map node
            val expectedKey = canonicalTaxonomyKey(node.label)
            val expectedType = node.type.name.lowercase()
            val attrs = node.attributes.toMutableMap()
            val before = attrs.toMap()
            attrs["taxonomy_key"] = expectedKey
            attrs["canonical_type"] = expectedType
            if (before != attrs) changed++
            node.copy(attributes = attrs)
        }
        setGraph(graph.copy(nodes = updatedNodes.toMutableList(), modifiedAt = System.currentTimeMillis()))
        val payload = JSONObject()
            .put("status", "normalized")
            .put("updated_nodes", changed)
            .put("scoped_node_count", scopedIds?.size ?: graph.nodes.size)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun staleMemoryDiagnostics(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val now = System.currentTimeMillis()
        val maxAgeMs = invocation.arguments.optLong("max_age_ms", 30L * 24 * 60 * 60 * 1000)
        val includeMissing = invocation.arguments.optBoolean("include_missing_timestamp", true)
        val stale = graph.nodes
            .asSequence()
            .filter { it.type == NodeType.MEMORY }
            .mapNotNull { node ->
                val ts = node.attributes["timestamp"]?.toLongOrNull()
                if (ts == null) {
                    if (!includeMissing) return@mapNotNull null
                    return@mapNotNull JSONObject()
                        .put("node_id", node.id)
                        .put("label", node.label)
                        .put("reason", "missing_timestamp")
                }
                val age = now - ts
                if (age <= maxAgeMs) return@mapNotNull null
                JSONObject()
                    .put("node_id", node.id)
                    .put("label", node.label)
                    .put("timestamp", ts)
                    .put("age_ms", age)
                    .put("reason", "age_exceeds_threshold")
            }
            .sortedBy { it.getString("node_id") }
            .toList()
        val payload = JSONObject()
            .put("max_age_ms", maxAgeMs)
            .put("memory_node_count", graph.nodes.count { it.type == NodeType.MEMORY })
            .put("stale_count", stale.size)
            .put("stale_nodes", JSONArray(stale))
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun linkRepairDiagnostics(invocation: ToolInvocation): ToolResult {
        val graph = getGraph()
        val nodeIds = graph.nodes.map { it.id }.toSet()
        val invalidEdges = graph.edges.filter { it.fromNodeId !in nodeIds || it.toNodeId !in nodeIds }
        val duplicateEdges = graph.edges
            .groupBy { EdgePair(it.fromNodeId, it.toNodeId) }
            .filterValues { it.size > 1 }
        val missingParentLinks = graph.nodes
            .asSequence()
            .filter { !it.parentId.isNullOrBlank() }
            .filter { node ->
                graph.edges.none { edge -> edge.fromNodeId == node.parentId && edge.toNodeId == node.id }
            }
            .map { node ->
                JSONObject()
                    .put("node_id", node.id)
                    .put("parent_id", node.parentId)
            }
            .sortedBy { it.getString("node_id") }
            .toList()

        val payload = JSONObject()
            .put("invalid_edge_count", invalidEdges.size)
            .put("invalid_edges", JSONArray().apply {
                invalidEdges.sortedBy { it.id }.forEach { edge ->
                    put(JSONObject().put("edge_id", edge.id).put("from_node_id", edge.fromNodeId).put("to_node_id", edge.toNodeId))
                }
            })
            .put("duplicate_edge_pair_count", duplicateEdges.size)
            .put("duplicate_edge_pairs", JSONArray().apply {
                duplicateEdges.toSortedMap(compareBy<EdgePair> { it.fromNodeId }.thenBy { it.toNodeId }).forEach { (pair, edges) ->
                    put(JSONObject().put("pair", pair.serialized()).put("count", edges.size))
                }
            })
            .put("missing_parent_link_count", missingParentLinks.size)
            .put("missing_parent_links", JSONArray(missingParentLinks))
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun linkRepairApply(invocation: ToolInvocation): ToolResult {
        requireMutationApproval(invocation)?.let { return it }
        val graph = getGraph()
        val nodeIds = graph.nodes.map { it.id }.toSet()
        val removeInvalid = invocation.arguments.optBoolean("remove_invalid_edges", true)
        val removeDuplicates = invocation.arguments.optBoolean("remove_duplicate_edges", true)
        val addMissingParent = invocation.arguments.optBoolean("add_missing_parent_links", true)

        val originalEdgeCount = graph.edges.size
        var nextEdges = graph.edges.toList()
        if (removeInvalid) {
            nextEdges = nextEdges.filter { it.fromNodeId in nodeIds && it.toNodeId in nodeIds }
        }
        if (removeDuplicates) {
            nextEdges = nextEdges
                .groupBy { EdgePair(it.fromNodeId, it.toNodeId) }
                .toSortedMap()
                .values
                .map { group -> group.minByOrNull { it.id } ?: group.first() }
        }
        if (addMissingParent) {
            val existingPairs = nextEdges.map { EdgePair(it.fromNodeId, it.toNodeId) }.toMutableSet()
            graph.nodes
                .filter { !it.parentId.isNullOrBlank() && it.parentId in nodeIds }
                .sortedBy { it.id }
                .forEach { node ->
                    val key = EdgePair(node.parentId!!, node.id)
                    if (key !in existingPairs) {
                        nextEdges = nextEdges + MindEdge(
                            id = "auto-parent:${node.parentId}:${node.id}",
                            fromNodeId = node.parentId!!,
                            toNodeId = node.id
                        )
                        existingPairs += key
                    }
                }
        }

        setGraph(graph.copy(edges = nextEdges.toMutableList(), modifiedAt = System.currentTimeMillis()))
        val payload = JSONObject()
            .put("status", "repaired")
            .put("edges_before", originalEdgeCount)
            .put("edges_after", nextEdges.size)
            .put("edges_delta", nextEdges.size - originalEdgeCount)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun memorySearch(invocation: ToolInvocation): ToolResult {
        val manager = memoryManager ?: return ToolResult(invocation.id, invocation.name, false, "MemoryManager is not configured")
        val query = invocation.arguments.optString("query").trim()
        val limit = invocation.arguments.optInt("limit", 10).coerceIn(1, 50)
        val characterId = invocation.arguments.optString("character_id").trim().ifBlank { null }
        val payload = JSONObject()
            .put("query", query)
            .put("limit", limit)
            .put("character_id", characterId)
            .put("results", JSONArray().apply {
                manager.searchMemories(query, limit, characterIdHint = characterId).forEach { memory ->
                    put(JSONObject()
                        .put("id", memory.id)
                        .put("label", memory.label)
                        .put("description", memory.description)
                        .put("attributes", JSONObject().apply { memory.attributes.forEach { (k, v) -> put(k, v) } }))
                }
            })
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun moderationDeniedResult(invocation: ToolInvocation, moderation: ModerationResult): ToolResult =
        ToolResult(
            invocation.id,
            invocation.name,
            false,
            "memory_write_denied_by_moderation",
            JSONObject()
                .put("error", "moderation_denied")
                .put("policy_id", moderation.policyId)
                .put("reason", moderation.reason ?: "Denied by moderation policy")
                .put("detected_entity_types", JSONArray(moderation.detectedEntityTypes.toList()))
        )

    private fun memoryUpsert(invocation: ToolInvocation): ToolResult =
        withMemoryManager(invocation) { manager ->
            val args = invocation.arguments
            val memoryId = args.optString("memory_id").trim()
            val category = args.optString("category").lowercase()
            val sensitivity = args.optString("sensitivity", "low").lowercase()
            val policyViolation = enforceSensitivityPolicy(
                invocation = invocation,
                sensitivity = sensitivity,
                allowHighSensitivity = args.optBoolean("allow_high_sensitivity", false)
            )
            if (policyViolation != null) return@withMemoryManager policyViolation

            val value = args.optString("value")
            val moderationResult = moderationPipeline.moderate(
                ModerationRequest(text = value, stage = ModerationStage.MEMORY_WRITE, policyId = "memory_write")
            )
            if (moderationResult.action == ModerationAction.DENY) {
                return@withMemoryManager moderationDeniedResult(invocation, moderationResult)
            }
            val moderatedValue = moderationResult.text

            when (category) {
                "profile" -> manager.upsertProfileMemory(
                    key = memoryId,
                    value = moderatedValue,
                    writingStyle = args.optString("writing_style").ifBlank { null },
                    characterId = args.optString("character_id").ifBlank { null },
                    sensitivity = sensitivity
                )
                "semantic" -> manager.upsertSemanticMemory(
                    MindNode(
                        id = memoryId,
                        label = args.optString("label").ifBlank { memoryId },
                        type = NodeType.MEMORY,
                        description = moderatedValue,
                        attributes = mutableMapOf(
                            "semantic_subtype" to "semantic",
                            "memory_category" to "semantic",
                            "sensitivity" to sensitivity,
                            "timestamp" to System.currentTimeMillis().toString(),
                            "tags" to args.optString("tags")
                        ).apply {
                            args.optString("character_id").ifBlank { null }?.let { put("character_id", it) }
                        )
                    )
                )
                else -> return@withMemoryManager ToolResult(
                    invocation.id,
                    invocation.name,
                    false,
                    "Unsupported category: $category"
                )
            }
            val payload = JSONObject().put("status", "upserted").put("memory_id", memoryId).put("category", category)
            ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
        }

    private fun memoryEdit(invocation: ToolInvocation): ToolResult =
        withMemoryManager(invocation) { manager ->
            val args = invocation.arguments
            val memoryId = args.optString("memory_id").trim()
            val existing = manager.getMemory(memoryId)
                ?: return@withMemoryManager ToolResult(invocation.id, invocation.name, false, "Memory not found: $memoryId")
            val sensitivity = args.optString("sensitivity").ifBlank { existing.attributes["sensitivity"] ?: "low" }.lowercase()
            val policyViolation = enforceSensitivityPolicy(
                invocation = invocation,
                sensitivity = sensitivity,
                allowHighSensitivity = args.optBoolean("allow_high_sensitivity", false)
            )
            if (policyViolation != null) return@withMemoryManager policyViolation

            val requestedValue = args.optString("value").ifBlank { existing.description }
            val moderationResult = moderationPipeline.moderate(
                ModerationRequest(text = requestedValue, stage = ModerationStage.MEMORY_WRITE, policyId = "memory_write")
            )
            if (moderationResult.action == ModerationAction.DENY) {
                return@withMemoryManager moderationDeniedResult(invocation, moderationResult)
            }
            val moderatedValue = moderationResult.text

            val edited = manager.editMemory(memoryId) { node ->
                node.copy(
                    label = args.optString("label").ifBlank { node.label },
                    description = moderatedValue,
                    attributes = node.attributes.toMutableMap().apply {
                        if (args.has("tags")) put("tags", args.optString("tags"))
                        if (args.has("writing_style")) put("writing_style", args.optString("writing_style"))
                        if (args.has("character_id")) put("character_id", args.optString("character_id"))
                        if (args.has("sensitivity")) put("sensitivity", sensitivity)
                        put("timestamp", System.currentTimeMillis().toString())
                    }
                )
            }
            val payload = JSONObject().put("status", if (edited) "edited" else "memory_not_found").put("memory_id", memoryId)
            ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
        }

    private fun memoryDelete(invocation: ToolInvocation): ToolResult =
        withMemoryManager(invocation) { manager ->
            val memoryId = invocation.arguments.optString("memory_id").trim()
            val existing = manager.getMemory(memoryId)
                ?: return@withMemoryManager ToolResult(invocation.id, invocation.name, false, "Memory not found: $memoryId")
            val sensitivity = existing.attributes["sensitivity"]?.lowercase() ?: "low"
            val policyViolation = enforceSensitivityPolicy(
                invocation = invocation,
                sensitivity = sensitivity,
                allowHighSensitivity = invocation.arguments.optBoolean("allow_high_sensitivity", false)
            )
            if (policyViolation != null) return@withMemoryManager policyViolation
            val deleted = manager.deleteMemory(memoryId)
            val payload = JSONObject().put("status", if (deleted) "deleted" else "memory_not_found").put("memory_id", memoryId)
            ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
        }

    private fun memoryStatus(invocation: ToolInvocation): ToolResult {
        val manager = memoryManager ?: return ToolResult(invocation.id, invocation.name, false, "MemoryManager is not configured")
        val status = manager.status()
        val payload = JSONObject()
            .put("mode", status.mode.name.lowercase())
            .put("session_turn_count", status.sessionTurnCount)
            .put("profile_memory_count", status.profileMemoryCount)
            .put("semantic_memory_count", status.semanticMemoryCount)
        return ToolResult(invocation.id, invocation.name, true, payload.toString(), payload)
    }

    private fun withMemoryManager(invocation: ToolInvocation, block: (MemoryManager) -> ToolResult): ToolResult {
        val manager = memoryManager ?: return ToolResult(invocation.id, invocation.name, false, "MemoryManager is not configured")
        return block(manager)
    }

    private fun enforceSensitivityPolicy(
        invocation: ToolInvocation,
        sensitivity: String,
        allowHighSensitivity: Boolean
    ): ToolResult? {
        return when (sensitivity.lowercase()) {
            "restricted" -> ToolResult(
                toolUseId = invocation.id,
                isError = true,
                contentJson = JSONObject()
                    .put("error", "sensitivity_policy_violation")
                    .put("message", "Mutating restricted memory is denied")
                    .put("sensitivity", "restricted")
            )
            "high" -> if (!allowHighSensitivity) {
                ToolResult(
                    toolUseId = invocation.id,
                    isError = true,
                    contentJson = JSONObject()
                        .put("error", "sensitivity_policy_violation")
                        .put("message", "Mutating high-sensitivity memory requires allow_high_sensitivity=true")
                        .put("sensitivity", "high")
                )
            } else null
            else -> null
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
        val x = invocation.arguments.optDouble("x", deterministicCoordinate(label, "x", 100f, 700f).toDouble()).toFloat()
        val y = invocation.arguments.optDouble("y", deterministicCoordinate(label, "y", 100f, 600f).toDouble()).toFloat()

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

    private fun canonicalTaxonomyKey(label: String): String {
        return label.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "unlabeled" }
    }

    private fun deterministicCoordinate(seed: String, axis: String, min: Float, max: Float): Float {
        val range = max - min
        if (range <= 0f) return min
        val bucket = (seed.lowercase() + ":" + axis).hashCode().toLong().let { kotlin.math.abs(it) % 10_000L }
        return min + (bucket / 10_000f) * range
    }

    private fun requireMutationApproval(invocation: ToolInvocation): ToolResult? {
        val token = invocation.arguments.optString("approval_token")
        return if (token == MUTATION_APPROVAL_TOKEN) {
            null
        } else {
            ToolResult(
                toolUseId = invocation.id,
                isError = true,
                contentJson = JSONObject()
                    .put("error", "approval_required")
                    .put("message", "This mutating tool requires explicit approval_token=APPROVED")
            )
        }
    }

    data class BuiltInToolDef(val spec: ToolSpec, val handlerId: String)

    data class MergedRegistry(
        val specs: List<ToolSpec>,
        val handlersByToolName: Map<String, (ToolInvocation) -> ToolResult>
    )

    companion object {
        private const val MUTATION_APPROVAL_TOKEN = "APPROVED"
        private const val TAG = "ToolRegistry"

        fun approvedHandlerIds(): Set<String> = setOf(
            "graph.read.get_summary",
            "graph.read.list_nodes",
            "graph.read.get_node",
            "graph.write.add_node",
            "graph.write.link_nodes",
            "graph.write.set_node_attribute",
            "memory.read.search",
            "memory.write.upsert",
            "memory.write.edit",
            "memory.write.delete",
            "memory.read.status",
            "graph.read.graph_refactor_diagnostics",
            "graph.write.graph_refactor_apply",
            "graph.read.taxonomy_normalization_diagnostics",
            "graph.write.taxonomy_normalization_apply",
            "memory.read.stale_memory_diagnostics",
            "graph.read.link_repair_diagnostics",
            "graph.write.link_repair_apply"
        )
    }

    private data class EdgePair(val fromNodeId: String, val toNodeId: String) {
        fun serialized(): String = "$fromNodeId->$toNodeId"
    }
}
