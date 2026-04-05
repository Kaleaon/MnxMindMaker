package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.LlmApiClient
import org.json.JSONArray
import org.json.JSONObject

class ToolOrchestrator(
    private val llmApiClient: LlmApiClient,
    private val settings: LlmSettings,
    private val registry: ToolRegistry,
    private val policy: ToolPolicyEngine,
    private val requestApproval: suspend (ToolApprovalRequest) -> Boolean,
    private val maxToolRounds: Int = 6
) {

    suspend fun run(systemPrompt: String, userPrompt: String): String {
        val transcript = mutableListOf<JSONObject>()
        transcript += JSONObject().put("role", "user").put("content", userPrompt)

        val textParts = mutableListOf<String>()
        repeat(maxToolRounds) {
            val turn = llmApiClient.completeAssistantTurn(
                settings = settings,
                systemPrompt = systemPrompt,
                transcript = transcript,
                tools = registry.specs()
            )
            if (turn.text.isNotBlank()) {
                textParts += turn.text.trim()
            }

            if (turn.toolInvocations.isEmpty()) {
                return textParts.joinToString("\n\n").ifBlank { "Done." }
            }

            val toolResults = JSONArray()
            for (invocation in turn.toolInvocations) {
                val result = executeInvocation(invocation)
                toolResults.put(
                    JSONObject()
                        .put("tool_call_id", result.toolCallId)
                        .put("name", invocation.toolName)
                        .put("success", result.success)
                        .put("output_text", result.outputText)
                        .put("output_json", result.outputJson)
                )
            }
            transcript += JSONObject()
                .put("role", "tool")
                .put("content", toolResults)
        }
        return textParts.joinToString("\n\n").ifBlank { "Tool loop reached limit with no textual response." }
    }

    private suspend fun executeInvocation(invocation: ToolInvocation): ToolResult {
        val decision = policy.evaluate(invocation)
        return when (decision.type) {
            PolicyDecisionType.ALLOW -> registry.invoke(invocation)
            PolicyDecisionType.REQUIRE_USER_APPROVAL -> {
                val approved = requestApproval(
                    ToolApprovalRequest(
                        id = invocation.id,
                        toolName = invocation.toolName,
                        arguments = invocation.argumentsJson.toString(2),
                        reason = decision.reason,
                        riskLevel = decision.riskLevel,
                        requiresConfirmation = decision.requiresConfirmation,
                        explicitActionType = decision.explicitActionType
                    )
                )
                if (!approved) {
                    ToolResult(
                        toolUseId = invocation.id,
                        isError = true,
                        contentJson = JSONObject()
                            .put("tool_name", invocation.toolName)
                            .put("error", "approval_rejected")
                            .put("message", "User denied tool invocation")
                    )
                } else {
                    registry.invoke(invocation)
                }
            }
            PolicyDecisionType.DENY -> ToolResult(
                toolUseId = invocation.id,
                isError = true,
                contentJson = JSONObject()
                    .put("tool_name", invocation.toolName)
                    .put("error", "policy_denied")
                    .put("message", decision.reason)
            )
        }
    }
}
