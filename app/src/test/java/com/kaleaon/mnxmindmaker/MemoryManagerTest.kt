package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.memory.MemoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryManagerTest {

    @Test
    fun `session only policy excludes persistent semantic memories`() {
        val manager = MemoryManager()
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.SESSION_ONLY)
        )

        manager.appendSessionTurn(
            MemoryManager.SessionTurn(role = "user", content = "Need launch checklist for deployment")
        )
        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-1",
                label = "Deploy checklist",
                type = NodeType.MEMORY,
                description = "Persistent launch runbook",
                attributes = mutableMapOf("timestamp" to System.currentTimeMillis().toString(), "current_relevance" to "0.9")
            )
        )

        val retrieved = manager.retrieveForPromptInjection(
            prompt = "deployment checklist",
            task = "prepare launch",
            limit = 10
        )

        assertTrue(retrieved.any { it.attributes["semantic_subtype"] == "session" })
        assertFalse(retrieved.any { it.id == "semantic-1" })
    }

    @Test
    fun `persistent policy applies sensitivity and supports edit delete`() {
        val manager = MemoryManager()
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.PERSISTENT)
        )

        manager.upsertSemanticMemory(
            MindNode(
                id = "memory-safe",
                label = "Writing tone preference",
                type = NodeType.MEMORY,
                description = "Use concise, technical style.",
                attributes = mutableMapOf(
                    "timestamp" to System.currentTimeMillis().toString(),
                    "current_relevance" to "0.9",
                    "sensitivity" to "low"
                )
            )
        )
        manager.upsertSemanticMemory(
            MindNode(
                id = "memory-sensitive",
                label = "Private credential note",
                type = NodeType.MEMORY,
                description = "Highly sensitive info",
                attributes = mutableMapOf(
                    "timestamp" to System.currentTimeMillis().toString(),
                    "current_relevance" to "1.0",
                    "sensitivity" to "restricted"
                )
            )
        )

        val edited = manager.editMemory("memory-safe") { node ->
            node.copy(description = "Use concise and direct style.")
        }
        assertTrue(edited)

        val retrieved = manager.retrieveForPromptInjection(
            prompt = "What writing style should I use?",
            task = "answer in user preference",
            limit = 10
        )

        assertTrue(retrieved.any { it.id == "memory-safe" })
        assertFalse(retrieved.any { it.id == "memory-sensitive" })

        assertTrue(manager.deleteMemory("memory-safe"))
        val afterDelete = manager.retrieveForPromptInjection(
            prompt = "writing style",
            task = "respond",
            limit = 10
        )
        assertFalse(afterDelete.any { it.id == "memory-safe" })
    }

    @Test
    fun `auto expiry purges memories by category`() {
        val manager = MemoryManager()
        val now = 50_000L
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(
                mode = MemoryManager.MemoryPolicyMode.PERSISTENT,
                expiryByCategoryMs = mapOf(
                    MemoryManager.MemoryCategory.SESSION to 1000L,
                    MemoryManager.MemoryCategory.SEMANTIC to 1000L,
                    MemoryManager.MemoryCategory.PROFILE to 1000L
                )
            )
        )

        manager.appendSessionTurn(
            MemoryManager.SessionTurn(role = "user", content = "old session", timestampMs = 1_000L)
        )
        manager.upsertProfileMemory(
            key = "tone",
            value = "friendly",
            timestampMs = 1_000L
        )
        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-old",
                label = "Old semantic",
                type = NodeType.MEMORY,
                description = "stale",
                attributes = mutableMapOf("timestamp" to "1000", "current_relevance" to "0.8")
            )
        )

        val retrieved = manager.retrieveForPromptInjection(
            prompt = "any",
            task = "any",
            limit = 10,
            nowEpochMs = now
        )

        assertEquals(0, retrieved.size)
    }
}
