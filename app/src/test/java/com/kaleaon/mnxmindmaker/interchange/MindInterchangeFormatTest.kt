package com.kaleaon.mnxmindmaker.interchange

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MindInterchangeFormatTest {

    private fun sampleGraph(): MindGraph = MindGraph(
        id = "graph-stable-1",
        name = "Stable Neutral Export",
        createdAt = 1710000000000,
        modifiedAt = 1710000010000,
        nodes = mutableListOf(
            MindNode(
                id = "n1",
                label = "Identity",
                type = NodeType.IDENTITY,
                x = 100f,
                y = 120f,
                attributes = mutableMapOf("domain" to "neutral"),
                dimensions = mapOf("confidence" to 0.9f)
            ),
            MindNode(
                id = "n2",
                label = "Belief",
                type = NodeType.BELIEF,
                parentId = "n1",
                x = 300f,
                y = 120f,
                attributes = mutableMapOf("scope" to "global"),
                dimensions = mapOf("revisability" to 0.2f)
            )
        ),
        edges = mutableListOf(
            MindEdge(
                id = "e1",
                fromNodeId = "n1",
                toNodeId = "n2",
                label = "contains",
                strength = 0.8f
            )
        )
    )

    @Test
    fun `json format round-trips with compatibility hooks`() {
        val graph = sampleGraph()
        val json = MindInterchangeFormat.exportJson(
            graph = graph,
            compatibilityHooks = MindInterchangeFormat.CompatibilityHooks(
                minReaderVersion = MindInterchangeFormat.SchemaVersion(1, 0),
                forwardCompatExtensions = listOf("ext.telemetry.v1"),
                migrationHint = "ignore_unknown_extensions"
            ),
            metadata = mapOf("source" to "unit-test")
        )

        assertTrue(json.contains("\"forward_compat_extensions\""))
        val recovered = MindInterchangeFormat.importJson(json)
        assertEquals(graph, recovered)
    }

    @Test
    fun `bundle format round-trips graph metadata and binary blobs`() {
        val graph = sampleGraph()
        val payload = MindInterchangeFormat.BundlePayload(
            graph = graph,
            metadata = mapOf("origin" to "test-suite"),
            blobs = mapOf(
                "attachments/readme.txt" to "hello".toByteArray(),
                "weights/matrix.bin" to byteArrayOf(1, 2, 3, 4)
            )
        )

        val bundleBytes = MindInterchangeFormat.exportBundle(payload)
        val imported = MindInterchangeFormat.importBundle(bundleBytes)

        assertEquals(graph, imported.graph)
        assertEquals("test-suite", imported.metadata["origin"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), imported.blobs["weights/matrix.bin"])
        assertArrayEquals("hello".toByteArray(), imported.blobs["attachments/readme.txt"])
    }

    @Test
    fun `validation rejects unknown future major version`() {
        val json = """
            {
              "schema": {
                "family": "mnx.interchange",
                "version": {"major": 99, "minor": 0}
              },
              "compatibility": {
                "min_reader_version": {"major": 1, "minor": 0},
                "forward_compat_extensions": []
              },
              "metadata": {},
              "graph": {
                "id": "g1",
                "name": "g",
                "created_at": 1,
                "modified_at": 2,
                "nodes": [],
                "edges": []
              }
            }
        """.trimIndent()

        val ex = runCatching { MindInterchangeFormat.validateJson(json) }.exceptionOrNull()
        assertTrue(ex is MindInterchangeFormat.ValidationException)
        assertTrue(ex!!.message!!.contains("Unsupported major schema version"))
    }

    @Test
    fun `validation rejects dangling edge references`() {
        val json = """
            {
              "schema": {
                "family": "mnx.interchange",
                "version": {"major": 1, "minor": 0}
              },
              "compatibility": {
                "min_reader_version": {"major": 1, "minor": 0},
                "forward_compat_extensions": []
              },
              "metadata": {},
              "graph": {
                "id": "g1",
                "name": "g",
                "created_at": 1,
                "modified_at": 2,
                "nodes": [
                    {
                        "id": "a",
                        "label": "A",
                        "type": "IDENTITY",
                        "description": "",
                        "x": 0,
                        "y": 0,
                        "attributes": {},
                        "is_expanded": true,
                        "dimensions": {}
                    }
                ],
                "edges": [
                    {
                        "id": "e1",
                        "from_node_id": "a",
                        "to_node_id": "missing",
                        "label": "",
                        "strength": 1
                    }
                ]
              }
            }
        """.trimIndent()

        val ex = runCatching { MindInterchangeFormat.validateJson(json) }.exceptionOrNull()
        assertTrue(ex is MindInterchangeFormat.ValidationException)
        assertTrue(ex!!.message!!.contains("must reference an existing node"))
    }

    @Test
    fun `bundle import rejects missing manifest`() {
        val graphJson = MindInterchangeFormat.exportJson(sampleGraph())
        val bytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry(MindInterchangeFormat.BUNDLE_GRAPH_PATH))
                zip.write(graphJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            baos.toByteArray()
        }

        val error = runCatching { MindInterchangeFormat.importBundle(bytes) }.exceptionOrNull()
        assertTrue(error is MindInterchangeFormat.ValidationException)
        assertTrue(error!!.message!!.contains("Bundle missing manifest.json"))
    }

    @Test
    fun `bundle import rejects missing graph payload`() {
        val bytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry(MindInterchangeFormat.BUNDLE_MANIFEST_PATH))
                zip.write(
                    """
                    {"schema_family":"mnx.interchange","schema_version":{"major":1,"minor":0},"entries":[]}
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }
            baos.toByteArray()
        }

        val error = runCatching { MindInterchangeFormat.importBundle(bytes) }.exceptionOrNull()
        assertTrue(error is MindInterchangeFormat.ValidationException)
        assertTrue(error!!.message!!.contains("Bundle missing payload/graph.json"))
    }
}
