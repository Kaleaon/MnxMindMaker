package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryAdvancedToolsTest {

    @Test
    fun `graph refactor and link repair expose read only diagnostics and approval gated mutations`() {
        var graph = MindGraph(
            nodes = mutableListOf(
                MindNode(id = "n1", label = "Core", type = NodeType.IDENTITY),
                MindNode(id = "n2", label = "Child", type = NodeType.KNOWLEDGE, parentId = "n1")
            ),
            edges = mutableListOf(
                MindEdge(id = "e1", fromNodeId = "n1", toNodeId = "n2"),
                MindEdge(id = "e2", fromNodeId = "n1", toNodeId = "n2"),
                MindEdge(id = "e3", fromNodeId = "n9", toNodeId = "n2")
            )
        )
        val registry = ToolRegistry(getGraph = { graph }, setGraph = { graph = it })

        val diag = registry.invoke(ToolInvocation("d1", "link_repair_diagnostics", JSONObject()))
        assertTrue(diag.success)
        assertEquals(1, diag.contentJson.getInt("invalid_edge_count"))
        assertEquals(1, diag.contentJson.getInt("duplicate_edge_pair_count"))

        val denied = registry.invoke(ToolInvocation("m1", "link_repair_apply", JSONObject()))
        assertTrue(denied.isError)
        assertEquals("approval_required", denied.contentJson.getString("error"))

        val applied = registry.invoke(
            ToolInvocation("m2", "link_repair_apply", JSONObject().put("approval_token", "APPROVED"))
        )
        assertTrue(applied.success)
        assertEquals(1, graph.edges.size)
    }

    @Test
    fun `taxonomy normalization and stale memory diagnostics are deterministic`() {
        val now = System.currentTimeMillis()
        var graph = MindGraph(
            nodes = mutableListOf(
                MindNode(
                    id = "m-old",
                    label = "Project Notes",
                    type = NodeType.MEMORY,
                    attributes = mutableMapOf(
                        "timestamp" to (now - 60L * 24 * 60 * 60 * 1000).toString(),
                        "taxonomy_key" to "bad-key"
                    )
                ),
                MindNode(
                    id = "m-fresh",
                    label = "Current Session",
                    type = NodeType.MEMORY,
                    attributes = mutableMapOf(
                        "timestamp" to now.toString()
                    )
                )
            )
        )
        val registry = ToolRegistry(getGraph = { graph }, setGraph = { graph = it })

        val stale = registry.invoke(
            ToolInvocation("s1", "stale_memory_diagnostics", JSONObject().put("max_age_ms", 30L * 24 * 60 * 60 * 1000))
        )
        assertEquals(1, stale.contentJson.getInt("stale_count"))
        assertEquals("m-old", stale.contentJson.getJSONArray("stale_nodes").getJSONObject(0).getString("node_id"))

        val driftBefore = registry.invoke(ToolInvocation("t1", "taxonomy_normalization_diagnostics", JSONObject()))
        assertEquals(2, driftBefore.contentJson.getInt("drifted_count"))

        val normalized = registry.invoke(
            ToolInvocation("t2", "taxonomy_normalization_apply", JSONObject().put("approval_token", "APPROVED"))
        )
        assertTrue(normalized.success)

        val driftAfter = registry.invoke(ToolInvocation("t3", "taxonomy_normalization_diagnostics", JSONObject()))
        assertEquals(0, driftAfter.contentJson.getInt("drifted_count"))
        assertEquals("project_notes", graph.nodes.first { it.id == "m-old" }.attributes["taxonomy_key"])
    }
}

