package com.kaleaon.mnxmindmaker.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeShardTest {

    @Test
    fun testKnowledgeShardCreationAndJsonSerialization() {
        val version = KnowledgeShardSchemaVersion(1, 0)
        val contributor = ContributorProvenance(
            id = "agent-alpha-42",
            role = "researcher",
            model = "claude-3-5-sonnet"
        )
        val evidence = EvidenceReference(
            sourceUrlOrPath = "https://arxiv.org/abs/2301.00000",
            title = "Attention Is All You Need Paper",
            snippet = "Transformer architecture uses multi-head self-attention.",
            confidenceScore = 0.95f
        )
        val shard = KnowledgeShard(
            label = "Transformer Architecture Notes",
            description = "Modular graph notes on transformer self-attention.",
            schemaVersion = version,
            contributor = contributor,
            evidenceReferences = listOf(evidence),
            nodeIds = listOf("node_concept_1", "node_concept_2"),
            topics = listOf("deep-learning", "attention-mechanisms")
        )

        val json = shard.toJson()
        assertEquals("Transformer Architecture Notes", json.getString("label"))
        assertEquals("1.0", json.getString("schema_version"))
        assertEquals("agent-alpha-42", json.getJSONObject("contributor").getString("id"))
        assertEquals(1, json.getJSONArray("evidence_references").length())

        val restored = KnowledgeShard.fromJson(json)
        assertEquals(shard.id, restored.id)
        assertEquals(shard.label, restored.label)
        assertEquals(shard.schemaVersion, restored.schemaVersion)
        assertEquals(shard.contributor.id, restored.contributor.id)
        assertEquals(shard.contributor.role, restored.contributor.role)
        assertEquals(shard.contributor.model, restored.contributor.model)
        assertEquals(1, restored.evidenceReferences.size)
        assertEquals("https://arxiv.org/abs/2301.00000", restored.evidenceReferences[0].sourceUrlOrPath)
        assertEquals(2, restored.nodeIds.size)
        assertEquals(2, restored.topics.size)
    }

    @Test
    fun testMindNodeConversionRoundTrip() {
        val contributor = ContributorProvenance(
            id = "agent-beta",
            role = "synthesizer"
        )
        val evidence = EvidenceReference(
            sourceUrlOrPath = "docs/research.pdf",
            snippet = "Sample snippet evidence"
        )
        val shard = KnowledgeShard(
            label = "Quantum Computing Shard",
            description = "Qubit superposition concepts.",
            contributor = contributor,
            evidenceReferences = listOf(evidence),
            topics = listOf("quantum", "physics")
        )

        val mindNode = shard.toMindNode(x = 120f, y = 240f)
        assertEquals(NodeType.KNOWLEDGE_SHARD, mindNode.type)
        assertEquals(120f, mindNode.x)
        assertEquals(240f, mindNode.y)
        assertEquals("1.0", mindNode.attributes["shard_schema_version"])
        assertEquals("agent-beta", mindNode.attributes["shard_contributor_id"])

        val reconstructed = KnowledgeShard.fromMindNode(mindNode)
        assertNotNull(reconstructed)
        assertEquals(shard.id, reconstructed?.id)
        assertEquals(shard.label, reconstructed?.label)
        assertEquals("1.0", reconstructed?.schemaVersion?.toString())
        assertEquals("agent-beta", reconstructed?.contributor?.id)
        assertEquals(1, reconstructed?.evidenceReferences?.size)
        assertEquals("docs/research.pdf", reconstructed?.evidenceReferences?.get(0)?.sourceUrlOrPath)
        assertTrue(reconstructed?.topics?.contains("quantum") == true)
    }
}
