package com.kaleaon.mnxmindmaker.util.tooling

import org.json.JSONObject

data class AssistantTurn(
    val text: String,
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val raw: JSONObject? = null
)

data class ToolApprovalRequest(
    val id: String,
    val toolName: String,
    val arguments: String,
    val reason: String,
    val riskLevel: ToolRiskLevel,
    val requiresConfirmation: Boolean,
    val explicitActionType: String? = null
)
