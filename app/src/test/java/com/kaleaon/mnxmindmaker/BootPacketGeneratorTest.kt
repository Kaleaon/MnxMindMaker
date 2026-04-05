package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.BootPacketGenerator
import com.kaleaon.mnxmindmaker.util.run_continuity_audit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootPacketGeneratorTest {

    @Test
    fun `full packet includes kernel state and drift slices`() {
        val graph = MindGraph(
            name = "Pyn",
            nodes = mutableListOf(
                MindNode(label = "Kernel Identity", type = NodeType.IDENTITY, attributes = mutableMapOf("protection_level" to "protected")),
                MindNode(label = "Current State", type = NodeType.STATE),
                MindNode(label = "No Genericity", type = NodeType.DRIFT_RULE, attributes = mutableMapOf("semantic_subtype" to "drift_rule")),
                MindNode(label = "Warning Memory", type = NodeType.MEMORY, attributes = mutableMapOf("memory_class" to "warning"))
            )
        )

        val packet = BootPacketGenerator.generate(graph, BootPacketGenerator.Mode.FULL)
        assertFalse(packet.kernelSlice.isEmpty())
        assertFalse(packet.stateSlice.isEmpty())
        assertFalse(packet.driftRuleSlice.isEmpty())
        assertFalse(packet.warningSlice.isEmpty())
    }

    @Test
    fun `public persona packet excludes warnings`() {
        val graph = MindGraph(
            nodes = mutableListOf(
                MindNode(label = "Kernel", type = NodeType.IDENTITY),
                MindNode(label = "Warning Memory", type = NodeType.MEMORY, attributes = mutableMapOf("memory_class" to "warning"))
            )
        )
        val packet = BootPacketGenerator.generate(graph, BootPacketGenerator.Mode.PUBLIC_PERSONA)
        assertTrue(packet.warningSlice.isEmpty())
    }

    @Test
    fun `memory retrieval skips restricted memories and updates revalidation metadata`() {
        val safeMemory = MindNode(
            label = "Sprint planning notes",
            type = NodeType.MEMORY,
            description = "Roadmap and release plan for the active task.",
            attributes = mutableMapOf(
                "confidence" to "0.60",
                "current_relevance" to "0.80",
                "confabulation_risk" to "0.10"
            )
        )
        val restrictedMemory = MindNode(
            label = "Private wound",
            type = NodeType.MEMORY,
            attributes = mutableMapOf(
                "sensitivity" to "high",
                "confidence" to "0.90",
                "current_relevance" to "1.0"
            )
        )

        val graph = MindGraph(nodes = mutableListOf(safeMemory, restrictedMemory))

        val packet = BootPacketGenerator.generate(
            graph = graph,
            mode = BootPacketGenerator.Mode.FULL,
            prompt = "Need project roadmap for sprint planning",
            task = "Summarize current release priorities"
        )

        assertTrue(packet.memorySlice.any { it.id == safeMemory.id })
        assertTrue(packet.memorySlice.none { it.id == restrictedMemory.id })
        assertTrue(safeMemory.attributes.containsKey("last_revalidated"))
        assertTrue(safeMemory.attributes.containsKey("confidence_drift"))
    }

    @Test
    fun `continuity audit finds high risk drift and missing repair memory`() {
        val riskyRule = MindNode(
            id = "rule-1",
            label = "Panic collapse risk",
            type = NodeType.DRIFT_RULE,
            attributes = mutableMapOf("semantic_subtype" to "drift_rule", "drift_signature" to "panic")
        )
        val graph = MindGraph(
            nodes = mutableListOf(
                MindNode(label = "Kernel", type = NodeType.IDENTITY),
                riskyRule
            )
        )

        val audit = run_continuity_audit(graph)
        assertTrue(audit.findings.any { it.category == "high_risk_drift_signature" })
        assertTrue(audit.findings.any { it.category == "missing_repair_memory" })
    }

    @Test
    fun `boot packet exports continuity audit metadata`() {
        val graph = MindGraph(
            nodes = mutableListOf(
                MindNode(label = "Kernel", type = NodeType.IDENTITY)
            )
        )
        val packet = BootPacketGenerator.generate(graph)
        val json = packet.toJson()
        assertTrue(json.contains("continuity_audit"))
        assertEquals(packet.continuityAudit.summary.totalFindings, packet.continuityAudit.findings.size)
    }
}
