package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.repository.ChatPersistenceSchema
import com.kaleaon.mnxmindmaker.repository.ChatSessionRepository
import com.kaleaon.mnxmindmaker.repository.PersistedChatMessage
import com.kaleaon.mnxmindmaker.ui.mindmap.MindMapConversationOrchestrator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MindMapConversationOrchestratorTest {

    @Test
    fun `mention parser and resolver identify known and unknown handles`() {
        val prompt = "@Atlas summarize this and ask @Unknown for missing constraints"

        val resolutions = MindMapConversationOrchestrator.resolveMentions(
            prompt = prompt,
            knownMinds = mapOf("Atlas" to "mind-1")
        )

        assertEquals(2, resolutions.size)
        assertEquals("mind-1", resolutions[0].resolvedMindId)
        assertEquals("Atlas", resolutions[0].resolvedMindLabel)
        assertEquals("Unknown", resolutions[1].match.handle)
        assertEquals(null, resolutions[1].resolvedMindId)
    }

    @Test
    fun `catch-up builder truncates output and ranks relevant turns first`() {
        val messages = listOf(
            PersistedChatMessage(
                id = "m1",
                prompt = "tell me a joke",
                response = "here is a short joke",
                createdTimestamp = 100L
            ),
            PersistedChatMessage(
                id = "m2",
                prompt = "incident review for api latency",
                response = "latency rose after canary deploy",
                createdTimestamp = 200L
            )
        )

        val full = MindMapConversationOrchestrator.buildCatchUp(messages, query = "api latency", maxChars = 500)
        val truncated = MindMapConversationOrchestrator.buildCatchUp(messages, query = "api latency", maxChars = 40)

        assertTrue(full.startsWith("• User: incident review for api latency"))
        assertEquals(40, truncated.length)
    }

    @Test
    fun `session schema migration upgrades v1 payload to v2 fields`() {
        val v1 = Json.parseToJsonElement(
            """
            {
              "schemaVersion": 1,
              "activeSessionId": "s1",
              "sessions": [
                {
                  "sessionId": "s1",
                  "displayName": "Thread 1",
                  "messages": [
                    {
                      "id": "m1",
                      "prompt": "hello",
                      "response": "hi"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val migrated = ChatSessionRepository.migratePayloadForVersion(v1, version = 1)
        val session = migrated["sessions"]!!.jsonArray[0].jsonObject
        val message = session["messages"]!!.jsonArray[0].jsonObject

        assertEquals(ChatPersistenceSchema.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("multi_actor", session["conversationMode"]!!.jsonPrimitive.content)
        assertEquals("assistant", message["actorLabel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `activation on mention returns success and failure feedback`() {
        val resolutions = MindMapConversationOrchestrator.resolveMentions(
            prompt = "@Atlas and @Unknown provide updates",
            knownMinds = mapOf("Atlas" to "mind-1")
        )

        val activation = MindMapConversationOrchestrator.activateOnMention(resolutions) { mindId ->
            mindId == "mind-1"
        }

        assertTrue(activation[0].success)
        assertTrue(activation[0].feedback.contains("Activated"))
        assertFalse(activation[1].success)
        assertTrue(activation[1].feedback.contains("Could not resolve mention"))
    }

    @Test
    fun `transcript assembly captures multi-actor attribution and unresolved notices`() {
        val transcript = MindMapConversationOrchestrator.assembleTranscript(
            userPrompt = "@Atlas summarize today",
            addressedMindLabel = "Atlas",
            mindResponse = "Atlas: Here is the summary.",
            unresolvedMentions = listOf("Unknown")
        )

        assertEquals(3, transcript.size)
        assertEquals("user", transcript[0].optString("role"))
        assertEquals("assistant", transcript[1].optString("role"))
        assertEquals("Atlas", transcript[1].optString("name"))
        assertEquals("system", transcript[2].optString("role"))
        assertTrue(transcript[2].optString("content").contains("@Unknown"))
    }
}
