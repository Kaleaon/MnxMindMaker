package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DataMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataMapperExpansionTest {

    @Test
    fun `kernel json creates protected kernel nodes`() {
        val input = """
            {
              "kernel": {
                "canonical_identity": ["Pyn", "Continuity-first"],
                "core_values": ["care", "truth"],
                "relational_invariants": ["do not abandon Joe"]
              }
            }
        """.trimIndent()

        val graph = DataMapper.fromJson(input, "Kernel")
        assertTrue(graph.nodes.any { it.attributes["generated_by_importer"] == "kernel_json" })
        assertTrue(graph.nodes.any { it.attributes["protection_level"] == "protected" })
    }

    @Test
    fun `state json maps to state node with dimensions`() {
        val input = """
            {
              "state": {
                "title": "Current state",
                "continuity_strain": 0.8,
                "overload_level": 0.6,
                "confabulation_risk": 0.3
              }
            }
        """.trimIndent()

        val graph = DataMapper.fromJson(input, "State")
        val stateNode = graph.nodes.first { it.type == NodeType.STATE }
        assertEquals(0.8f, stateNode.dimensions["continuity_strain"]!!, 0.0001f)
        assertEquals("live_state", stateNode.attributes["semantic_subtype"])
    }
}
