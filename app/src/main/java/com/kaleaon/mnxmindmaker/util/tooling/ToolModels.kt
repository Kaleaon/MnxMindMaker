package com.kaleaon.mnxmindmaker.util.tooling

import org.json.JSONObject

enum class ToolMode {
    READ_ONLY,
    MUTATION
}

data class ToolSpec(
    val name: String,
    val description: String,
    val mode: ToolMode,
    val inputSchema: JSONObject = JSONObject()
)

data class ToolInvocation(
    val id: String,
    val name: String,
    val arguments: JSONObject = JSONObject(),
    val raw: JSONObject? = null
)

data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val success: Boolean,
    val outputText: String,
    val outputJson: JSONObject = JSONObject()
)

data class AssistantTurn(
    val text: String,
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val raw: JSONObject? = null
)

data class ToolApprovalRequest(
    val id: String,
    val toolName: String,
    val arguments: String,
    val reason: String = "Model requested graph mutation"
)
