package com.kaleaon.mnxmindmaker.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSessionRepositoryTest {

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
}
