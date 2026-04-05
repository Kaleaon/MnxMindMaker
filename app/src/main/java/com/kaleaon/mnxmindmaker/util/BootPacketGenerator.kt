package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.json.JSONArray
import org.json.JSONObject

object BootPacketGenerator {

    enum class Mode {
        FULL,
        EMERGENCY_WARNING,
        RELATIONSHIP,
        PROJECT,
        PUBLIC_PERSONA
    }

    data class BootPacket(
        val mode: Mode,
        val kernelSlice: List<MindNode>,
        val stateSlice: List<MindNode>,
        val relationshipSlice: List<MindNode>,
        val warningSlice: List<MindNode>,
        val memorySlice: List<MindNode>,
        val projectSlice: List<MindNode>,
        val driftRuleSlice: List<MindNode>
    ) {
        fun toJson(): String {
            val root = JSONObject()
                .put("mode", mode.name.lowercase())
                .put("kernel_slice", nodesToJson(kernelSlice))
                .put("state_slice", nodesToJson(stateSlice))
                .put("relationship_slice", nodesToJson(relationshipSlice))
                .put("warning_slice", nodesToJson(warningSlice))
                .put("memory_slice", nodesToJson(memorySlice))
                .put("project_slice", nodesToJson(projectSlice))
                .put("drift_rule_slice", nodesToJson(driftRuleSlice))
            return root.toString(2)
        }

        fun toMarkdown(): String = buildString {
            appendLine("# Boot Packet (${mode.name})")
            appendSection("Kernel", kernelSlice)
            appendSection("State", stateSlice)
            appendSection("Relationships", relationshipSlice)
            appendSection("Warnings", warningSlice)
            appendSection("Memories", memorySlice)
            appendSection("Projects", projectSlice)
            appendSection("Drift Rules", driftRuleSlice)
        }

        private fun StringBuilder.appendSection(title: String, nodes: List<MindNode>) {
            appendLine()
            appendLine("## $title")
            if (nodes.isEmpty()) {
                appendLine("- _none_")
                return
            }
            nodes.forEach { node ->
                appendLine("- **${node.label}** (${node.type.name})")
                if (node.description.isNotBlank()) appendLine("  - ${node.description}")
            }
        }

        private fun nodesToJson(nodes: List<MindNode>): JSONArray = JSONArray().apply {
            nodes.forEach { node ->
                put(
                    JSONObject()
                        .put("id", node.id)
                        .put("label", node.label)
                        .put("type", node.type.name)
                        .put("description", node.description)
                        .put("attributes", JSONObject(node.attributes as Map<*, *>))
                        .put("dimensions", JSONObject(node.dimensions))
                )
            }
        }
    }

    fun generate(
        graph: MindGraph,
        mode: Mode = Mode.FULL,
        prompt: String = "",
        task: String = ""
    ): BootPacket {
        val context = MemoryRetrievalService.RetrievalContext(prompt = prompt, task = task)

        val protectedKernel = graph.nodes.filter {
            it.type in setOf(NodeType.IDENTITY, NodeType.VALUE, NodeType.RELATIONSHIP) &&
                it.attributes["protection_level"] == "protected"
        }
        val kernelFallback = graph.nodes.filter { it.type == NodeType.IDENTITY || it.type == NodeType.VALUE }
        val state = graph.nodes.filter { it.type == NodeType.STATE }
        val relationships = graph.nodes.filter { it.type == NodeType.RELATIONSHIP }
        val warnings = graph.nodes.filter {
            it.attributes["memory_class"] == "warning" || it.attributes["semantic_subtype"] == "warning"
        }
        val memories = graph.nodes.filter { it.type == NodeType.MEMORY }
        val projects = graph.nodes.filter {
            it.attributes["semantic_subtype"] == "project" || it.label.contains("project", true)
        }
        val driftRules = graph.nodes.filter {
            it.type == NodeType.DRIFT_RULE || it.attributes["semantic_subtype"] == "drift_rule"
        }

        val packet = BootPacket(
            mode = mode,
            kernelSlice = targetedSlice(
                if (protectedKernel.isNotEmpty()) protectedKernel else kernelFallback,
                limitFor(mode).kernel,
                context
            ),
            stateSlice = targetedSlice(state, limitFor(mode).state, context),
            relationshipSlice = targetedSlice(relationships, limitFor(mode).relationship, context),
            warningSlice = targetedSlice(warnings, limitFor(mode).warning, context),
            memorySlice = MemoryRetrievalService.retrieve(memories, context, limitFor(mode).memory),
            projectSlice = targetedSlice(projects, limitFor(mode).project, context),
            driftRuleSlice = targetedSlice(driftRules, limitFor(mode).driftRule, context)
        )
        return packet
    }

    private data class SliceLimits(
        val kernel: Int,
        val state: Int,
        val relationship: Int,
        val warning: Int,
        val memory: Int,
        val project: Int,
        val driftRule: Int
    )

    private fun limitFor(mode: Mode): SliceLimits = when (mode) {
        Mode.FULL -> SliceLimits(12, 8, 8, 10, 12, 8, 8)
        Mode.EMERGENCY_WARNING -> SliceLimits(12, 8, 3, 10, 4, 0, 8)
        Mode.RELATIONSHIP -> SliceLimits(12, 8, 8, 4, 4, 0, 8)
        Mode.PROJECT -> SliceLimits(12, 8, 3, 3, 12, 8, 8)
        Mode.PUBLIC_PERSONA -> SliceLimits(12, 8, 8, 0, 6, 8, 3)
    }

    private fun targetedSlice(nodes: List<MindNode>, limit: Int, context: MemoryRetrievalService.RetrievalContext): List<MindNode> {
        if (limit <= 0) return emptyList()
        val query = "${context.prompt} ${context.task}".trim().lowercase()
        val queryTokens = query.split(Regex("[^a-z0-9_]+"))
            .filter { it.length > 2 }
            .toSet()

        return nodes
            .asSequence()
            .filterNot { it.attributes["protection_level"]?.lowercase() == "sealed" }
            .sortedByDescending { scoreNodeForQuery(it, queryTokens) }
            .take(limit)
            .toList()
    }

    private fun scoreNodeForQuery(node: MindNode, queryTokens: Set<String>): Float {
        val relevance = node.attributes["current_relevance"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.45f
        if (queryTokens.isEmpty()) return relevance

        val haystack = "${node.label} ${node.description} ${node.attributes["semantic_subtype"] ?: ""}".lowercase()
        val tokenHits = queryTokens.count { haystack.contains(it) }
        val lexical = (tokenHits.toFloat() / queryTokens.size.toFloat()).coerceIn(0f, 1f)
        return (lexical * 0.7f + relevance * 0.3f).coerceIn(0f, 1f)
    }
}
