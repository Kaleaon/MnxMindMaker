package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxFormat
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that N-dimensional node coordinates survive a full
 * MNX encode → decode round-trip via DIMENSIONAL_REFS.
 */
class DimensionalRefsRoundTripTest {

    @Test
    fun `defaultDimensions returns correct axes per NodeType`() {
        // VALUES should have at least 7 named dimensions
        val valueDims = DimensionMapper.defaultDimensions(NodeType.VALUE)
        assertTrue("VALUES should have ≥ 7 dimensions", valueDims.size >= 7)
        assertTrue(valueDims.containsKey("ethical_weight"))
        assertTrue(valueDims.containsKey("social_impact"))
        assertTrue(valueDims.containsKey("personal_relevance"))
        assertTrue(valueDims.containsKey("priority"))
        assertTrue(valueDims.containsKey("universality"))
        assertTrue(valueDims.containsKey("actionability"))
        assertTrue(valueDims.containsKey("intrinsic_worth"))

        // BELIEF should have at least 7 named dimensions
        val beliefDims = DimensionMapper.defaultDimensions(NodeType.BELIEF)
        assertTrue("BELIEF should have ≥ 7 dimensions", beliefDims.size >= 7)
        assertTrue(beliefDims.containsKey("confidence"))
        assertTrue(beliefDims.containsKey("evidence_strength"))
        assertTrue(beliefDims.containsKey("emotional_loading"))
        assertTrue(beliefDims.containsKey("social_consensus"))

        // Every node type should have > 3 dimensions
        NodeType.entries.forEach { type ->
            val dims = DimensionMapper.defaultDimensions(type)
            assertTrue("$type must have > 3 dimensions, got ${dims.size}", dims.size > 3)
        }
    }

    @Test
    fun `resolve merges caller overrides with defaults`() {
        val node = MindNode(
            label = "Honesty",
            type = NodeType.VALUE,
            dimensions = mapOf("ethical_weight" to 0.95f, "custom_axis" to 0.1f)
        )
        val resolved = DimensionMapper.resolve(node)

        // Caller-supplied value wins
        assertEquals(0.95f, resolved["ethical_weight"])
        // Default value filled in for missing axes
        assertEquals(0.5f, resolved["social_impact"])
        // Extra caller-supplied axis is also present
        assertEquals(0.1f, resolved["custom_axis"])
    }

    @Test
    fun `buildDimensionalRefs and restoreDimensions are inverses`() {
        val nodes = listOf(
            MindNode(id = "n1", label = "Honesty", type = NodeType.VALUE,
                dimensions = mapOf("ethical_weight" to 0.9f, "priority" to 0.8f)),
            MindNode(id = "n2", label = "Joy", type = NodeType.AFFECT,
                dimensions = mapOf("valence" to 0.7f, "arousal" to 0.6f))
        )

        val refs = DimensionMapper.buildDimensionalRefs(nodes)
        val restored = DimensionMapper.restoreDimensions(refs)

        // n1: caller-supplied value 0.9f should be preserved
        assertEquals(0.9f, restored["n1"]!!["ethical_weight"])
        assertEquals(0.8f, restored["n1"]!!["priority"])

        // n2: caller-supplied value 0.7f should be preserved
        assertEquals(0.7f, restored["n2"]!!["valence"])
        assertEquals(0.6f, restored["n2"]!!["arousal"])
    }

    @Test
    fun `DIMENSIONAL_REFS section round-trips through MnxCodec`() {
        val nodes = listOf(
            MindNode(id = "v1", label = "Truth", type = NodeType.VALUE,
                dimensions = mapOf("ethical_weight" to 0.95f, "universality" to 0.88f,
                    "social_impact" to 0.7f, "personal_relevance" to 0.85f,
                    "priority" to 0.9f, "actionability" to 0.6f, "intrinsic_worth" to 1.0f)),
            MindNode(id = "b1", label = "Determinism", type = NodeType.BELIEF,
                dimensions = mapOf("confidence" to 0.6f, "evidence_strength" to 0.55f,
                    "emotional_loading" to 0.3f, "social_consensus" to 0.4f,
                    "revisability" to 0.7f, "centrality" to 0.5f, "acquired_recency" to 0.8f))
        )

        val dimRefs = DimensionMapper.buildDimensionalRefs(nodes)

        // Serialize section payload
        val payload = MnxCodec.serializeDimensionalRefs(dimRefs)
        assertTrue("Payload must not be empty", payload.isNotEmpty())

        // Wrap in a minimal MnxFile and encode/decode the whole file
        val sections = mapOf(MnxFormat.MnxSectionType.DIMENSIONAL_REFS to payload)
        val original = MnxFile(header = MnxHeader(), sections = sections)
        val bytes = MnxCodec.encodeToBytes(original)
        val decoded = MnxCodec.decodeFromBytes(bytes)

        // Deserialize the recovered payload
        val recoveredRefs = MnxCodec.deserializeDimensionalRefs(
            decoded.sections[MnxFormat.MnxSectionType.DIMENSIONAL_REFS]!!
        )
        val restored = DimensionMapper.restoreDimensions(recoveredRefs)

        // VALUES node — all 7 dimensions must survive
        assertEquals(0.95f, restored["v1"]!!["ethical_weight"]!!, 1e-5f)
        assertEquals(0.88f, restored["v1"]!!["universality"]!!, 1e-5f)
        assertEquals(1.0f,  restored["v1"]!!["intrinsic_worth"]!!, 1e-5f)

        // BELIEF node — all 7 dimensions must survive
        assertEquals(0.6f,  restored["b1"]!!["confidence"]!!, 1e-5f)
        assertEquals(0.7f,  restored["b1"]!!["revisability"]!!, 1e-5f)

        // Total refs count = sum of all resolved dims across all nodes
        val expectedCount = nodes.sumOf { DimensionMapper.resolve(it).size }
        assertEquals(expectedCount, recoveredRefs.refs.size)
    }

    @Test
    fun `every NodeType has more than 3 dimensions`() {
        NodeType.entries.forEach { type ->
            val count = DimensionMapper.defaultDimensionNames(type).size
            assertTrue(
                "$type has only $count dimensions — requirement is > 3",
                count > 3
            )
        }
    }
}
