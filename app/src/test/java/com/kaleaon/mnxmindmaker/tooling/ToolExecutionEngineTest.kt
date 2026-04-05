package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.tooling.InMemoryToolTranscriptStore
import com.kaleaon.mnxmindmaker.util.tooling.RedactionUtils
import com.kaleaon.mnxmindmaker.util.tooling.ToolExecutionEngine
import com.kaleaon.mnxmindmaker.util.tooling.ToolExecutionOutcome
import com.kaleaon.mnxmindmaker.util.tooling.ToolHandler
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolOperationClass
import com.kaleaon.mnxmindmaker.util.tooling.ToolPolicyEngine
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import com.kaleaon.mnxmindmaker.util.tooling.ToolTranscriptRecorder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionEngineTest {

    @Test
    fun `failed mutation sequence rolls graph back to pre-sequence state`() {
        val graph = MindGraph(
            nodes = mutableListOf(MindNode(id = "n1", label = "Initial", type = NodeType.IDENTITY))
        )

        val policy = ToolPolicyEngine(
            mapOf(
                "rename_node" to ToolSpec("rename_node", "Rename", ToolOperationClass.MUTATING),
                "explode" to ToolSpec("explode", "Fails", ToolOperationClass.MUTATING)
            )
        )
        val transcripts = InMemoryToolTranscriptStore()
        val engine = ToolExecutionEngine(
            policyEngine = policy,
            handlers = mapOf(
                "rename_node" to ToolHandler { invocation, g ->
                    val id = invocation.argumentsJson.getString("node_id")
                    val next = invocation.argumentsJson.getString("label")
                    val idx = g.nodes.indexOfFirst { it.id == id }
                    g.nodes[idx] = g.nodes[idx].copy(label = next)
                    ToolExecutionOutcome(JSONObject().put("ok", true), mutatedGraph = true)
                },
                "explode" to ToolHandler { _, _ ->
                    throw IllegalStateException("boom")
                }
            ),
            transcriptRecorder = ToolTranscriptRecorder(transcripts, nowMs = { 1234L })
        )

        val result = engine.executeSequence(
            runId = "run-1",
            invocations = listOf(
                ToolInvocation("t1", "rename_node", JSONObject().put("node_id", "n1").put("label", "Changed")),
                ToolInvocation("t2", "explode", JSONObject())
            ),
            graph = graph,
            approve = { _, _ -> true }
        )

        assertTrue(result.rolledBack)
        assertEquals("Initial", result.graph.nodes.first().label)
        assertEquals(2, result.toolResults.size)
        assertTrue(result.toolResults.last().isError)
        assertTrue(result.toolResults.last().rolledBack)
    }

    @Test
    fun `transcript recorder redacts sensitive keys`() {
        val source = JSONObject()
            .put("api_key", "abc123")
            .put("nested", JSONObject().put("token", "xyz"))
            .put("safe", "ok")

        val redacted = RedactionUtils.redact(source)

        assertEquals(RedactionUtils.REDACTED_VALUE, redacted.getString("api_key"))
        assertEquals(RedactionUtils.REDACTED_VALUE, redacted.getJSONObject("nested").getString("token"))
        assertEquals("ok", redacted.getString("safe"))
    }

    @Test
    fun `sensitive operation requires approval and rejection stops sequence`() {
        val graph = MindGraph(nodes = mutableListOf(MindNode(id = "n1", label = "A", type = NodeType.IDENTITY)))

        val policy = ToolPolicyEngine(
            mapOf("rename_node" to ToolSpec("rename_node", "Rename", ToolOperationClass.MUTATING))
        )
        val transcripts = InMemoryToolTranscriptStore()
        val engine = ToolExecutionEngine(
            policyEngine = policy,
            handlers = mapOf(
                "rename_node" to ToolHandler { _, _ ->
                    ToolExecutionOutcome(JSONObject().put("ok", true), mutatedGraph = true)
                }
            ),
            transcriptRecorder = ToolTranscriptRecorder(transcripts)
        )

        val result = engine.executeSequence(
            runId = "run-2",
            invocations = listOf(ToolInvocation("t1", "rename_node", JSONObject().put("node_id", "n1"))),
            graph = graph,
            approve = { _, _ -> false }
        )

        assertFalse(result.rolledBack)
        assertTrue(result.toolResults.single().isError)
        assertEquals("approval_rejected", result.toolResults.single().contentJson.getString("error"))
    }
}
