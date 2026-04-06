package com.kaleaon.mnxmindmaker.util.observability

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolResult

enum class TraceEventType {
    PROMPT_PIPELINE,
    RETRIEVAL_HIT,
    TOOL_CALL,
    PROVIDER_RESPONSE,
    DEPLOY_INITIATED,
    DEPLOY_COMPLETED,
    DEPLOY_FAILED,
    ACTIVATION_SUCCESS,
    ACTIVATION_FAILURE,
    INVOCATION_METRIC,
    ERROR
}

data class CorrelationIds(
    val correlationId: String,
    val personaVersion: String,
    val deploymentManifestHash: String
) {
    companion object {
        fun from(personaVersion: String, deploymentManifestHash: String): CorrelationIds {
            val normalizedVersion = personaVersion.trim()
            val normalizedHash = deploymentManifestHash.trim()
            return CorrelationIds(
                correlationId = "${normalizedVersion.ifEmpty { "unknown" }}:${normalizedHash.ifEmpty { "unknown" }}",
                personaVersion = normalizedVersion,
                deploymentManifestHash = normalizedHash
            )
        }
    }
}

data class TraceEvent(
    val type: TraceEventType,
    val atEpochMs: Long,
    val payload: Map<String, String>
)

data class RequestTrace(
    val requestId: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val events: List<TraceEvent>
) {
    val durationMs: Long get() = (completedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
}

interface TraceStore {
    fun append(trace: RequestTrace)
    fun list(): List<RequestTrace>
}

class InMemoryTraceStore : TraceStore {
    private val traces = mutableListOf<RequestTrace>()

    override fun append(trace: RequestTrace) {
        traces += trace
    }

    override fun list(): List<RequestTrace> = traces.toList()
}

class RequestTracer(
    private val requestId: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val startedAtEpochMs = nowMs()
    private val events = mutableListOf<TraceEvent>()

    fun recordPromptPipeline(stage: String, promptPreview: String) {
        record(
            TraceEventType.PROMPT_PIPELINE,
            mapOf(
                "stage" to stage,
                "prompt_preview" to promptPreview.take(300)
            )
        )
    }

    fun recordRetrievalHits(hits: List<MindNode>) {
        hits.forEachIndexed { index, node ->
            record(
                TraceEventType.RETRIEVAL_HIT,
                mapOf(
                    "rank" to (index + 1).toString(),
                    "node_id" to node.id,
                    "label" to node.label,
                    "confidence" to (node.attributes["confidence"] ?: "")
                )
            )
        }
    }

    fun recordToolCall(invocation: ToolInvocation, result: ToolResult, latencyMs: Long) {
        record(
            TraceEventType.TOOL_CALL,
            mapOf(
                "tool_id" to invocation.id,
                "tool_name" to invocation.name,
                "success" to result.success.toString(),
                "latency_ms" to latencyMs.toString(),
                "output_preview" to result.outputText.take(220)
            )
        )
    }

    fun recordProviderResponse(provider: String, responsePreview: String, latencyMs: Long) {
        record(
            TraceEventType.PROVIDER_RESPONSE,
            mapOf(
                "provider" to provider,
                "latency_ms" to latencyMs.toString(),
                "response_preview" to responsePreview.take(300)
            )
        )
    }

    fun recordDeployInitiated(personaVersion: String, deploymentManifestHash: String) {
        val correlation = CorrelationIds.from(personaVersion, deploymentManifestHash)
        record(
            TraceEventType.DEPLOY_INITIATED,
            lifecyclePayload(correlation)
        )
    }

    fun recordDeployCompleted(personaVersion: String, deploymentManifestHash: String, durationMs: Long) {
        val correlation = CorrelationIds.from(personaVersion, deploymentManifestHash)
        record(
            TraceEventType.DEPLOY_COMPLETED,
            lifecyclePayload(correlation) + ("duration_ms" to durationMs.toString())
        )
    }

    fun recordDeployFailed(personaVersion: String, deploymentManifestHash: String, reason: String) {
        val correlation = CorrelationIds.from(personaVersion, deploymentManifestHash)
        record(
            TraceEventType.DEPLOY_FAILED,
            lifecyclePayload(correlation) + ("reason" to reason.take(300))
        )
    }

    fun recordActivationSuccess(personaVersion: String, deploymentManifestHash: String, provider: String) {
        val correlation = CorrelationIds.from(personaVersion, deploymentManifestHash)
        record(
            TraceEventType.ACTIVATION_SUCCESS,
            lifecyclePayload(correlation) + ("provider" to provider)
        )
    }

    fun recordActivationFailure(
        personaVersion: String,
        deploymentManifestHash: String,
        provider: String,
        reason: String
    ) {
        val correlation = CorrelationIds.from(personaVersion, deploymentManifestHash)
        record(
            TraceEventType.ACTIVATION_FAILURE,
            lifecyclePayload(correlation) +
                mapOf(
                    "provider" to provider,
                    "reason" to reason.take(300)
                )
        )
    }

    fun recordProviderInvocationMetric(
        personaVersion: String,
        deploymentManifestHash: String,
        provider: String,
        latencyMs: Long,
        success: Boolean,
        errorRate: Double
    ) {
        val correlation = CorrelationIds.from(personaVersion, deploymentManifestHash)
        record(
            TraceEventType.INVOCATION_METRIC,
            lifecyclePayload(correlation) +
                mapOf(
                    "provider" to provider,
                    "latency_ms" to latencyMs.toString(),
                    "success" to success.toString(),
                    "error_rate" to errorRate.toString()
                )
        )
    }

    fun recordError(stage: String, message: String) {
        record(
            TraceEventType.ERROR,
            mapOf(
                "stage" to stage,
                "message" to message.take(400)
            )
        )
    }

    fun finish(): RequestTrace {
        return RequestTrace(
            requestId = requestId,
            startedAtEpochMs = startedAtEpochMs,
            completedAtEpochMs = nowMs(),
            events = events.toList()
        )
    }

    private fun lifecyclePayload(correlation: CorrelationIds): Map<String, String> = mapOf(
        "correlation_id" to correlation.correlationId,
        "persona_version" to correlation.personaVersion,
        "deployment_manifest_hash" to correlation.deploymentManifestHash
    )

    private fun record(type: TraceEventType, payload: Map<String, String>) {
        events += TraceEvent(type = type, atEpochMs = nowMs(), payload = payload)
    }
}
