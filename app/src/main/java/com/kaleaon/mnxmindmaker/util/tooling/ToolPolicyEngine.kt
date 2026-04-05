package com.kaleaon.mnxmindmaker.util.tooling

class ToolPolicyEngine(
    private val registry: ToolRegistry
) {

    fun evaluate(invocation: ToolInvocation): ToolPolicyDecision {
        val spec = registry.findSpec(invocation.name)
            ?: return ToolPolicyDecision.Deny("Tool is not registered: ${invocation.name}")
        return when (spec.mode) {
            ToolMode.READ_ONLY -> ToolPolicyDecision.Allow
            ToolMode.MUTATION -> ToolPolicyDecision.RequireApproval
        }
    }
}

sealed interface ToolPolicyDecision {
    data object Allow : ToolPolicyDecision
    data object RequireApproval : ToolPolicyDecision
    data class Deny(val reason: String) : ToolPolicyDecision
import com.kaleaon.mnxmindmaker.model.MindGraph

/**
 * Evaluates whether a tool call can run, requires user approval, or must be denied.
 *
 * Security defaults:
 * - deny unknown tools
 * - mandatory approval for mutating/high-risk tools
 * - protected node writes require user approval
 * - invariant node writes are denied
 */
class ToolPolicyEngine(
    private val specsByName: Map<String, ToolSpec>
) {

    fun evaluate(invocation: ToolInvocation, graph: MindGraph): PolicyDecision {
        val spec = specsByName[invocation.toolName]
            ?: return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Unknown tool '${invocation.toolName}' denied by default"
            )

        val targetedNodes = extractTargetNodeIds(invocation)
            .mapNotNull { id -> graph.nodes.firstOrNull { it.id == id } }

        val hasInvariantTarget = targetedNodes.any {
            it.attributes[PROTECTION_LEVEL_KEY].equals(PROTECTION_INVARIANT, ignoreCase = true)
        }
        if (hasInvariantTarget && spec.operationClass != ToolOperationClass.READ_ONLY) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Writes to invariant nodes are denied"
            )
        }

        val hasProtectedTarget = targetedNodes.any {
            it.attributes[PROTECTION_LEVEL_KEY].equals(PROTECTION_PROTECTED, ignoreCase = true)
        }

        return when (spec.operationClass) {
            ToolOperationClass.READ_ONLY -> PolicyDecision(
                type = PolicyDecisionType.ALLOW,
                reason = "Read-only tool"
            )
            ToolOperationClass.MUTATING -> {
                if (hasProtectedTarget) {
                    PolicyDecision(
                        type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                        reason = "Mutating protected nodes requires explicit user approval"
                    )
                } else {
                    PolicyDecision(
                        type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                        reason = "Mutating tool requires explicit user approval"
                    )
                }
            }
            ToolOperationClass.HIGH_RISK -> PolicyDecision(
                type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                reason = "High-risk tool requires explicit user approval"
            )
        }
    }

    companion object {
        const val PROTECTION_LEVEL_KEY = "protection_level"
        const val PROTECTION_PROTECTED = "protected"
        const val PROTECTION_INVARIANT = "invariant"

        /**
         * Heuristic extraction of node IDs from common mutation argument keys.
         */
        fun extractTargetNodeIds(invocation: ToolInvocation): List<String> {
            val args = invocation.argumentsJson
            val keys = listOf("node_id", "from_id", "to_id", "parent_id")
            val ids = mutableListOf<String>()
            keys.forEach { key ->
                val value = args.optString(key)
                if (value.isNotBlank()) ids += value
            }

            val nodeIds = args.optJSONArray("node_ids")
            if (nodeIds != null) {
                for (i in 0 until nodeIds.length()) {
                    val v = nodeIds.optString(i)
                    if (v.isNotBlank()) ids += v
                }
            }
            return ids
        }
    }
}
