package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.persona.runtime.ClassificationPrivacyConstraints
import com.kaleaon.mnxmindmaker.persona.runtime.FallbackStrategy
import com.kaleaon.mnxmindmaker.persona.runtime.InferenceParams
import com.kaleaon.mnxmindmaker.persona.runtime.PersonaDeploymentManifest
import com.kaleaon.mnxmindmaker.persona.runtime.RuntimeTarget
import com.kaleaon.mnxmindmaker.persona.runtime.ToolPolicy
import com.kaleaon.mnxmindmaker.repository.MnxRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the repository's full graph payload serializer/deserializer
 * preserves all graph-level, node-level, and edge-level fields.
 */
class MnxRepositoryGraphRoundTripTest {

    @Test
    fun `full graph payload round-trips through MNX raw section`() {
        val original = MindGraph(
            id = "graph-001",
            name = "Complete Graph",
            createdAt = 1711111111111L,
            modifiedAt = 1712222222222L,
            nodes = mutableListOf(
                MindNode(
                    id = "node-identity",
                    label = "Kalea",
                    type = NodeType.IDENTITY,
                    description = "Main identity node",
                    x = 400.5f,
                    y = 300.25f,
                    parentId = null,
                    attributes = mutableMapOf("role" to "self", "lang" to "en"),
                    isExpanded = false,
                    dimensions = mapOf("self_coherence" to 0.92f, "valence" to 0.31f)
                ),
                MindNode(
                    id = "node-belief",
                    label = "Truth matters",
                    type = NodeType.BELIEF,
                    description = "Epistemic anchor",
                    x = 150.75f,
                    y = 500.125f,
                    parentId = "node-identity",
                    attributes = mutableMapOf("priority" to "high"),
                    isExpanded = true,
                    dimensions = mapOf("confidence" to 0.81f, "revisability" to 0.22f)
                )
            ),
            edges = mutableListOf(
                MindEdge(
                    id = "edge-1",
                    fromNodeId = "node-identity",
                    toNodeId = "node-belief",
                    label = "supports",
                    strength = 0.77f
                )
            )
        )

        val payload = MnxRepository.serializeGraphPayload(original)
        assertTrue(payload.isNotEmpty())

        val encodedFile = MnxFile(
            header = MnxHeader(
                createdTimestamp = original.createdAt,
                modifiedTimestamp = original.modifiedAt
            ),
            sections = emptyMap(),
            rawSections = mapOf(MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE to payload)
        )
        val bytes = MnxCodec.encodeToBytes(encodedFile)
        val decodedFile = MnxCodec.decodeFromBytes(bytes)
        val recovered = MnxRepository.deserializeGraphPayload(
            decodedFile.rawSections[MnxRepository.GRAPH_PAYLOAD_SECTION_TYPE]!!
        )

        assertEquals(original.id, recovered.id)
        assertEquals(original.name, recovered.name)
        assertEquals(original.createdAt, recovered.createdAt)
        assertEquals(original.modifiedAt, recovered.modifiedAt)
        assertEquals(original.nodes, recovered.nodes)
        assertEquals(original.edges, recovered.edges)
    }

    @Test
    fun `persona manifest round-trips in META section with namespaced key`() {
        val manifest = PersonaDeploymentManifest(
            target = RuntimeTarget(provider = "openai", model = "gpt-4.2", runtime = "responses"),
            inference = InferenceParams(temperature = 0.22, maxTokens = 1200, contextWindow = 128000),
            toolPolicy = ToolPolicy(
                allowlist = listOf("file_read", "node_update", "memory_search"),
                policy = "allowlist_only"
            ),
            classification = ClassificationPrivacyConstraints(
                classification = "confidential",
                privacy = "hipaa_restricted"
            ),
            fallback = FallbackStrategy(mode = "model_failover", target = "gpt-4.1-mini", maxRetries = 2)
        )

        val meta = com.kaleaon.mnxmindmaker.mnx.MnxMeta(
            mapOf(
                "app" to "MnxMindMaker",
                MnxRepository.META_PERSONA_DEPLOYMENT_KEY to
                    MnxRepository.encodePersonaDeploymentManifest(manifest)
            )
        )
        val encodedMeta = MnxCodec.serializeMeta(meta)
        val decodedMeta = MnxCodec.deserializeMeta(encodedMeta)
        val recovered = MnxRepository.manifestFromMeta(decodedMeta)

        assertEquals(manifest, recovered)
    }

    @Test
    fun `persona manifest defaults when META key is missing`() {
        val meta = com.kaleaon.mnxmindmaker.mnx.MnxMeta(mapOf("app" to "MnxMindMaker"))
        val recovered = MnxRepository.manifestFromMeta(meta)
        assertEquals(PersonaDeploymentManifest.defaults(), recovered)
    }
}
