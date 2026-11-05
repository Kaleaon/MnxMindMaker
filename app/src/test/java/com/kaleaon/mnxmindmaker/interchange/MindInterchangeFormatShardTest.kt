package com.kaleaon.mnxmindmaker.interchange

import com.kaleaon.mnxmindmaker.model.ContributorProvenance
import com.kaleaon.mnxmindmaker.model.EvidenceReference
import com.kaleaon.mnxmindmaker.model.KnowledgeShard
import com.kaleaon.mnxmindmaker.model.KnowledgeShardSchemaVersion
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MindInterchangeFormatShardTest {

    @Test
    fun testExportAndImportJsonWithNativeKnowledgeShard() {
        val shard = KnowledgeShard(
            label = "Reinforcement Learning Shard",
            description = "Policy gradient and value iteration algorithms",
            schemaVersion = KnowledgeShardSchemaVersion(1, 0),
            contributor = ContributorProvenance(
                id = "agent_rl_core",
                role = "domain_expert",
                model = "claude-3-5-sonnet"
            ),
            evidenceReferences = listOf(
                EvidenceReference(
                    sourceUrlOrPath = "https://sutton.and.barto/book.pdf",
                    title = "Reinforcement Learning: An Introduction",
                    snippet = "Temporal difference learning combines Monte Carlo ideas and dynamic programming ideas.",
                    confidenceScore = 0.99f
                )
            ),
            topics = listOf("rl", "machine-learning")
        )

        val conceptNode = MindNode(
            id = "node_concept_td",
            label = "TD Learning",
            type = NodeType.KNOWLEDGE,
            description = "Temporal Difference Learning concept node"
        )
        val shardNode = shard.toMindNode(x = 100f, y = 200f)

        val graph = MindGraph(
            name = "RL Knowledge Mind",
            nodes = mutableListOf(conceptNode, shardNode),
            edges = mutableListOf(
                MindEdge(fromNodeId = shardNode.id, toNodeId = conceptNode.id, label = "includes")
            )
        )

        val json = MindInterchangeFormat.exportJson(graph)
        assertTrue(json.contains("mnx.interchange"))
        assertTrue(json.contains("KNOWLEDGE_SHARD"))
        assertTrue(json.contains("agent_rl_core"))
        assertTrue(json.contains("https://sutton.and.barto/book.pdf"))

        val importedGraph = MindInterchangeFormat.importJson(json)
        assertEquals(2, importedGraph.nodes.size)
        assertEquals(1, importedGraph.edges.size)

        val importedShardNode = importedGraph.nodes.first { it.id == shardNode.id }
        assertEquals(NodeType.KNOWLEDGE_SHARD, importedShardNode.type)

        val restoredShard = KnowledgeShard.fromMindNode(importedShardNode)
        assertNotNull(restoredShard)
        assertEquals("Reinforcement Learning Shard", restoredShard?.label)
        assertEquals("1.0", restoredShard?.schemaVersion?.toString())
        assertEquals("agent_rl_core", restoredShard?.contributor?.id)
        assertEquals(1, restoredShard?.evidenceReferences?.size)
        assertEquals("https://sutton.and.barto/book.pdf", restoredShard?.evidenceReferences?.get(0)?.sourceUrlOrPath)
    }

    @Test
    fun testExportAndImportBundleWithNativeKnowledgeShard() {
        val shard = KnowledgeShard(
            label = "Multimodal Vision Shard",
            contributor = ContributorProvenance(id = "vision_agent_01"),
            evidenceReferences = listOf(
                EvidenceReference(sourceUrlOrPath = "docs/vision_bench.json", confidenceScore = 0.92f)
            ),
            topics = listOf("vision", "ocr")
        )

        val shardNode = shard.toMindNode(x = 50f, y = 50f)
        val graph = MindGraph(
            name = "Vision Mind",
            nodes = mutableListOf(shardNode)
        )

        val payload = MindInterchangeFormat.BundlePayload(
            graph = graph,
            metadata = mapOf("author" to "Vision Pipeline")
        )

        val bundleBytes = MindInterchangeFormat.exportBundle(payload)
        val importedPayload = MindInterchangeFormat.importBundle(bundleBytes)

        assertEquals("Vision Mind", importedPayload.graph.name)
        assertEquals("Vision Pipeline", importedPayload.metadata["author"])

        val importedNode = importedPayload.graph.nodes[0]
        assertEquals(NodeType.KNOWLEDGE_SHARD, importedNode.type)

        val restoredShard = KnowledgeShard.fromMindNode(importedNode)
        assertNotNull(restoredShard)
        assertEquals("Multimodal Vision Shard", restoredShard?.label)
        assertEquals("vision_agent_01", restoredShard?.contributor?.id)
    }

    @Test
    fun testValidationFailsForInvalidShardMetadata() {
        val invalidJson = """
            {
              "schema": {
                "family": "mnx.interchange",
                "version": { "major": 1, "minor": 0 }
              },
              "compatibility": {
                "min_reader_version": { "major": 1, "minor": 0 },
                "forward_compat_extensions": []
              },
              "graph": {
                "id": "g1",
                "name": "Invalid Mind",
                "created_at": 1000,
                "modified_at": 2000,
                "nodes": [
                  {
                    "id": "shard_invalid",
                    "label": "Invalid Shard",
                    "type": "KNOWLEDGE_SHARD",
                    "x": 0.0,
                    "y": 0.0,
                    "attributes": {
                      "shard_schema_version": ""
                    },
                    "dimensions": {}
                  }
                ],
                "edges": []
              }
            }
        """.trimIndent()

        try {
            MindInterchangeFormat.validateJson(invalidJson)
            fail("Expected ValidationException for blank shard_schema_version")
        } catch (e: MindInterchangeFormat.ValidationException) {
            assertTrue(e.message?.contains("shard_schema_version") == true)
        }
    }
}
