package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.memory.MemoryRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRoutingTest {

    @Test
    fun `inferRoute maps preference prompts to self preference wing`() {
        val route = MemoryRouting.inferRoute(prompt = "What tone and style should I use?", task = "reply")

        assertEquals("self", route.memoryWing)
        assertEquals("preferences", route.memoryHall)
        assertEquals("voice_style", route.memoryRoom)
        assertTrue(route.intentTokens.contains("tone"))
    }

    @Test
    fun `tierCandidates prioritizes direct wing hall room matches`() {
        val route = MemoryRouting.inferRoute(prompt = "Need deployment plan", task = "ship release")
        val primary = MindNode(
            id = "primary",
            label = "Deploy runbook",
            type = NodeType.MEMORY,
            attributes = mutableMapOf(
                MemoryRouting.KEY_MEMORY_WING to "execution",
                MemoryRouting.KEY_MEMORY_HALL to "project",
                MemoryRouting.KEY_MEMORY_ROOM to "runbook"
            )
        )
        val secondary = MindNode(
            id = "secondary",
            label = "General plan",
            type = NodeType.MEMORY,
            attributes = mutableMapOf(
                MemoryRouting.KEY_MEMORY_WING to "planning",
                MemoryRouting.KEY_MEMORY_HALL to "general",
                MemoryRouting.KEY_MEMORY_ROOM to "notes"
            )
        )
        val fallback = MindNode(
            id = "fallback",
            label = "Unrelated",
            type = NodeType.MEMORY,
            attributes = mutableMapOf(
                MemoryRouting.KEY_MEMORY_WING to "history",
                MemoryRouting.KEY_MEMORY_HALL to "events",
                MemoryRouting.KEY_MEMORY_ROOM to "timeline"
            )
        )

        val tiered = MemoryRouting.tierCandidates(listOf(fallback, secondary, primary), route)

        assertEquals(listOf("primary"), tiered.primary.map { it.id })
        assertEquals(listOf("secondary"), tiered.secondary.map { it.id })
        assertEquals(listOf("fallback"), tiered.fallback.map { it.id })
    }
}
