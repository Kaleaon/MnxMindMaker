package com.kaleaon.mnxmindmaker.observability

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.observability.RequestTracer
import com.kaleaon.mnxmindmaker.util.observability.TraceEvidenceExporter
import com.kaleaon.mnxmindmaker.util.observability.TraceEventType
import com.kaleaon.mnxmindmaker.util.observability.TraceReplayEngine
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTracingTest {

    @Test
    fun `records provenance hashes for prompt retrieval tool provider and error`() {
        var now = 10_000L
        val tracer = RequestTracer(requestId = "req-prov") { now++ }

        tracer.recordPromptPipeline("incoming", "draft a planning prompt")
        tracer.recordRetrievalHits(
            listOf(
                MindNode(
                    id = "n1",
                    label = "Memory A",
                    type = NodeType.MEMORY,
                    description = "remember this retrieval hit",
                    attributes = mutableMapOf("confidence" to "0.91", "source" to "vault")
                )
            )
        )
        val invocation = ToolInvocation(
            id = "tool-1",
            toolName = "query_memory",
            argumentsJson = JSONObject().put("query", "retro")
        )
        val result = ToolResult(
            toolCallId = "tool-1",
            toolName = "query_memory",
            success = true,
            outputText = "ok"
        )
        tracer.recordToolCall(invocation, result, latencyMs = 18)
        tracer.recordProviderResponse(provider = "OPENAI", responsePreview = "assistant says hi", latencyMs = 320, model = "gpt-4.1")
        tracer.recordError("provider", "timeout")

        val trace = tracer.finish()
        val prompt = trace.events.first { it.type == TraceEventType.PROMPT_PIPELINE }
        val retrieval = trace.events.first { it.type == TraceEventType.RETRIEVAL_HIT }
        val tool = trace.events.first { it.type == TraceEventType.TOOL_CALL }
        val provider = trace.events.first { it.type == TraceEventType.PROVIDER_RESPONSE }
        val error = trace.events.first { it.type == TraceEventType.ERROR }

        assertTrue(prompt.payload["prompt_hash"].orEmpty().length == 64)
        assertTrue(retrieval.payload["retrieval_hash"].orEmpty().length == 64)
        assertEquals("vault", retrieval.payload["source"])
        assertTrue(tool.payload["tool_arguments_hash"].orEmpty().length == 64)
        assertTrue(tool.payload["tool_output_hash"].orEmpty().length == 64)
        assertEquals("gpt-4.1", provider.payload["model"])
        assertTrue(provider.payload["response_hash"].orEmpty().length == 64)
        assertTrue(error.payload["error_hash"].orEmpty().length == 64)
    }

    @Test
    fun `exports replayable evidence bundle`() {
        var now = 20_000L
        val tracer = RequestTracer(requestId = "req-replay") { now++ }
        tracer.recordPromptPipeline("incoming", "hello")
        tracer.recordProviderResponse(provider = "LOCAL", responsePreview = "world", latencyMs = 11, model = "gemma")
        val trace = tracer.finish()

        val replay = TraceReplayEngine.replay(trace)
        assertEquals("req-replay", replay.requestId)
        assertEquals(2, replay.frames.size)
        assertTrue(replay.frames.all { it.evidenceHash.length == 64 })

        val json = TraceEvidenceExporter.exportJson(trace)
        val parsed = JSONObject(json)
        assertEquals("mnx.trace_evidence.v1", parsed.getString("schema"))
        assertEquals(1, parsed.getInt("trace_count"))
        assertNotNull(parsed.getJSONArray("traces").getJSONObject(0).getJSONArray("events"))
    }
}
