package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph

/**
 * Evaluates whether a tool call can run, requires user approval, or must be denied.
 */
class ToolPolicyEngine(
    private val specsByName: Map<String, ToolSpec>
) {

    fun evaluate(invocation: ToolInvocation): PolicyDecision = evaluate(invocation, MindGraph())

    fun evaluate(invocation: ToolInvocation, graph: MindGraph): PolicyDecision {
        val spec = specsByName[invocation.toolName]
            ?: return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Unknown tool '${invocation.toolName}' denied by default",
                riskLevel = ToolRiskLevel.HIGH
            )

        val explicitAction = inferExplicitActionType(invocation.toolName)

        val targetedNodes = extractTargetNodeIds(invocation)
            .mapNotNull { id -> graph.nodes.firstOrNull { it.id == id } }

        val hasInvariantTarget = targetedNodes.any {
            it.attributes[PROTECTION_LEVEL_KEY].equals(PROTECTION_INVARIANT, ignoreCase = true)
        }
        if (hasInvariantTarget && spec.operationClass != ToolOperationClass.READ_ONLY) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Writes to invariant nodes are denied",
                riskLevel = ToolRiskLevel.HIGH,
                explicitActionType = explicitAction
            )
        }

        val hasProtectedTarget = targetedNodes.any {
            it.attributes[PROTECTION_LEVEL_KEY].equals(PROTECTION_PROTECTED, ignoreCase = true)
        }

        val requiresApprovalByRisk = spec.requiresConfirmation || explicitAction != null

        return when {
            spec.operationClass == ToolOperationClass.READ_ONLY && !requiresApprovalByRisk -> PolicyDecision(
                type = PolicyDecisionType.ALLOW,
                reason = "Read-only tool",
                riskLevel = spec.riskLevel
            )

            hasProtectedTarget && spec.operationClass != ToolOperationClass.READ_ONLY -> PolicyDecision(
                type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                reason = "Mutating protected nodes requires explicit user approval",
                riskLevel = spec.riskLevel,
                requiresConfirmation = true,
                explicitActionType = explicitAction
            )

            else -> PolicyDecision(
                type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                reason = when {
                    explicitAction != null -> "High-risk '${explicitAction}' action requires explicit user approval"
                    spec.operationClass == ToolOperationClass.HIGH_RISK -> "High-risk tool requires explicit user approval"
                    else -> "Mutating tool requires explicit user approval"
                },
                riskLevel = spec.riskLevel,
                requiresConfirmation = true,
                explicitActionType = explicitAction
            )
        }
    }

    private fun inferExplicitActionType(toolName: String): String? {
        val name = toolName.lowercase()
        return when {
            "delete" in name || "remove" in name -> "delete"
            "send" in name || "post" in name -> "send"
            "execute" in name || "command" in name || "terminal" in name -> "execute"
            else -> null
        }
    }

    companion object {
        const val PROTECTION_LEVEL_KEY = "protection_level"
        const val PROTECTION_PROTECTED = "protected"
        const val PROTECTION_INVARIANT = "invariant"

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
