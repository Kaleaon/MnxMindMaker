package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.model.KnowledgeShard
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.tooling.ToolInvocation
import com.kaleaon.mnxmindmaker.util.tooling.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KnowledgeShardToolsTest {

    private lateinit var currentGraph: MindGraph
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        currentGraph = MindGraph(
            name = "Test Shard Mind",
            nodes = mutableListOf(
                MindNode(id = "node_concept_101", label = "Neural Networks", type = NodeType.KNOWLEDGE),
                MindNode(id = "node_concept_102", label = "Backpropagation", type = NodeType.KNOWLEDGE)
            )
        )
        registry = ToolRegistry(
            getGraph = { currentGraph },
            setGraph = { currentGraph = it }
        )
    }

    @Test
    fun testShardCreateToolInvocation() {
        val invocation = ToolInvocation(
            id = "inv_create_1",
            toolName = "shard_create",
            argumentsJson = JSONObject()
                .put("label", "Deep Learning Foundations")
                .put("description", "Core concepts of deep neural network architectures")
                .put("contributor_id", "agent_research_01")
                .put("contributor_role", "lead_researcher")
                .put("contributor_model", "claude-3-5-sonnet")
                .put("schema_version", "1.0")
                .put("topics", JSONArray().put("ai").put("deep-learning"))
                .put("node_ids", JSONArray().put("node_concept_101").put("node_concept_102"))
        )

        val result = registry.invoke(invocation)
        assertTrue(result.success)
        assertFalse(result.isError)

        val output = result.contentJson
        assertEquals("Deep Learning Foundations", output.getString("label"))
        assertEquals("1.0", output.getString("schema_version"))
        assertEquals("agent_research_01", output.getJSONObject("contributor").getString("id"))

        val shardNodeId = output.getString("node_id")
        val createdNode = currentGraph.nodes.firstOrNull { it.id == shardNodeId }
        assertNotNull(createdNode)
        assertEquals(NodeType.KNOWLEDGE_SHARD, createdNode?.type)
        assertEquals("1.0", createdNode?.attributes?.get("shard_schema_version"))
        assertEquals("agent_research_01", createdNode?.attributes?.get("shard_contributor_id"))
    }

    @Test
    fun testShardAttachEvidenceToolInvocation() {
        // First create shard
        val createInv = ToolInvocation(
            id = "inv_create_2",
            toolName = "shard_create",
            argumentsJson = JSONObject()
                .put("label", "Natural Language Processing Shard")
                .put("contributor_id", "agent_nlp_02")
        )
        val createResult = registry.invoke(createInv)
        val shardNodeId = createResult.contentJson.getString("node_id")

        // Attach evidence
        val attachInv = ToolInvocation(
            id = "inv_attach_1",
            toolName = "shard_attach_evidence",
            argumentsJson = JSONObject()
                .put("shard_id", shardNodeId)
                .put("source_url_or_path", "https://arxiv.org/abs/1706.03762")
                .put("title", "Attention Is All You Need")
                .put("snippet", "Dominant sequence transduction models are based on complex recurrent or convolutional neural networks.")
                .put("confidence_score", 0.98)
        )

        val attachResult = registry.invoke(attachInv)
        assertTrue(attachResult.success)

        val evidenceArray = attachResult.contentJson.getJSONArray("evidence_references")
        assertEquals(1, evidenceArray.length())
        assertEquals("https://arxiv.org/abs/1706.03762", evidenceArray.getJSONObject(0).getString("source_url_or_path"))
        assertEquals(0.98, evidenceArray.getJSONObject(0).getDouble("confidence_score"), 0.001)

        val updatedNode = currentGraph.nodes.first { it.id == shardNodeId }
        val shardFromNode = KnowledgeShard.fromMindNode(updatedNode)
        assertNotNull(shardFromNode)
        assertEquals(1, shardFromNode?.evidenceReferences?.size)
        assertEquals("https://arxiv.org/abs/1706.03762", shardFromNode?.evidenceReferences?.get(0)?.sourceUrlOrPath)
    }

    @Test
    fun testShardQueryToolInvocation() {
        // Create two shards
        registry.invoke(
            ToolInvocation(
                id = "inv_c1",
                toolName = "shard_create",
                argumentsJson = JSONObject()
                    .put("label", "Robotics Dynamics")
                    .put("contributor_id", "agent_robotics")
                    .put("topics", JSONArray().put("control").put("mechanics"))
            )
        )
        registry.invoke(
            ToolInvocation(
                id = "inv_c2",
                toolName = "shard_create",
                argumentsJson = JSONObject()
                    .put("label", "Computer Vision Optics")
                    .put("contributor_id", "agent_vision")
                    .put("topics", JSONArray().put("optics").put("imaging"))
            )
        )

        // Query by topic
        val queryResult = registry.invoke(
            ToolInvocation(
                id = "inv_q1",
                toolName = "shard_query",
                argumentsJson = JSONObject().put("topic", "control")
            )
        )
        assertTrue(queryResult.success)
        assertEquals(1, queryResult.contentJson.getInt("count"))
        assertEquals("Robotics Dynamics", queryResult.contentJson.getJSONArray("shards").getJSONObject(0).getString("label"))

        // Query by text query
        val queryTextResult = registry.invoke(
            ToolInvocation(
                id = "inv_q2",
                toolName = "shard_query",
                argumentsJson = JSONObject().put("query", "Vision")
            )
        )
        assertTrue(queryTextResult.success)
        assertEquals(1, queryTextResult.contentJson.getInt("count"))
        assertEquals("Computer Vision Optics", queryTextResult.contentJson.getJSONArray("shards").getJSONObject(0).getString("label"))
    }

    @Test
    fun testShardLinkToolInvocation() {
        val createResult = registry.invoke(
            ToolInvocation(
                id = "inv_c1",
                toolName = "shard_create",
                argumentsJson = JSONObject()
                    .put("label", "Supervised Learning Shard")
                    .put("contributor_id", "agent_ml")
            )
        )
        val shardNodeId = createResult.contentJson.getString("node_id")

        val linkResult = registry.invoke(
            ToolInvocation(
                id = "inv_l1",
                toolName = "shard_link",
                argumentsJson = JSONObject()
                    .put("shard_id", shardNodeId)
                    .put("target_node_id", "node_concept_101")
                    .put("label", "exemplifies")
            )
        )

        assertTrue(linkResult.success)
        assertEquals("linked", linkResult.contentJson.getString("status"))
        assertTrue(currentGraph.edges.any { it.fromNodeId == shardNodeId && it.toNodeId == "node_concept_101" && it.label == "exemplifies" })
    }
}
