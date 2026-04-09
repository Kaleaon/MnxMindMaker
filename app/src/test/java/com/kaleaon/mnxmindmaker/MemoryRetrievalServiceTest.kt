package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalServiceTest {

    @Test
    fun `retrieve prioritizes route tiers before score and applies deterministic ordering`() {
        val context = MemoryRetrievalService.RetrievalContext(
            prompt = "room:apollo hall:red wing:zenith",
            task = "planning"
        )

        val memories = listOf(
            memory("z-global", relevance = "0.95", confidence = "0.95"),
            memory("a-room", room = "apollo", relevance = "0.40", confidence = "0.40"),
            memory("b-hall", hall = "red", relevance = "0.90", confidence = "0.90"),
            memory("c-wing-2", wing = "zenith", relevance = "0.60", confidence = "0.60"),
            memory("c-wing-1", wing = "zenith", relevance = "0.60", confidence = "0.60")
        )

        val retrieved = MemoryRetrievalService.retrieve(memories, context, limit = 5)

        assertEquals(
            listOf("a-room", "b-hall", "c-wing-1", "c-wing-2", "z-global"),
            retrieved.map { it.id }
        )
    }

    @Test
    fun `retrieve keeps sensitivity gating before final result emission`() {
        val context = MemoryRetrievalService.RetrievalContext(
            prompt = "room:apollo",
            task = "planning"
        )

        val memories = listOf(
            memory("restricted-room", room = "apollo", relevance = "0.95", sensitivity = "restricted"),
            memory("safe-global", relevance = "0.50", sensitivity = "low")
        )

        val retrieved = MemoryRetrievalService.retrieveForPromptInjection(memories, context, limit = 5)

        assertFalse(retrieved.any { it.id == "restricted-room" })
        assertTrue(retrieved.any { it.id == "safe-global" })
    }

    private fun memory(
        id: String,
        room: String? = null,
        hall: String? = null,
        wing: String? = null,
        relevance: String = "0.75",
        confidence: String = "0.75",
        sensitivity: String = "low"
    ): MindNode {
        val attributes = mutableMapOf(
            "current_relevance" to relevance,
            "confidence" to confidence,
            "sensitivity" to sensitivity,
            "timestamp" to System.currentTimeMillis().toString()
        )
        room?.let { attributes["room"] = it }
        hall?.let { attributes["hall"] = it }
        wing?.let { attributes["wing"] = it }

        return MindNode(
            id = id,
            label = id,
            type = NodeType.MEMORY,
            description = "memory-$id",
            attributes = attributes
        )
    }
}
