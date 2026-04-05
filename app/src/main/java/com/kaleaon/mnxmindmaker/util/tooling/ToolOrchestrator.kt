package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import com.kaleaon.mnxmindmaker.util.provider.RoutingPolicy
import org.json.JSONArray
import org.json.JSONObject
import com.kaleaon.mnxmindmaker.util.observability.RequestTracer

class ToolOrchestrator(
    private val providerRouter: ProviderRouter,
    private val settingsChain: List<LlmSettings>,
    private val registry: ToolRegistry,
    private val policy: ToolPolicyEngine,
    private val requestApproval: suspend (ToolApprovalRequest) -> Boolean,
    private val maxToolRounds: Int = 6,
    private val tracer: RequestTracer? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val routingPolicy: RoutingPolicy = RoutingPolicy()
) {

    suspend fun run(systemPrompt: String, userPrompt: String): String {
        val transcript = mutableListOf<JSONObject>()
        transcript += JSONObject().put("role", "user").put("content", userPrompt)
        tracer?.recordPromptPipeline("user_prompt", userPrompt)

        val textParts = mutableListOf<String>()
        repeat(maxToolRounds) {
            val providerStart = nowMs()
            val turn = providerRouter.chat(
                settingsChain = settingsChain,
                systemPrompt = systemPrompt,
                transcript = transcript,
                tools = registry.specs(),
                policy = routingPolicy
            )
            val routedProvider = settingsChain.firstOrNull()?.provider?.name ?: "UNKNOWN"
            tracer?.recordProviderResponse(routedProvider, turn.text, nowMs() - providerStart)
            if (turn.text.isNotBlank()) {
                textParts += turn.text.trim()
            }

            if (turn.toolInvocations.isEmpty()) {
                return textParts.joinToString("\n\n").ifBlank { "Done." }
            }

            val toolResults = JSONArray()
            for (invocation in turn.toolInvocations) {
                val toolStart = nowMs()
                val result = executeInvocation(invocation)
                tracer?.recordToolCall(invocation, result, nowMs() - toolStart)
                toolResults.put(JSONObject()
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
