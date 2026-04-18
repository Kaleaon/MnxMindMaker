package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.observability.RequestTracer
import com.kaleaon.mnxmindmaker.util.moderation.ModerationAction
import com.kaleaon.mnxmindmaker.util.moderation.ModerationPipeline
import com.kaleaon.mnxmindmaker.util.moderation.ModerationRequest
import com.kaleaon.mnxmindmaker.util.moderation.ModerationStage
import com.kaleaon.mnxmindmaker.util.moderation.SensitiveEntityModerationPolicy
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
    private val maxToolRounds: Int = 6,
    private val tracer: RequestTracer? = null,
    private val moderationPipeline: ModerationPipeline = ModerationPipeline(listOf(SensitiveEntityModerationPolicy())),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun run(systemPrompt: String, userPrompt: String): String {
        val promptModeration = moderationPipeline.moderate(
            ModerationRequest(text = userPrompt, stage = ModerationStage.PROMPT, policyId = "orchestrator_prompt")
        )
        if (promptModeration.action == ModerationAction.DENY) {
            return "Request blocked by moderation policy."
        }

        val transcript = mutableListOf<JSONObject>()
        transcript += JSONObject().put("role", "user").put("content", promptModeration.text)
        return run(systemPrompt = systemPrompt, transcript = transcript)
    }

    suspend fun run(systemPrompt: String, transcript: List<JSONObject>): String {
        val conversation = transcript.toMutableList()

        val textParts = mutableListOf<String>()
        repeat(maxToolRounds) {
            val providerStart = nowMs()
            val turn = providerRouter.chat(
                settingsChain = settingsChain,
                systemPrompt = systemPrompt,
                transcript = conversation,
                tools = registry.specs(),
                policy = routingPolicy
            )

            val providerName = settingsChain.firstOrNull()?.provider?.name ?: "unknown"
            tracer?.recordProviderResponse(providerName, turn.text, nowMs() - providerStart)

            if (turn.text.isNotBlank()) {
                textParts += turn.text.trim()
            }

            if (turn.toolInvocations.isEmpty()) {
                val rawOutput = textParts.joinToString("\n\n").ifBlank { "Done." }
                val outputModeration = moderationPipeline.moderate(
                    ModerationRequest(text = rawOutput, stage = ModerationStage.OUTPUT, policyId = "assistant_output")
                )
                return when (outputModeration.action) {
                    ModerationAction.DENY -> "Response blocked by moderation policy."
                    else -> outputModeration.text
                }
            }

            val toolResults = JSONArray()
            for (invocation in turn.toolInvocations) {
                val toolStart = nowMs()
                val result = executeInvocation(invocation)
                tracer?.recordToolCall(invocation, result, nowMs() - toolStart)

                val outputText = result.contentJson.optString("output_text", result.contentJson.toString())
                toolResults.put(
                    JSONObject()
                        .put("tool_call_id", result.toolUseId)
                        .put("name", invocation.toolName)
                        .put("success", !result.isError)
                        .put("output_text", outputText)
                        .put("output_json", result.contentJson)
                )
            }

            conversation += JSONObject()
                .put("role", "tool")
                .put("content", toolResults)
        }

        return textParts
            .joinToString("\n\n")
            .ifBlank { "Tool loop reached limit with no textual response." }
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

                if (approved) {
                    registry.invoke(invocation)
                } else {
                    ToolResult(
                        toolUseId = invocation.id,
                        isError = true,
                        contentJson = JSONObject()
                            .put("tool_name", invocation.toolName)
                            .put("error", "approval_rejected")
                            .put("message", "User denied tool invocation")
                    )
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
