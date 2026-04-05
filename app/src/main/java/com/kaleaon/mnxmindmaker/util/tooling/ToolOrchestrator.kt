package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.LlmApiClient
import org.json.JSONArray
import org.json.JSONObject
import com.kaleaon.mnxmindmaker.util.observability.RequestTracer

class ToolOrchestrator(
    private val llmApiClient: LlmApiClient,
    private val settings: LlmSettings,
    private val registry: ToolRegistry,
    private val policy: ToolPolicyEngine,
    private val requestApproval: suspend (ToolApprovalRequest) -> Boolean,
    private val maxToolRounds: Int = 6,
    private val tracer: RequestTracer? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun run(systemPrompt: String, userPrompt: String): String {
        val transcript = mutableListOf<JSONObject>()
        transcript += JSONObject().put("role", "user").put("content", userPrompt)
        tracer?.recordPromptPipeline("user_prompt", userPrompt)

        val textParts = mutableListOf<String>()
        repeat(maxToolRounds) {
            val providerStart = nowMs()
            val turn = llmApiClient.completeAssistantTurn(
                settings = settings,
                systemPrompt = systemPrompt,
                transcript = transcript,
                tools = registry.specs()
            )
            tracer?.recordProviderResponse(settings.provider.name, turn.text, nowMs() - providerStart)
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
