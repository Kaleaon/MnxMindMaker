package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.util.memory.MemoryManager
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryManagerTest {
    private class RecordingStore : MemoryManager.MemoryPersistenceStore {
        val deletedByCategory = ConcurrentHashMap<MemoryManager.MemoryCategory, MutableList<String>>()
        override fun deleteExpired(category: MemoryManager.MemoryCategory, memoryIds: List<String>) {
            deletedByCategory.computeIfAbsent(category) { mutableListOf() }.addAll(memoryIds)
        }
    }

    private class RecordingTelemetry : MemoryManager.MemoryExpiryTelemetry {
        val events = mutableListOf<Triple<MemoryManager.MemoryCategory, Int, Int>>()
        override fun onExpiredRemoved(
            category: MemoryManager.MemoryCategory,
            removedCount: Int,
            malformedTimestampCount: Int
        ) {
            events.add(Triple(category, removedCount, malformedTimestampCount))
        }
    }

    private class RemoteOnlyEmbeddingProvider : MemoryManager.EmbeddingProvider {
        override val id: String = "remote-mock"
        override val isLocal: Boolean = false

        override fun embed(input: String): Map<String, Float> = mapOf("remote-token" to 1f)
    }

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
    fun `persistent policy supports edit delete`() {
        val manager = MemoryManager()
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.PERSISTENT)
        )

        manager.upsertSemanticMemory(
            MindNode(
                id = "memory-safe",
                label = "Writing tone preference",
                type = NodeType.MEMORY,
                description = "Use concise technical style",
                attributes = mutableMapOf(
                    "timestamp" to System.currentTimeMillis().toString(),
                    "current_relevance" to "0.9",
                    "sensitivity" to "low"
                )
            )
        )

        val edited = manager.editMemory("memory-safe") { node ->
            node.copy(description = "Use concise and direct style")
        }
        assertTrue(edited)

        val retrieved = manager.retrieveForPromptInjection(
            prompt = "What writing style should I use?",
            task = "answer in user preference",
            limit = 10
        )

        assertTrue(retrieved.any { it.id == "memory-safe" })
        assertTrue(manager.deleteMemory("memory-safe"))
        val afterDelete = manager.retrieveForPromptInjection(
            prompt = "writing style",
            task = "respond",
            limit = 10
        )
        assertFalse(afterDelete.any { it.id == "memory-safe" })
    }

    @Test
    fun `auto expiry purges memories by category and keeps fresh entries`() {
        val store = RecordingStore()
        val telemetry = RecordingTelemetry()
        val manager = MemoryManager(store, telemetry)
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
        manager.appendSessionTurn(
            MemoryManager.SessionTurn(role = "user", content = "fresh session", timestampMs = 49_500L)
        )
        manager.upsertProfileMemory(key = "tone-old", value = "friendly", timestampMs = 1_000L)
        manager.upsertProfileMemory(key = "tone-fresh", value = "direct", timestampMs = 49_900L)
        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-old",
                label = "Old semantic",
                type = NodeType.MEMORY,
                description = "stale",
                attributes = mutableMapOf("timestamp" to "1000", "current_relevance" to "0.8")
            )
        )
        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-fresh",
                label = "Fresh semantic",
                type = NodeType.MEMORY,
                description = "fresh",
                attributes = mutableMapOf("timestamp" to "49500", "current_relevance" to "0.8")
            )
        )

        val retrieved = manager.retrieveForPromptInjection(
            prompt = "fresh",
            task = "any",
            limit = 10,
            nowEpochMs = now
        )

        assertFalse(retrieved.any { it.id == "semantic-old" || it.id == "tone-old" || it.description == "old session" })
        assertTrue(retrieved.any { it.id == "semantic-fresh" })
        assertTrue(retrieved.any { it.id == "tone-fresh" })
        assertEquals(listOf("tone-old"), store.deletedByCategory[MemoryManager.MemoryCategory.PROFILE])
        assertEquals(listOf("semantic-old"), store.deletedByCategory[MemoryManager.MemoryCategory.SEMANTIC])
        assertEquals(1, store.deletedByCategory[MemoryManager.MemoryCategory.SESSION]?.size)
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.SESSION, 1, 0)))
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.PROFILE, 1, 0)))
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.SEMANTIC, 1, 0)))
    }

    @Test
    fun `embedding cache invalidates when semantic node content changes`() {
        val manager = MemoryManager()
        manager.setPolicy(MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.PERSISTENT))

        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-cache",
                label = "Project plan",
                type = NodeType.MEMORY,
                description = "v1 roadmap",
                attributes = mutableMapOf("timestamp" to "1000")
            )
        )

        val initialFingerprint = manager.getMemory("semantic-cache")
            ?.attributes
            ?.get("embedding_cache_fingerprint")

        manager.editMemory("semantic-cache") { current ->
            current.copy(description = "v2 roadmap with updates")
        }

        val updatedFingerprint = manager.getMemory("semantic-cache")
            ?.attributes
            ?.get("embedding_cache_fingerprint")

        assertTrue(initialFingerprint != null)
        assertTrue(updatedFingerprint != null)
        assertTrue(initialFingerprint != updatedFingerprint)
    }

    @Test
    fun `strict local privacy blocks remote embedding provider fallback`() {
        val manager = MemoryManager(
            embeddingPolicy = MemoryManager.EmbeddingPolicy(
                privacyMode = PrivacyMode.STRICT_LOCAL_ONLY,
                preferLocal = false
            ),
            remoteEmbeddingProvider = RemoteOnlyEmbeddingProvider()
        )
        manager.setPolicy(MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.PERSISTENT))

        manager.upsertSemanticMemory(
            MindNode(
                id = "privacy-local",
                label = "Private key handling",
                type = NodeType.MEMORY,
                description = "Never send off-device",
                attributes = mutableMapOf("timestamp" to "1000")
            )
        )

        val provider = manager.getMemory("privacy-local")?.attributes?.get("embedding_provider")
        assertEquals("local-bow", provider)
    }
}
