package com.kaleaon.mnxmindmaker.util.observability

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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

data class ReplayFrame(
    val index: Int,
    val type: TraceEventType,
    val atEpochMs: Long,
    val offsetMs: Long,
    val payload: Map<String, String>,
    val evidenceHash: String
)

data class ReplayTimeline(
    val requestId: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val durationMs: Long,
    val frames: List<ReplayFrame>
)

object TraceReplayEngine {
    fun replay(trace: RequestTrace): ReplayTimeline {
        val frames = trace.events
            .sortedBy { it.atEpochMs }
            .mapIndexed { index, event ->
                ReplayFrame(
                    index = index,
                    type = event.type,
                    atEpochMs = event.atEpochMs,
                    offsetMs = (event.atEpochMs - trace.startedAtEpochMs).coerceAtLeast(0L),
                    payload = event.payload,
                    evidenceHash = hashEvent(event)
                )
            }
        return ReplayTimeline(
            requestId = trace.requestId,
            startedAtEpochMs = trace.startedAtEpochMs,
            completedAtEpochMs = trace.completedAtEpochMs,
            durationMs = trace.durationMs,
            frames = frames
        )
    }

    private fun hashEvent(event: TraceEvent): String {
        val canonicalPayload = event.payload
            .toSortedMap()
            .entries
            .joinToString("|") { (k, v) -> "$k=$v" }
        return Hashing.sha256Hex("${event.type.name}|${event.atEpochMs}|$canonicalPayload")
    }
}

object TraceEvidenceExporter {
    fun exportJson(trace: RequestTrace): String = exportJson(listOf(trace))

    fun exportJson(traces: List<RequestTrace>): String {
        val root = JSONObject()
            .put("schema", "mnx.trace_evidence.v1")
            .put("exported_at_epoch_ms", System.currentTimeMillis())
            .put("trace_count", traces.size)

        val traceArray = JSONArray()
        traces.forEach { trace ->
            val replay = TraceReplayEngine.replay(trace)
            val events = JSONArray()
            replay.frames.forEach { frame ->
                events.put(
                    JSONObject()
                        .put("index", frame.index)
                        .put("type", frame.type.name)
                        .put("at_epoch_ms", frame.atEpochMs)
                        .put("offset_ms", frame.offsetMs)
                        .put("evidence_hash", frame.evidenceHash)
                        .put("payload", JSONObject(frame.payload))
                )
            }
            traceArray.put(
                JSONObject()
                    .put("request_id", replay.requestId)
                    .put("started_at_epoch_ms", replay.startedAtEpochMs)
                    .put("completed_at_epoch_ms", replay.completedAtEpochMs)
                    .put("duration_ms", replay.durationMs)
                    .put("events", events)
            )
        }
        root.put("traces", traceArray)
        return root.toString()
    }
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
                "prompt_preview" to promptPreview.take(300),
                "prompt_hash" to Hashing.sha256Hex(promptPreview)
            )
        )
    }

    fun recordRetrievalHits(hits: List<MindNode>) {
        hits.forEachIndexed { index, node ->
            val source = node.attributes["source"]
                ?: node.attributes["origin"]
                ?: node.attributes["memory_source"]
                ?: ""
            val retrievalEvidence = "${node.id}|${node.label}|${node.description}|${index + 1}|$source"
            record(
                TraceEventType.RETRIEVAL_HIT,
                mapOf(
                    "rank" to (index + 1).toString(),
                    "node_id" to node.id,
                    "label" to node.label,
                    "confidence" to (node.attributes["confidence"] ?: ""),
                    "source" to source,
                    "retrieval_hash" to Hashing.sha256Hex(retrievalEvidence)
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
                "output_preview" to result.outputText.take(220),
                "tool_arguments_hash" to Hashing.sha256Hex(invocation.argumentsJson.toString()),
                "tool_output_hash" to Hashing.sha256Hex(result.contentJson.toString())
            )
        )
    }

    fun recordProviderResponse(provider: String, responsePreview: String, latencyMs: Long, model: String = "") {
        record(
            TraceEventType.PROVIDER_RESPONSE,
            mapOf(
                "provider" to provider,
                "model" to model,
                "latency_ms" to latencyMs.toString(),
                "response_preview" to responsePreview.take(300),
                "response_hash" to Hashing.sha256Hex(responsePreview)
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
                "message" to message.take(400),
                "error_hash" to Hashing.sha256Hex("$stage:$message")
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

private object Hashing {
    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
