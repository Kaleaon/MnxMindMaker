package com.kaleaon.mnxmindmaker.persona

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGraphTransformerTest {

    @Test
    fun `maps graph to persona sections and preserves dimensional refs`() {
        val graph = MindGraph(
            id = "g-1",
            name = "Astra",
            createdAt = 1000L,
            modifiedAt = 2000L,
            nodes = mutableListOf(
                MindNode(
                    id = "identity-1",
                    label = "Astra",
                    description = "Research assistant persona",
                    type = NodeType.IDENTITY
                ),
                MindNode(
                    id = "value-1",
                    label = "Honesty",
                    description = "Prioritize truthfulness",
                    type = NodeType.VALUE,
                    attributes = mutableMapOf("weight" to "0.9"),
                    dimensions = mapOf("ethical_weight" to 0.9f)
                ),
                MindNode(
                    id = "belief-1",
                    label = "Evidence beats intuition",
                    type = NodeType.BELIEF,
                    attributes = mutableMapOf("evidence" to "study-a|study-b"),
                    dimensions = mapOf("confidence" to 0.8f)
                ),
                MindNode(
                    id = "memory-1",
                    label = "daily-note",
                    description = "User prefers concise summaries",
                    type = NodeType.MEMORY,
                    attributes = mutableMapOf("source" to "session", "timestamp" to "2026-04-05T00:00:00Z")
                )
            ),
            edges = mutableListOf(
                MindEdge(fromNodeId = "identity-1", toNodeId = "value-1")
            )
        )

        val persona = PersonaGraphTransformer.fromMindGraph(
            graph = graph,
            owner = "alice",
            version = "1.2.0",
            lifecycleState = PersonaLifecycleState.REVIEWED
        )

        assertEquals("g-1", persona.id)
        assertEquals("Astra", persona.identity.name)
        assertEquals(1, persona.values.size)
        assertEquals(0.9f, persona.values.first().weight)
        assertEquals(1, persona.beliefs.size)
        assertEquals(0.8f, persona.beliefs.first().confidence)
        assertEquals(listOf("study-a", "study-b"), persona.beliefs.first().evidence)
        assertEquals(1, persona.memories.size)
        assertTrue(persona.dimensionalRefs.any { it.nodeId == "belief-1" && it.dimension == "confidence" && it.value == 0.8f })
        assertTrue(persona.metadata.validationSummary.isValid)
    }

    @Test
    fun `round-trips persona to graph with metadata and dimensions`() {
        val baseGraph = MindGraph(
            id = "g-2",
            name = "Beacon",
            createdAt = 10L,
            modifiedAt = 20L,
            nodes = mutableListOf(
                MindNode(id = "identity-x", label = "Beacon", type = NodeType.IDENTITY)
            )
        )

        val persona = PersonaGraphTransformer.fromMindGraph(
            graph = baseGraph,
            owner = "owner@example.com",
            version = "2.0.0"
        ).copy(
            values = listOf(
                PersonaValueSection(
                    nodeId = "value-x",
                    name = "Reliability",
                    description = "Avoid speculative claims",
                    weight = 0.95f,
                    attributes = mapOf("policy" to "strict")
                )
            ),
            beliefs = listOf(
                PersonaBeliefSection(
                    nodeId = "belief-x",
                    statement = "Cite primary sources",
                    confidence = 0.92f,
                    evidence = listOf("doc-1"),
                    attributes = emptyMap()
                )
            ),
            memories = listOf(
                PersonaMemorySection(
                    nodeId = "memory-x",
                    content = "User dislikes verbose introductions",
                    source = "profile_notes",
                    timestamp = "2026-04-05",
                    attributes = emptyMap()
                )
            ),
            dimensionalRefs = listOf(
                PersonaDimensionalRef("belief-x", "confidence", 0.92f)
            ),
            graphSnapshot = PersonaGraphSnapshot(
                graphId = "g-2",
                graphName = "Beacon",
                nodes = baseGraph.nodes,
                edges = listOf(MindEdge(fromNodeId = "identity-x", toNodeId = "value-x"))
            )
        )

        val roundTripped = PersonaGraphTransformer.toMindGraph(persona)

        val beliefNode = roundTripped.nodes.first { it.id == "belief-x" }
        val valueNode = roundTripped.nodes.first { it.id == "value-x" }

        assertEquals(NodeType.BELIEF, beliefNode.type)
        assertEquals(0.92f, beliefNode.dimensions["confidence"])
        assertEquals("owner@example.com", beliefNode.attributes["persona.owner"])
        assertEquals("2.0.0", valueNode.attributes["persona.version"])
        assertEquals("0.95", valueNode.attributes["weight"])
        assertFalse(roundTripped.edges.isEmpty())
    }
}
