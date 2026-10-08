package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.interchange.MindInterchangeFormat
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DataMapper
import com.kaleaon.mnxmindmaker.util.FileImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataMapperInterchangeTest {
    private fun graph() = MindGraph(
        id = "knowledge-test",
        name = "Shared Knowledge",
        nodes = mutableListOf(
            MindNode(id = "concept", label = "Apple", type = NodeType.KNOWLEDGE,
                dimensions = mapOf("confidence" to 0.8f)),
            MindNode(id = "source", label = "Fixture", type = NodeType.MEMORY,
                attributes = mutableMapOf("knowledge_record" to "{\"uri\":\"urn:test\"}"))
        ),
        edges = mutableListOf(MindEdge(id = "edge", fromNodeId = "concept", toNodeId = "source",
            label = "evidence"))
    )

    @Test
    fun `native interchange is preserved rather than heuristically remapped`() {
        val graph = graph()
        val imported = DataMapper.fromJson(MindInterchangeFormat.exportJson(graph), "Ignored name")
        assertEquals(graph, imported)
    }

    @Test
    fun `file importer accepts pasted interchange and explicit json`() {
        val graph = graph()
        val json = MindInterchangeFormat.exportJson(graph)
        assertEquals(graph, FileImporter.parseText(json, FileImporter.Format.UNKNOWN))
        assertEquals(graph, FileImporter.parseText(json, FileImporter.Format.JSON))
    }

    @Test
    fun `invalid native envelope does not fall through to generic mapping`() {
        val json = """{"schema":{"family":"mnx.interchange","version":{"major":99,"minor":0}}}"""
        val error = runCatching { DataMapper.fromJson(json) }.exceptionOrNull()
        assertTrue(error is MindInterchangeFormat.ValidationException)
    }

    @Test
    fun `ordinary json continues using the existing mapper`() {
        val graph = DataMapper.fromJson("""{"knowledge":{"label":"Fruit","description":"Example"}}""")
        assertTrue(graph.nodes.isNotEmpty())
        assertTrue(graph.nodes.any { it.type == NodeType.KNOWLEDGE })
    }

    @Test
    fun `knowledge core projection preserves sensory records evidence and vectors`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/knowledge-core-seed.mnxj"))
        val json = stream.bufferedReader().use { it.readText() }
        val graph = FileImporter.parseText(json, FileImporter.Format.JSON)
        assertEquals(11, graph.nodes.size)
        assertEquals(15, graph.edges.size)
        val observation = graph.nodes.first { it.attributes["modality"] == "shape" }
        assertTrue(observation.attributes.getValue("knowledge_record").contains("embedding"))
        assertTrue(observation.dimensions.isEmpty())
        assertTrue(graph.edges.any { it.label.startsWith("evidence:") })
        val exported = MindInterchangeFormat.exportJson(graph)
        assertEquals(graph, MindInterchangeFormat.importJson(exported))
    }
}
