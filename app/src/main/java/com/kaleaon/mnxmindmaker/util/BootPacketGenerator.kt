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

    fun generate(graph: MindGraph, mode: Mode = Mode.FULL): BootPacket {
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
            kernelSlice = (if (protectedKernel.isNotEmpty()) protectedKernel else kernelFallback).take(12),
            stateSlice = state.take(8),
            relationshipSlice = relationships.take(8),
            warningSlice = warnings.take(10),
            memorySlice = memories.sortedByDescending { it.attributes["current_relevance"]?.toFloatOrNull() ?: 0f }.take(12),
            projectSlice = projects.take(8),
            driftRuleSlice = driftRules.take(8),
            continuityAudit = continuityAudit
        )
        return packetForMode(packet, mode)
    }

    private fun packetForMode(packet: BootPacket, mode: Mode): BootPacket = when (mode) {
        Mode.FULL -> packet
        Mode.EMERGENCY_WARNING -> packet.copy(
            memorySlice = packet.memorySlice.take(4),
            relationshipSlice = packet.relationshipSlice.take(3),
            projectSlice = emptyList()
        )
        Mode.RELATIONSHIP -> packet.copy(
            warningSlice = packet.warningSlice.take(4),
            memorySlice = packet.memorySlice.take(4),
            projectSlice = emptyList()
        )
        Mode.PROJECT -> packet.copy(
            relationshipSlice = packet.relationshipSlice.take(3),
            warningSlice = packet.warningSlice.take(3)
        )
        Mode.PUBLIC_PERSONA -> packet.copy(
            warningSlice = emptyList(),
            memorySlice = packet.memorySlice.filterNot { it.attributes["memory_class"] == "wound" }.take(6),
            driftRuleSlice = packet.driftRuleSlice.take(3)
        )
    }
}
