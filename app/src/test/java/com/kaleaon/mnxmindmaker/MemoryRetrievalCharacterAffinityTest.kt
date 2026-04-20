package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.MemoryRetrievalService
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRetrievalCharacterAffinityTest {

    @Test
    fun `retrieve prefers memories that match character hint`() {
        val context = MemoryRetrievalService.RetrievalContext(
            prompt = "background",
            task = "roleplay",
            characterIdHint = "layla"
        )

        val memoryForLayla = memory(id = "for-layla", characterId = "layla")
        val memoryForOther = memory(id = "for-other", characterId = "kaito")

        val retrieved = MemoryRetrievalService.retrieve(
            memories = listOf(memoryForOther, memoryForLayla),
            context = context,
            limit = 2
        )

        assertEquals(listOf("for-layla", "for-other"), retrieved.map { it.id })
    }

    private fun memory(id: String, characterId: String): MindNode = MindNode(
        id = id,
        label = id,
        type = NodeType.MEMORY,
        description = "memory-$id",
        attributes = mutableMapOf(
            "current_relevance" to "0.7",
            "confidence" to "0.7",
            "sensitivity" to "low",
            "importance" to "0.5",
            "timestamp" to "1700000000000",
            "character_id" to characterId
        )
    )
}
