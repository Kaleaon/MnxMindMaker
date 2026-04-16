package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.memory.MemoryManager
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
                ),
                maintenancePolicy = MemoryManager.MemoryMaintenancePolicy(
                    enabled = false
                )
            )
        )

        manager.appendSessionTurn(
            MemoryManager.SessionTurn(role = "user", content = "old session", timestampMs = 1_000L)
        )
        manager.appendSessionTurn(
            MemoryManager.SessionTurn(role = "user", content = "fresh session", timestampMs = 49_500L)
        )
        manager.upsertProfileMemory(
            key = "tone-old",
            value = "friendly",
            timestampMs = 1_000L
        )
        manager.upsertProfileMemory(
            key = "tone-fresh",
            value = "direct",
            timestampMs = 49_900L
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
        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-fresh",
                label = "Fresh semantic",
                type = NodeType.MEMORY,
                description = "fresh",
                attributes = mutableMapOf("timestamp" to "49500", "current_relevance" to "0.8")
            )
        )

        manager.runMaintenance(now)

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
        assertEquals(1, manager.getExpiryPurgeCounters()[MemoryManager.MemoryCategory.SESSION])
        assertEquals(1, manager.getExpiryPurgeCounters()[MemoryManager.MemoryCategory.PROFILE])
        assertEquals(1, manager.getExpiryPurgeCounters()[MemoryManager.MemoryCategory.SEMANTIC])
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.SESSION, 1, 0)))
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.PROFILE, 1, 0)))
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.SEMANTIC, 1, 0)))
    }

    @Test
    fun `malformed timestamps use fallback and emit malformed telemetry count`() {
        val store = RecordingStore()
        val telemetry = RecordingTelemetry()
        val manager = MemoryManager(store, telemetry)
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(
                mode = MemoryManager.MemoryPolicyMode.PERSISTENT,
                expiryByCategoryMs = mapOf(
                    MemoryManager.MemoryCategory.SESSION to 1000L,
                    MemoryManager.MemoryCategory.SEMANTIC to 1000L,
                    MemoryManager.MemoryCategory.PROFILE to 1000L
                ),
                maintenancePolicy = MemoryManager.MemoryMaintenancePolicy(enabled = false)
            )
        )
        manager.upsertProfileMemory(
            key = "tone",
            value = "friendly",
            timestampMs = 1_000L
        )
        manager.editMemory("tone") { node ->
            node.copy(attributes = node.attributes.toMutableMap().apply { put("timestamp", "not-a-number") })
        }
        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-invalid-ts",
                label = "semantic",
                type = NodeType.MEMORY,
                description = "record",
                attributes = mutableMapOf("timestamp" to "bad", "current_relevance" to "0.8")
            )
        )

        manager.runMaintenance(50_000L)

        val retrieved = manager.retrieveForPromptInjection(
            prompt = "check",
            task = "check",
            limit = 10,
            nowEpochMs = 50_000L
        )

        assertTrue(retrieved.any { it.id == "tone" })
        assertTrue(retrieved.any { it.id == "semantic-invalid-ts" })
        assertFalse(store.deletedByCategory.containsKey(MemoryManager.MemoryCategory.PROFILE))
        assertFalse(store.deletedByCategory.containsKey(MemoryManager.MemoryCategory.SEMANTIC))
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.PROFILE, 0, 1)))
        assertTrue(telemetry.events.contains(Triple(MemoryManager.MemoryCategory.SEMANTIC, 0, 1)))
    }

    @Test
    fun `scheduled maintenance archives dormant memories and exports cold storage`() {
        val manager = MemoryManager()
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(
                mode = MemoryManager.MemoryPolicyMode.PERSISTENT,
                maintenancePolicy = MemoryManager.MemoryMaintenancePolicy(
                    enabled = true,
                    cleanupIntervalMs = 1,
                    archiveDormantAfterMs = mapOf(
                        MemoryManager.MemoryCategory.SESSION to 1000L,
                        MemoryManager.MemoryCategory.SEMANTIC to 1000L,
                        MemoryManager.MemoryCategory.PROFILE to 1000L
                    ),
                    retentionCountByCategory = mapOf(
                        MemoryManager.MemoryCategory.SESSION to 1,
                        MemoryManager.MemoryCategory.SEMANTIC to 100,
                        MemoryManager.MemoryCategory.PROFILE to 100
                    ),
                    deleteArchivedAfterMs = 10_000L,
                    sessionCompactionEnabled = false
                )
            )
        )

        manager.appendSessionTurn(MemoryManager.SessionTurn(role = "user", content = "old-1", timestampMs = 100L))
        manager.appendSessionTurn(MemoryManager.SessionTurn(role = "user", content = "old-2", timestampMs = 200L))
        manager.upsertSemanticMemory(
            MindNode(
                id = "semantic-old",
                label = "old semantics",
                type = NodeType.MEMORY,
                description = "very old",
                attributes = mutableMapOf("timestamp" to "100", "current_relevance" to "0.8")
            )
        )

        val report = manager.runScheduledCleanup(nowEpochMs = 50_000L)
        assertNotNull(report)
        val status = manager.status(nowEpochMs = 50_000L)
        assertTrue(status.archivedMemoryCount >= 2)

        val export = manager.exportDormantMemoriesToColdStorage(nowEpochMs = 50_000L)
        assertTrue(export.records.isNotEmpty())
        assertTrue(export.lineDelimitedJson.contains("archived_at_ms"))
    }

    @Test
    fun `session turn metadata is preserved in retrieved memory attributes`() {
        val manager = MemoryManager()
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.SESSION_ONLY)
        )
        manager.appendSessionTurn(
            MemoryManager.SessionTurn(
                role = "assistant",
                source = "import",
                content = "Chunked transcript segment",
                conversationId = "conv-42",
                turnIndex = 7,
                chunkSpan = "7:0-199"
            )
        )

        val retrieved = manager.retrieveForPromptInjection(
            prompt = "transcript",
            task = "audit",
            limit = 5
        )

        val sessionNode = retrieved.first { it.attributes["semantic_subtype"] == "session" }
        assertEquals("conv-42", sessionNode.attributes["conversation_id"])
        assertEquals("7", sessionNode.attributes["turn_index"])
        assertEquals("7:0-199", sessionNode.attributes["chunk_span"])
        assertEquals("import", sessionNode.attributes["source"])
        assertEquals("assistant", sessionNode.attributes["role"])
    }
}
