package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType

data class ContinuityAuditResult(
    val findings: List<ContinuityFinding>,
    val summary: ContinuityAuditSummary
)

data class ContinuityAuditSummary(
    val totalFindings: Int,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val averageConfidence: Float
)

data class ContinuityFinding(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val severity: Severity,
    val severityScore: Float,
    val confidenceScore: Float,
    val nodeIds: List<String>,
    val ruleIds: List<String>,
    val suggestedAction: String,
    val accepted: Boolean = false
)

enum class Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

fun run_continuity_audit(
    graph: MindGraph,
    acceptedFindingIds: Set<String> = emptySet()
): ContinuityAuditResult {
    val findings = mutableListOf<ContinuityFinding>()
    findings += evaluateKernelCoverage(graph)
    findings += detectContradictions(graph)
    findings += detectMissingRepairMemories(graph)
    findings += detectHighRiskDriftSignatures(graph)

    val sorted = findings
        .map { finding ->
            if (acceptedFindingIds.contains(finding.id)) finding.copy(accepted = true) else finding
        }
        .sortedWith(
            compareByDescending<ContinuityFinding> { it.severityScore }
                .thenByDescending { it.confidenceScore }
                .thenBy { it.id }
        )

    return ContinuityAuditResult(
        findings = sorted,
        summary = ContinuityAuditSummary(
            totalFindings = sorted.size,
            criticalCount = sorted.count { it.severity == Severity.CRITICAL },
            highCount = sorted.count { it.severity == Severity.HIGH },
            mediumCount = sorted.count { it.severity == Severity.MEDIUM },
            lowCount = sorted.count { it.severity == Severity.LOW },
            averageConfidence = if (sorted.isEmpty()) 1f else sorted.map { it.confidenceScore }.average().toFloat()
        )
    )
}

private fun evaluateKernelCoverage(graph: MindGraph): List<ContinuityFinding> {
    val protectedKernel = graph.nodes.filter {
        it.type in setOf(NodeType.IDENTITY, NodeType.VALUE, NodeType.RELATIONSHIP) &&
            it.attributes["protection_level"] == "protected"
    }
    val kernelFallback = graph.nodes.filter { it.type == NodeType.IDENTITY || it.type == NodeType.VALUE }
    val kernel = if (protectedKernel.isNotEmpty()) protectedKernel else kernelFallback
    if (kernel.size >= 3) return emptyList()

    val missingCount = 3 - kernel.size
    val severityScore = (0.45f + 0.2f * missingCount).coerceAtMost(0.95f)
    return listOf(
        ContinuityFinding(
            id = "kernel_coverage_missing_$missingCount",
            category = "kernel_coverage",
            title = "Kernel coverage below continuity baseline",
            description = "Only ${kernel.size} kernel nodes detected; baseline is 3 protected identity/value anchors.",
            severity = scoreToSeverity(severityScore),
            severityScore = severityScore,
            confidenceScore = 0.9f,
            nodeIds = kernel.map { it.id },
            ruleIds = emptyList(),
            suggestedAction = "Promote or add protected IDENTITY/VALUE nodes with explicit protection_level=protected."
        )
    )
}

private fun detectContradictions(graph: MindGraph): List<ContinuityFinding> {
    val candidates = graph.nodes.filter {
        it.type in setOf(NodeType.IDENTITY, NodeType.BELIEF, NodeType.VALUE, NodeType.MEMORY)
    }
    val grouped = candidates.groupBy { it.label.trim().lowercase() }
    val findings = mutableListOf<ContinuityFinding>()
    grouped.forEach { (key, nodes) ->
        if (key.isBlank() || nodes.size < 2) return@forEach
        val withNegation = nodes.filter { hasNegationSignal(it.description) }
        val withoutNegation = nodes.filterNot { hasNegationSignal(it.description) }
        if (withNegation.isNotEmpty() && withoutNegation.isNotEmpty()) {
            val nodeIds = (withNegation + withoutNegation).map { it.id }.distinct()
            findings += ContinuityFinding(
                id = "contradiction_label_${key.hashCode()}",
                category = "contradiction_detection",
                title = "Potential contradiction on \"$key\"",
                description = "Nodes with same label contain both negated and non-negated claims.",
                severity = Severity.HIGH,
                severityScore = 0.78f,
                confidenceScore = 0.62f,
                nodeIds = nodeIds,
                ruleIds = emptyList(),
                suggestedAction = "Attach a repair memory that clarifies contextual boundaries for \"$key\"."
            )
        }
    }
    return findings
}

private fun detectMissingRepairMemories(graph: MindGraph): List<ContinuityFinding> {
    val ruleNodes = graph.nodes.filter {
        it.type == NodeType.DRIFT_RULE || it.attributes["semantic_subtype"] == "drift_rule"
    }
    if (ruleNodes.isEmpty()) return emptyList()

    val memoryNodes = graph.nodes.filter {
        it.type == NodeType.MEMORY &&
            (it.attributes["memory_class"] == "repair" || it.attributes["semantic_subtype"] == "repair")
    }
    val adjacentMap = graph.edges.fold(mutableMapOf<String, MutableSet<String>>()) { acc, edge ->
        acc.getOrPut(edge.fromNodeId) { mutableSetOf() }.add(edge.toNodeId)
        acc.getOrPut(edge.toNodeId) { mutableSetOf() }.add(edge.fromNodeId)
        acc
    }

    return ruleNodes.mapNotNull { rule ->
        val hasLinkedRepair = memoryNodes.any { memory ->
            memory.attributes["linked_rule_id"] == rule.id || adjacentMap[rule.id]?.contains(memory.id) == true
        }
        if (hasLinkedRepair) {
            null
        } else {
            ContinuityFinding(
                id = "missing_repair_${rule.id}",
                category = "missing_repair_memory",
                title = "Drift rule missing repair memory",
                description = "Rule \"${rule.label}\" has no linked MEMORY node classified as repair.",
                severity = Severity.MEDIUM,
                severityScore = 0.64f,
                confidenceScore = 0.95f,
                nodeIds = listOf(rule.id),
                ruleIds = listOf(rule.id),
                suggestedAction = "Create a repair MEMORY and link it to this rule."
            )
        }
    }
}

private fun detectHighRiskDriftSignatures(graph: MindGraph): List<ContinuityFinding> {
    val riskKeywords = listOf(
        "panic", "collapse", "identity loss", "self erase", "erasure", "hallucination", "dissociation", "manic"
    )
    return graph.nodes
        .filter { it.type == NodeType.DRIFT_RULE || it.attributes.containsKey("drift_signature") }
        .mapNotNull { node ->
            val searchable = buildString {
                append(node.label.lowercase())
                append(' ')
                append(node.description.lowercase())
                append(' ')
                append(node.attributes["drift_signature"]?.lowercase() ?: "")
                append(' ')
                append(node.attributes["drift_type"]?.lowercase() ?: "")
            }
            val hit = riskKeywords.firstOrNull { searchable.contains(it) } ?: return@mapNotNull null
            ContinuityFinding(
                id = "high_risk_signature_${node.id}",
                category = "high_risk_drift_signature",
                title = "High-risk drift signature detected",
                description = "Drift signature contains \"$hit\", which is associated with destabilizing failure modes.",
                severity = Severity.CRITICAL,
                severityScore = 0.9f,
                confidenceScore = 0.83f,
                nodeIds = listOf(node.id),
                ruleIds = listOf(node.id),
                suggestedAction = "Add emergency warning memory and a containment repair rule linked to this drift node."
            )
        }
}

private fun hasNegationSignal(description: String): Boolean {
    val d = description.lowercase()
    return d.contains(" not ") || d.startsWith("not ") || d.contains(" never ") || d.contains(" no ")
}

private fun scoreToSeverity(score: Float): Severity = when {
    score >= 0.85f -> Severity.CRITICAL
    score >= 0.70f -> Severity.HIGH
    score >= 0.45f -> Severity.MEDIUM
    else -> Severity.LOW
}

