package com.kaleaon.mnxmindmaker.persona.validation

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import kotlin.math.max
import kotlin.math.min

/**
 * Validates whether a [MindGraph] has enough core persona structure to be considered
 * ready for downstream assistant/runtime usage.
 */
class PersonaReadinessValidator(
    private val policy: PersonaReadinessPolicy = PersonaReadinessPolicy()
) {
    fun validate(graph: MindGraph): PersonaReadinessResult {
        val blockingErrors = mutableListOf<PersonaValidationIssue>()
        val warnings = mutableListOf<PersonaValidationIssue>()
        val suggestedFixes = mutableListOf<PersonaSuggestedFix>()

        val identityNodes = graph.nodes.ofType(NodeType.IDENTITY)
        val valueNodes = graph.nodes.ofType(NodeType.VALUE)
        val beliefNodes = graph.nodes.ofType(NodeType.BELIEF)
        val memoryNodes = graph.nodes.ofType(NodeType.MEMORY)

        if (!hasMeaningfulIdentity(identityNodes)) {
            blockingErrors += PersonaValidationIssue(
                code = PersonaIssueCode.IDENTITY_MISSING,
                message = "Persona identity is missing or empty."
            )
            suggestedFixes += PersonaSuggestedFix(
                issueCode = PersonaIssueCode.IDENTITY_MISSING,
                action = "Add an IDENTITY node with a non-empty label and/or description."
            )
        }

        if (valueNodes.isEmpty()) {
            blockingErrors += PersonaValidationIssue(
                code = PersonaIssueCode.VALUES_MISSING,
                message = "Persona values are missing."
            )
            suggestedFixes += PersonaSuggestedFix(
                issueCode = PersonaIssueCode.VALUES_MISSING,
                action = "Add at least one VALUE node describing core principles."
            )
        }

        if (beliefNodes.isEmpty()) {
            blockingErrors += PersonaValidationIssue(
                code = PersonaIssueCode.BELIEFS_MISSING,
                message = "Persona beliefs are missing."
            )
            suggestedFixes += PersonaSuggestedFix(
                issueCode = PersonaIssueCode.BELIEFS_MISSING,
                action = "Add at least one BELIEF node capturing worldview assumptions."
            )
        }

        if (memoryNodes.size < policy.minMemoryNodes) {
            blockingErrors += PersonaValidationIssue(
                code = PersonaIssueCode.MEMORY_COVERAGE_LOW,
                message = "Memory coverage is insufficient: ${memoryNodes.size}/${policy.minMemoryNodes} required memory nodes."
            )
            suggestedFixes += PersonaSuggestedFix(
                issueCode = PersonaIssueCode.MEMORY_COVERAGE_LOW,
                action = "Add MEMORY nodes for episodic, procedural, or long-term continuity context."
            )
        }

        val memoryCoverage = if (graph.nodes.isEmpty()) 0f else memoryNodes.size.toFloat() / graph.nodes.size.toFloat()
        if (memoryNodes.isNotEmpty() && memoryCoverage < policy.minMemoryNodeShare) {
            warnings += PersonaValidationIssue(
                code = PersonaIssueCode.MEMORY_COVERAGE_LOW,
                message = "Memory coverage ratio is low (${formatPercent(memoryCoverage)} < ${formatPercent(policy.minMemoryNodeShare)})."
            )
            suggestedFixes += PersonaSuggestedFix(
                issueCode = PersonaIssueCode.MEMORY_COVERAGE_LOW,
                action = "Increase MEMORY representation across the graph to improve contextual recall breadth."
            )
        }

        runDimensionalQualityChecks(graph, warnings, suggestedFixes)

        return PersonaReadinessResult(
            passed = blockingErrors.isEmpty(),
            blockingErrors = blockingErrors.distinctBy { it.code to it.message },
            warnings = warnings.distinctBy { it.code to it.message },
            suggestedFixes = suggestedFixes.distinctBy { it.issueCode to it.action }
        )
    }

    /**
     * ViewModel-friendly API returning a compact UI projection.
     */
    fun validateForUi(graph: MindGraph): PersonaReadinessUiState {
        val result = validate(graph)
        return PersonaReadinessUiState(
            passed = result.passed,
            status = if (result.passed) "READY" else "BLOCKED",
            blockingErrors = result.blockingErrors.map { it.message },
            warnings = result.warnings.map { it.message },
            suggestedFixes = result.suggestedFixes.map { it.action }
        )
    }

    private fun runDimensionalQualityChecks(
        graph: MindGraph,
        warnings: MutableList<PersonaValidationIssue>,
        suggestedFixes: MutableList<PersonaSuggestedFix>
    ) {
        if (policy.dimensionalQualityThresholds.isEmpty()) return

        policy.dimensionalQualityThresholds.forEach { (nodeType, threshold) ->
            val nodes = graph.nodes.ofType(nodeType)
            if (nodes.isEmpty()) return@forEach

            val qualifyingNodes = nodes.filter { it.dimensions.size >= policy.minDimensionsPerQualifiedNode }
            if (qualifyingNodes.isEmpty()) {
                warnings += PersonaValidationIssue(
                    code = PersonaIssueCode.DIMENSIONAL_QUALITY_LOW,
                    message = "${nodeType.name} nodes are missing enough dimensional attributes for quality scoring."
                )
                suggestedFixes += PersonaSuggestedFix(
                    issueCode = PersonaIssueCode.DIMENSIONAL_QUALITY_LOW,
                    action = "Add at least ${policy.minDimensionsPerQualifiedNode} dimensions to ${nodeType.name} nodes."
                )
                return@forEach
            }

            val averageQuality = qualifyingNodes.map(::computeDimensionalQuality).average().toFloat()
            if (averageQuality < threshold) {
                warnings += PersonaValidationIssue(
                    code = PersonaIssueCode.DIMENSIONAL_QUALITY_LOW,
                    message = "${nodeType.name} dimensional quality is low (${formatPercent(averageQuality)} < ${formatPercent(threshold)})."
                )
                suggestedFixes += PersonaSuggestedFix(
                    issueCode = PersonaIssueCode.DIMENSIONAL_QUALITY_LOW,
                    action = "Adjust ${nodeType.name} dimensions (confidence/coherence/etc.) toward more explicit, higher-quality values."
                )
            }
        }
    }

    private fun computeDimensionalQuality(node: MindNode): Float {
        if (node.dimensions.isEmpty()) return 0f
        return node.dimensions.values
            .map(::normalizeDimensionValue)
            .average()
            .toFloat()
    }

    private fun normalizeDimensionValue(value: Float): Float {
        return if (value < 0f) {
            ((value + 1f) / 2f).coerceIn(0f, 1f)
        } else {
            max(0f, min(1f, value))
        }
    }

    private fun hasMeaningfulIdentity(identityNodes: List<MindNode>): Boolean {
        if (identityNodes.isEmpty()) return false
        return identityNodes.any { it.label.isNotBlank() || it.description.isNotBlank() }
    }

    private fun formatPercent(value: Float): String = "${(value * 100).toInt()}%"

    private fun List<MindNode>.ofType(type: NodeType): List<MindNode> = filter { it.type == type }
}

/** Validation policy controls hard requirements and optional quality warnings. */
data class PersonaReadinessPolicy(
    val minMemoryNodes: Int = 1,
    val minMemoryNodeShare: Float = 0.10f,
    val dimensionalQualityThresholds: Map<NodeType, Float> = emptyMap(),
    val minDimensionsPerQualifiedNode: Int = 1
)

data class PersonaReadinessResult(
    val passed: Boolean,
    val blockingErrors: List<PersonaValidationIssue>,
    val warnings: List<PersonaValidationIssue>,
    val suggestedFixes: List<PersonaSuggestedFix>
)

data class PersonaValidationIssue(
    val code: PersonaIssueCode,
    val message: String
)

data class PersonaSuggestedFix(
    val issueCode: PersonaIssueCode,
    val action: String
)

enum class PersonaIssueCode {
    IDENTITY_MISSING,
    VALUES_MISSING,
    BELIEFS_MISSING,
    MEMORY_COVERAGE_LOW,
    DIMENSIONAL_QUALITY_LOW
}

data class PersonaReadinessUiState(
    val passed: Boolean,
    val status: String,
    val blockingErrors: List<String>,
    val warnings: List<String>,
    val suggestedFixes: List<String>
)
