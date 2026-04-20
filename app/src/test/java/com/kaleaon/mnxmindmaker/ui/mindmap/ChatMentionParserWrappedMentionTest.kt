package com.kaleaon.mnxmindmaker.ui.mindmap

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMentionParserWrappedMentionTest {

    private val parser = ChatMentionParser()

    @Test
    fun parse_supportsWrappedAtMentionForMultiWordCharacter() {
        val candidates = listOf(
            ChatMentionParser.IdentityCandidate(id = "m1", label = "Orion Prime")
        )

        val result = parser.parse("@{Orion Prime} continue the scenario", candidates)

        assertEquals(listOf("m1"), result.addressedIdentities.map { it.id })
        assertEquals("continue the scenario", result.cleanedMessageContent)
    }
}
