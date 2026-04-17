package com.kaleaon.mnxmindmaker.util.observability

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import com.kaleaon.mnxmindmaker.util.moderation.ModerationAction
import com.kaleaon.mnxmindmaker.util.moderation.ModerationPipeline
import com.kaleaon.mnxmindmaker.util.moderation.ModerationRequest
import com.kaleaon.mnxmindmaker.util.moderation.ModerationStage
import com.kaleaon.mnxmindmaker.util.moderation.SensitiveEntityModerationPolicy
import com.kaleaon.mnxmindmaker.util.tooling.ToolOrchestrator
import java.util.UUID

data class PromptPipelineRequest(
    val prompt: String,
    val task: String = "assist",
    val retrievalLimit: Int = 8,
    val wakeUpContextEnabled: Boolean = false,
    val wakeUpContextL0: String = "",
    val wakeUpContextL1: String = ""
)

data class PromptPipelineResult(
    val responseText: String,
    val retrievalHits: List<MindNode>,
    val trace: RequestTrace
)

/**
 * Central request pipeline with first-class tracing for:
 * prompt construction, retrieval hits, tool calls, provider response, and errors.
 */
class PromptPipelineEngine(
    private val traceStore: TraceStore,
    private val moderationPipeline: ModerationPipeline = ModerationPipeline(listOf(SensitiveEntityModerationPolicy())),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val WAKE_UP_CONTEXT_MAX_CHARS = 1200
        private const val DYNAMIC_RETRIEVED_CONTEXT_MAX_CHARS = 2000
    }

    suspend fun execute(
        request: PromptPipelineRequest,
        settings: LlmSettings,
        memoryNodes: List<MindNode>,
        baseSystemPrompt: String,
        orchestratorFactory: (RequestTracer, LlmSettings) -> ToolOrchestrator
    ): PromptPipelineResult {
        val tracer = RequestTracer(requestId = UUID.randomUUID().toString(), nowMs = nowMs)

        return try {
            val promptModeration = moderationPipeline.moderate(
                ModerationRequest(
                    text = request.prompt,
                    stage = ModerationStage.PROMPT,
                    policyId = "prompt_input"
                )
            )
            tracer.recordPromptPipeline("incoming", request.prompt)
            tracer.recordPromptPipeline("prompt_moderation_action", promptModeration.action.name)
            if (promptModeration.action == ModerationAction.DENY) {
                val trace = tracer.finish()
                traceStore.append(trace)
                return PromptPipelineResult(
                    responseText = "Request blocked by moderation policy.",
                    retrievalHits = emptyList(),
                    trace = trace
                )
            }

            val moderatedPrompt = promptModeration.text
            val retrievalContext = MemoryRetrievalService.RetrievalContext(
                prompt = moderatedPrompt,
                task = request.task,
                nowEpochMs = nowMs()
            )
            val retrievalResult = MemoryRetrievalService.retrieveWithSuggestions(
                memories = memoryNodes,
                context = retrievalContext,
                limit = request.retrievalLimit
            )
            // Prompt composition is read-only; persistence decisions belong to explicit write workflows.
            val hits = retrievalResult.memories
            tracer.recordRetrievalHits(hits)

            val retrievalContextText = hits.joinToString("\n") { node ->
                "- ${node.label}: ${node.description.take(120)}"
            }

            val systemPromptSections = mutableListOf<String>()
            systemPromptSections += baseSystemPrompt
            tracer.recordPromptPipeline("base_system_prompt_included", baseSystemPrompt)

            if (request.wakeUpContextEnabled) {
                val wakeUpContext = buildString {
                    val l0 = request.wakeUpContextL0.trim()
                    val l1 = request.wakeUpContextL1.trim()
                    if (l0.isNotEmpty()) appendLine("L0: $l0")
                    if (l1.isNotEmpty()) appendLine("L1: $l1")
                }.trim()

                if (wakeUpContext.isNotEmpty()) {
                    tracer.recordPromptPipeline("wake_up_context_generated", wakeUpContext)
                    val truncatedWakeUpContext = wakeUpContext.take(WAKE_UP_CONTEXT_MAX_CHARS)
                    if (truncatedWakeUpContext.length < wakeUpContext.length) {
                        tracer.recordPromptPipeline("wake_up_context_truncated", truncatedWakeUpContext)
                    }
                    systemPromptSections += "Wake-up context:\n$truncatedWakeUpContext"
                } else {
                    tracer.recordPromptPipeline("wake_up_context_skipped", "enabled_but_empty")
                }
            } else {
                tracer.recordPromptPipeline("wake_up_context_skipped", "disabled")
            }

            tracer.recordPromptPipeline("dynamic_retrieved_context_generated", retrievalContextText)
            val truncatedRetrievedContext = retrievalContextText.take(DYNAMIC_RETRIEVED_CONTEXT_MAX_CHARS)
            if (truncatedRetrievedContext.length < retrievalContextText.length) {
                tracer.recordPromptPipeline("dynamic_retrieved_context_truncated", truncatedRetrievedContext)
            }
            systemPromptSections += "Retrieved context:\n$truncatedRetrievedContext"

            val systemPrompt = systemPromptSections.joinToString("\n\n")
            tracer.recordPromptPipeline("assembled_system_prompt", systemPrompt)

            val orchestrator = orchestratorFactory(tracer, settings)
            val response = orchestrator.run(systemPrompt, moderatedPrompt)

            val trace = tracer.finish()
            traceStore.append(trace)
            PromptPipelineResult(responseText = response, retrievalHits = hits, trace = trace)
        } catch (e: Exception) {
            tracer.recordError("pipeline", e.message ?: "unknown error")
            val trace = tracer.finish()
            traceStore.append(trace)
            throw e
        }
    }
}
