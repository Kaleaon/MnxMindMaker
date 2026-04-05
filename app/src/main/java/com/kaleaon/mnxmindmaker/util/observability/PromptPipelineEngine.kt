package com.kaleaon.mnxmindmaker.util.observability

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import com.kaleaon.mnxmindmaker.util.tooling.ToolOrchestrator
import java.util.UUID

data class PromptPipelineRequest(
    val prompt: String,
    val task: String = "assist",
    val retrievalLimit: Int = 8
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
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun execute(
        request: PromptPipelineRequest,
        settings: LlmSettings,
        memoryNodes: List<MindNode>,
        baseSystemPrompt: String,
        orchestratorFactory: (RequestTracer, LlmSettings) -> ToolOrchestrator
    ): PromptPipelineResult {
        val tracer = RequestTracer(requestId = UUID.randomUUID().toString(), nowMs = nowMs)

        return try {
            tracer.recordPromptPipeline("incoming", request.prompt)
            val retrievalContext = MemoryRetrievalService.RetrievalContext(
                prompt = request.prompt,
                task = request.task,
                nowEpochMs = nowMs()
            )
            val hits = MemoryRetrievalService.retrieve(memoryNodes, retrievalContext, request.retrievalLimit)
            tracer.recordRetrievalHits(hits)

            val retrievalContextText = hits.joinToString("\n") { node ->
                "- ${node.label}: ${node.description.take(120)}"
            }
            val systemPrompt = "$baseSystemPrompt\n\nRetrieved context:\n$retrievalContextText"
            tracer.recordPromptPipeline("assembled_system_prompt", systemPrompt)

            val orchestrator = orchestratorFactory(tracer, settings)
            val response = orchestrator.run(systemPrompt, request.prompt)

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
