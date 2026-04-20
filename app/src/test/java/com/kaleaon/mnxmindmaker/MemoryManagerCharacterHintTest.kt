package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.util.memory.MemoryManager
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryManagerCharacterHintTest {

    @Test
    fun `searchMemories prioritizes matching character id when hint provided`() {
        val manager = MemoryManager()
        manager.setPolicy(
            MemoryManager.MemoryPolicySettings(mode = MemoryManager.MemoryPolicyMode.SESSION_ONLY)
        )

        manager.upsertProfileMemory(
            key = "char:layla:voice",
            value = "Layla speaks with a calm tone",
            characterId = "layla"
        )
        manager.upsertProfileMemory(
            key = "char:kaito:voice",
            value = "Kaito uses energetic language",
            characterId = "kaito"
        )

        val results = manager.searchMemories(
            query = "voice tone",
            limit = 2,
            characterIdHint = "layla"
        )

        assertEquals("char:layla:voice", results.first().id)
    }
}
