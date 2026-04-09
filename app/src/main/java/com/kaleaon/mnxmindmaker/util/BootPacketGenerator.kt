package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.LlmCapabilityFlags
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.json.JSONArray
import org.json.JSONObject

object BootPacketGenerator {

    data class WakeUpTokenBudget(
        val l0Tokens: Int = 320,
        val l1Tokens: Int = 480
    ) {
        init {
            require(l0Tokens >= 0) { "l0Tokens must be >= 0" }
            require(l1Tokens >= 0) { "l1Tokens must be >= 0" }
        }
    }

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
        val driftRuleSlice: List<MindNode>,
        val continuityAudit: ContinuityAuditResult
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
                .put("continuity_audit", auditToJson(continuityAudit))
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
            appendAuditSection(continuityAudit)
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

        private fun auditToJson(audit: ContinuityAuditResult): JSONObject {
            val findings = JSONArray().apply {
                audit.findings.forEach { finding ->
                    put(
                        JSONObject()
                            .put("id", finding.id)
                            .put("category", finding.category)
                            .put("title", finding.title)
                            .put("description", finding.description)
                            .put("severity", finding.severity.name.lowercase())
                            .put("severity_score", finding.severityScore)
                            .put("confidence_score", finding.confidenceScore)
                            .put("node_ids", JSONArray(finding.nodeIds))
                            .put("rule_ids", JSONArray(finding.ruleIds))
                            .put("suggested_action", finding.suggestedAction)
                            .put("accepted", finding.accepted)
                    )
                }
            }
            val summary = JSONObject()
                .put("total_findings", audit.summary.totalFindings)
                .put("critical_count", audit.summary.criticalCount)
                .put("high_count", audit.summary.highCount)
                .put("medium_count", audit.summary.mediumCount)
                .put("low_count", audit.summary.lowCount)
                .put("average_confidence", audit.summary.averageConfidence)
            return JSONObject()
                .put("summary", summary)
                .put("findings", findings)
        }

        private fun StringBuilder.appendAuditSection(audit: ContinuityAuditResult) {
            appendLine()
            appendLine("## Continuity Audit")
            appendLine("- Total findings: ${audit.summary.totalFindings}")
            appendLine("- Critical: ${audit.summary.criticalCount}, High: ${audit.summary.highCount}, Medium: ${audit.summary.mediumCount}, Low: ${audit.summary.lowCount}")
            appendLine("- Avg confidence: ${"%.2f".format(audit.summary.averageConfidence)}")
            if (audit.findings.isEmpty()) {
                appendLine("- _No continuity warnings detected._")
                return
            }
            audit.findings.forEach { finding ->
                appendLine("- **${finding.title}** [${finding.severity}] (sev=${"%.2f".format(finding.severityScore)}, conf=${"%.2f".format(finding.confidenceScore)})")
                appendLine("  - Rule IDs: ${finding.ruleIds.joinToString().ifBlank { "none" }}")
                appendLine("  - Node IDs: ${finding.nodeIds.joinToString().ifBlank { "none" }}")
                appendLine("  - Action: ${finding.suggestedAction}")
            }
        }
    }

    fun generate(
        graph: MindGraph,
        mode: Mode = Mode.FULL,
        capabilities: LlmCapabilityFlags? = null,
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
        val continuityAudit = run_continuity_audit(graph)

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
            memorySlice = MemoryRetrievalService.retrieveForPromptInjection(memories, context, limitFor(mode).memory),
            projectSlice = targetedSlice(projects, limitFor(mode).project, context),
            driftRuleSlice = targetedSlice(driftRules, limitFor(mode).driftRule, context),
            continuityAudit = continuityAudit
        )
        val modePacket = packetForMode(packet, mode)
        return adaptForCapabilities(modePacket, capabilities)
    }

    private fun adaptForCapabilities(packet: BootPacket, capabilities: LlmCapabilityFlags?): BootPacket {
        if (capabilities == null) return packet

        val contextScale = when {
            capabilities.contextWindowTokens <= 4096 -> 0.4f
            capabilities.contextWindowTokens <= 8192 -> 0.6f
            capabilities.contextWindowTokens <= 16384 -> 0.8f
            else -> 1.0f
        }

        fun <T> List<T>.scaled(): List<T> {
            if (isEmpty()) return emptyList()
            val target = (size * contextScale).toInt().coerceAtLeast(1)
            return take(target)
        }

        return packet.copy(
            relationshipSlice = packet.relationshipSlice.scaled(),
            warningSlice = packet.warningSlice.scaled(),
            memorySlice = packet.memorySlice.scaled(),
            projectSlice = if (capabilities.supportsToolPlanning) packet.projectSlice.scaled() else emptyList(),
            driftRuleSlice = if (capabilities.supportsPacketGeneration) packet.driftRuleSlice.scaled() else packet.driftRuleSlice.take(2)
        )
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

    private fun targetedSlice(
        nodes: List<MindNode>,
        limit: Int,
        context: MemoryRetrievalService.RetrievalContext
    ): List<MindNode> {
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

    private fun packetForMode(packet: BootPacket, mode: Mode): BootPacket = when (mode) {
        Mode.FULL -> packet
        Mode.EMERGENCY_WARNING -> packet.copy(projectSlice = emptyList())
        Mode.RELATIONSHIP -> packet.copy(projectSlice = emptyList())
        Mode.PROJECT -> packet
        Mode.PUBLIC_PERSONA -> packet.copy(warningSlice = emptyList())
    }

    fun generateWakeUpContext(
        graph: MindGraph,
        tokenBudget: WakeUpTokenBudget = WakeUpTokenBudget()
    ): String {
        val l0Items = buildL0Items(graph.nodes)
        val l1Items = buildL1Items(graph.nodes)

        val l0Selected = fitItemsToBudget(l0Items, tokenBudget.l0Tokens)
        val l1Selected = fitItemsToBudget(l1Items, tokenBudget.l1Tokens)

        return buildString {
            appendLine("L0: identity/core values/core constraints")
            if (l0Selected.isEmpty()) {
                appendLine("- _none_")
            } else {
                l0Selected.forEach { appendLine(it.renderedLine) }
            }
            appendLine()
            appendLine("L1: stable preferences/projects/long-horizon context")
            if (l1Selected.isEmpty()) {
                appendLine("- _none_")
            } else {
                l1Selected.forEach { appendLine(it.renderedLine) }
            }
        }.trimEnd()
    }

    private data class WakeItem(
        val priorityBucket: Int,
        val score: Float,
        val sortLabel: String,
        val id: String,
        val renderedLine: String
    )

    private fun buildL0Items(nodes: List<MindNode>): List<WakeItem> =
        nodes.asSequence()
            .filterNot { it.attributes["protection_level"]?.lowercase() == "sealed" }
            .mapNotNull { node ->
                val bucket = when {
                    node.type == NodeType.IDENTITY -> 0
                    node.type == NodeType.VALUE || node.attributes["semantic_subtype"]?.lowercase() in setOf("value", "core_value") -> 1
                    node.type == NodeType.DRIFT_RULE -> 2
                    node.attributes["semantic_subtype"]?.lowercase() in setOf("constraint", "core_constraint", "drift_rule") -> 2
                    node.attributes["memory_class"]?.lowercase() == "warning" -> 2
                    else -> null
                } ?: return@mapNotNull null

                WakeItem(
                    priorityBucket = bucket,
                    score = wakeScore(node),
                    sortLabel = node.label.lowercase(),
                    id = node.id,
                    renderedLine = formatWakeLine(node)
                )
            }
            .sortedWith(
                compareBy<WakeItem> { it.priorityBucket }
                    .thenByDescending { it.score }
                    .thenBy { it.sortLabel }
                    .thenBy { it.id }
            )
            .toList()

    private fun buildL1Items(nodes: List<MindNode>): List<WakeItem> =
        nodes.asSequence()
            .filterNot { it.attributes["protection_level"]?.lowercase() == "sealed" }
            .mapNotNull { node ->
                val subtype = node.attributes["semantic_subtype"]?.lowercase()
                val bucket = when {
                    subtype in setOf("preference", "stable_preference") || node.type in setOf(NodeType.PERSONALITY, NodeType.BELIEF) -> 0
                    subtype == "project" || node.label.contains("project", ignoreCase = true) -> 1
                    subtype in setOf("long_horizon", "long_term", "evergreen") ||
                        node.attributes["horizon"]?.lowercase() in setOf("long", "long_term") -> 2
                    else -> null
                } ?: return@mapNotNull null

                WakeItem(
                    priorityBucket = bucket,
                    score = wakeScore(node),
                    sortLabel = node.label.lowercase(),
                    id = node.id,
                    renderedLine = formatWakeLine(node)
                )
            }
            .sortedWith(
                compareBy<WakeItem> { it.priorityBucket }
                    .thenByDescending { it.score }
                    .thenBy { it.sortLabel }
                    .thenBy { it.id }
            )
            .toList()

    private fun wakeScore(node: MindNode): Float {
        val relevance = node.attributes["current_relevance"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.4f
        val confidence = node.attributes["confidence"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
        val protectionBonus = if (node.attributes["protection_level"]?.lowercase() == "protected") 0.15f else 0f
        return (relevance * 0.6f + confidence * 0.4f + protectionBonus).coerceIn(0f, 1f)
    }

    private fun formatWakeLine(node: MindNode): String {
        val base = StringBuilder()
            .append("- [")
            .append(node.type.name.lowercase())
            .append("] ")
            .append(node.label.trim())

        if (node.description.isNotBlank()) {
            base.append(" :: ").append(node.description.trim())
        }

        val stableAttributeKeys = listOf(
            "semantic_subtype",
            "horizon",
            "current_relevance",
            "confidence",
            "protection_level"
        )
        val attrs = stableAttributeKeys
            .mapNotNull { key ->
                node.attributes[key]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { value -> "$key=$value" }
            }
        if (attrs.isNotEmpty()) {
            base.append(" {").append(attrs.joinToString(", ")).append("}")
        }
        return base.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun fitItemsToBudget(items: List<WakeItem>, budgetTokens: Int): List<WakeItem> {
        if (budgetTokens <= 0 || items.isEmpty()) return emptyList()

        val selected = mutableListOf<WakeItem>()
        var used = 0
        items.forEach { item ->
            val tokens = estimateTokens(item.renderedLine)
            if (used + tokens <= budgetTokens) {
                selected += item
                used += tokens
            } else {
                val remaining = budgetTokens - used
                if (remaining > 0) {
                    val truncatedLine = truncateToTokenBudget(item.renderedLine, remaining)
                    if (truncatedLine.isNotBlank()) {
                        selected += item.copy(renderedLine = truncatedLine)
                    }
                }
                return selected
            }
        }
        return selected
    }

    private fun estimateTokens(text: String): Int =
        ((text.trim().length + 3) / 4).coerceAtLeast(1)

    private fun truncateToTokenBudget(text: String, budgetTokens: Int): String {
        if (budgetTokens <= 0) return ""
        val maxChars = (budgetTokens * 4).coerceAtLeast(1)
        if (text.length <= maxChars) return text
        val clipped = text.take(maxChars).trimEnd()
        return if (clipped.length <= 1) "…" else "$clipped…"
    }
}
