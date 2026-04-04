package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.BootPacketGenerator
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
}
