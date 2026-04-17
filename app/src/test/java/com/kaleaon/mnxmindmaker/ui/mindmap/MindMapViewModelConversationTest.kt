package com.kaleaon.mnxmindmaker.ui.mindmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindMapViewModelConversationTest {

    @Test
    fun `addressed mind receives context in assembled transcript`() {
        val transcript = MindMapConversationOrchestrator.assembleTranscript(
            userPrompt = "@Atlas can you expand this design?",
            addressedMindLabel = "Atlas",
            mindResponse = "Atlas: Expanding design context.",
            unresolvedMentions = emptyList()
        )

        assertEquals(2, transcript.size)
        assertEquals("@Atlas can you expand this design?", transcript[0].optString("content"))
        assertEquals("Atlas", transcript[1].optString("name"))
    }

    @Test
    fun `conversation includes attributed mind reply`() {
        val transcript = MindMapConversationOrchestrator.assembleTranscript(
            userPrompt = "@Planner propose next steps",
            addressedMindLabel = "Planner",
            mindResponse = "Planner: Next steps are prioritize and execute.",
            unresolvedMentions = emptyList()
        )

        assertEquals("assistant", transcript[1].optString("role"))
        assertTrue(transcript[1].optString("content").startsWith("Planner:"))
    }

    @Test
    fun `unresolved mention creates system feedback`() {
        val transcript = MindMapConversationOrchestrator.assembleTranscript(
            userPrompt = "@Ghost review this",
            addressedMindLabel = null,
            mindResponse = null,
            unresolvedMentions = listOf("Ghost")
        )

        assertEquals(2, transcript.size)
        assertEquals("system", transcript[1].optString("role"))
        assertTrue(transcript[1].optString("content").contains("@Ghost"))
    }

    @Test
    fun `legacy single assistant flow remains user plus assistant without attribution`() {
        val transcript = MindMapConversationOrchestrator.assembleTranscript(
            userPrompt = "Help with migration",
            addressedMindLabel = null,
            mindResponse = null,
            unresolvedMentions = emptyList()
        )

        assertEquals(1, transcript.size)
        assertEquals("user", transcript[0].optString("role"))
        assertNull(transcript[0].opt("name"))
    }
}
