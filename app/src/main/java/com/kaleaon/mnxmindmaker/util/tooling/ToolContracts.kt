package com.kaleaon.mnxmindmaker.util.tooling

import org.json.JSONObject

/** Risk class for tool execution authorization. */
enum class ToolOperationClass {
    READ_ONLY,
    MUTATING,
    HIGH_RISK
}

enum class PolicyDecisionType {
    ALLOW,
    REQUIRE_USER_APPROVAL,
    DENY
}

data class ToolSpec(
    val name: String,
    val description: String,
    val operationClass: ToolOperationClass
)

data class ToolInvocation(
    val id: String,
    val toolName: String,
    val argumentsJson: JSONObject = JSONObject()
)

data class PolicyDecision(
    val type: PolicyDecisionType,
    val reason: String
)

data class ToolResult(
    val toolUseId: String,
    val isError: Boolean,
    val contentJson: JSONObject,
    val rolledBack: Boolean = false
)

data class MutationSequenceResult(
    val graph: com.kaleaon.mnxmindmaker.model.MindGraph,
    val toolResults: List<ToolResult>,
    val rolledBack: Boolean
)

data class ToolExecutionOutcome(
    val contentJson: JSONObject,
    val mutatedGraph: Boolean
)

fun interface ToolHandler {
    fun execute(invocation: ToolInvocation, graph: com.kaleaon.mnxmindmaker.model.MindGraph): ToolExecutionOutcome
}
