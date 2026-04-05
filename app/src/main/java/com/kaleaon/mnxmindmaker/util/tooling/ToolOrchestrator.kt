package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import com.kaleaon.mnxmindmaker.util.provider.RoutingPolicy
import org.json.JSONArray
import org.json.JSONObject

class ToolOrchestrator(
    private val providerRouter: ProviderRouter,
    private val settingsChain: List<LlmSettings>,
    private val registry: ToolRegistry,
    private val policy: ToolPolicyEngine,
    private val requestApproval: suspend (ToolApprovalRequest) -> Boolean,
    private val routingPolicy: RoutingPolicy = RoutingPolicy(),
    private val maxToolRounds: Int = 6
) {

    suspend fun run(systemPrompt: String, userPrompt: String): String {
        val transcript = mutableListOf<JSONObject>()
        transcript += JSONObject().put("role", "user").put("content", userPrompt)

        val textParts = mutableListOf<String>()
        repeat(maxToolRounds) {
            val turn = providerRouter.chat(
                settingsChain = settingsChain,
                systemPrompt = systemPrompt,
                transcript = transcript,
                tools = registry.specs(),
                policy = routingPolicy
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
                        .put("name", result.toolName)
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
        return when (val decision = policy.evaluate(invocation)) {
            ToolPolicyDecision.Allow -> registry.invoke(invocation)
            ToolPolicyDecision.RequireApproval -> {
                val approved = requestApproval(
                    ToolApprovalRequest(
                        id = invocation.id,
                        toolName = invocation.name,
                        arguments = invocation.arguments.toString(2)
                    )
                )
                if (!approved) {
                    ToolResult(invocation.id, invocation.name, false, "User denied tool invocation")
                } else {
                    registry.invoke(invocation)
                }
            }

            is ToolPolicyDecision.Deny -> ToolResult(invocation.id, invocation.name, false, decision.reason)
        }
    }
}
