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

    @Test
    fun `wake up context emits L0 and L1 sections in deterministic order`() {
        val graph = MindGraph(
            nodes = mutableListOf(
                MindNode(id = "z", label = "Project Zebra", type = NodeType.MEMORY, attributes = mutableMapOf("semantic_subtype" to "project")),
                MindNode(id = "i", label = "Identity Anchor", type = NodeType.IDENTITY, attributes = mutableMapOf("current_relevance" to "0.3")),
                MindNode(id = "v", label = "Autonomy", type = NodeType.VALUE, attributes = mutableMapOf("current_relevance" to "0.9")),
                MindNode(id = "d", label = "No Deception", type = NodeType.DRIFT_RULE),
                MindNode(id = "p", label = "Concise tone", type = NodeType.PERSONALITY),
                MindNode(id = "l", label = "Evergreen roadmap", type = NodeType.KNOWLEDGE, attributes = mutableMapOf("horizon" to "long"))
            )
        )

        val context = BootPacketGenerator.generateWakeUpContext(graph)

        assertTrue(context.contains("L0: identity/core values/core constraints"))
        assertTrue(context.contains("L1: stable preferences/projects/long-horizon context"))

        val identityIndex = context.indexOf("- [identity] Identity Anchor")
        val valueIndex = context.indexOf("- [value] Autonomy")
        val driftIndex = context.indexOf("- [drift_rule] No Deception")
        assertTrue(identityIndex in 0 until valueIndex)
        assertTrue(valueIndex in 0 until driftIndex)
    }

    @Test
    fun `wake up context truncates lower priority content first by token budget`() {
        val graph = MindGraph(
            nodes = mutableListOf(
                MindNode(label = "Core Identity", type = NodeType.IDENTITY, description = "A".repeat(120)),
                MindNode(label = "Core Value", type = NodeType.VALUE, description = "B".repeat(120)),
                MindNode(label = "Big Project", type = NodeType.MEMORY, description = "C".repeat(220), attributes = mutableMapOf("semantic_subtype" to "project"))
            )
        )

        val context = BootPacketGenerator.generateWakeUpContext(
            graph,
            BootPacketGenerator.WakeUpTokenBudget(l0Tokens = 20, l1Tokens = 12)
        )

        assertTrue(context.contains("- [identity] Core Identity"))
        assertTrue(context.contains("…"))
    fun `boot packet retrieval payload retains chunk metadata attributes`() {
        val sessionChunk = MindNode(
            id = "memory-chunk-1",
            label = "Assistant chunk",
            type = NodeType.MEMORY,
            description = "Partial response chunk",
            attributes = mutableMapOf(
                "conversation_id" to "conv-abc",
                "turn_index" to "5",
                "chunk_span" to "5:0-128",
                "source" to "assistant",
                "current_relevance" to "0.95"
            )
        )
        val graph = MindGraph(nodes = mutableListOf(sessionChunk))

        val packet = BootPacketGenerator.generate(
            graph = graph,
            mode = BootPacketGenerator.Mode.FULL,
            prompt = "assistant chunk",
            task = "audit retrieval payload"
        )
        val json = packet.toJson()

        assertTrue(packet.memorySlice.any { it.id == "memory-chunk-1" })
        assertTrue(json.contains("\"conversation_id\": \"conv-abc\""))
        assertTrue(json.contains("\"turn_index\": \"5\""))
        assertTrue(json.contains("\"chunk_span\": \"5:0-128\""))
        assertTrue(json.contains("\"source\": \"assistant\""))
    }
}
