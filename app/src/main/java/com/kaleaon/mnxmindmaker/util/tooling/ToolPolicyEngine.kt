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
}
