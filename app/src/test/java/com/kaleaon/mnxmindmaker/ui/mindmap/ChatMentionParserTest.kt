package com.kaleaon.mnxmindmaker.ui.mindmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMentionParserTest {

    private val parser = ChatMentionParser()

    @Test
    fun parse_extractsAtMentionsAndQuotedMentions_andCleansPrompt() {
        val candidates = listOf(
            ChatMentionParser.IdentityCandidate(id = "m1", label = "Athena"),
            ChatMentionParser.IdentityCandidate(id = "m2", label = "Orion Prime")
        )

        val result = parser.parse("@Athena please sync with \"Orion Prime\" on this.", candidates)

        assertEquals("please sync with on this.", result.cleanedMessageContent)
        assertEquals(listOf("m1", "m2"), result.addressedIdentities.map { it.id })
        assertEquals(
            listOf(ChatMentionParser.MatchType.EXACT, ChatMentionParser.MatchType.EXACT),
            result.addressedIdentities.map { it.matchedBy }
        )
    }

    @Test
    fun parse_prefersAliasWhenNoExactLabelMatch() {
        val candidates = listOf(
            ChatMentionParser.IdentityCandidate(id = "m1", label = "Athena Strategist", aliases = setOf("Athena"))
        )

        val result = parser.parse("@Athena propose a memory index.", candidates)

        assertEquals("propose a memory index.", result.cleanedMessageContent)
        assertEquals(1, result.addressedIdentities.size)
        assertEquals(ChatMentionParser.MatchType.ALIAS, result.addressedIdentities.first().matchedBy)
    }

    @Test
    fun parse_usesDeterministicFuzzyResolution() {
        val candidates = listOf(
            ChatMentionParser.IdentityCandidate(id = "z-id", label = "Nova Prime"),
            ChatMentionParser.IdentityCandidate(id = "a-id", label = "Nova Prime Core")
        )

        val result = parser.parse("@Nova outline the plan", candidates)

        assertEquals(1, result.addressedIdentities.size)
        assertEquals("z-id", result.addressedIdentities.first().id)
        assertEquals(ChatMentionParser.MatchType.FUZZY, result.addressedIdentities.first().matchedBy)
    }

    @Test
    fun parse_prefersExactOverAliasAndFuzzy() {
        val candidates = listOf(
            ChatMentionParser.IdentityCandidate(id = "exact", label = "Echo"),
            ChatMentionParser.IdentityCandidate(id = "alias", label = "Echo Prime", aliases = setOf("Echo")),
            ChatMentionParser.IdentityCandidate(id = "fuzzy", label = "Echo Core")
        )

        val result = parser.parse("@Echo prioritize this", candidates)

        assertEquals(1, result.addressedIdentities.size)
        assertEquals("exact", result.addressedIdentities.first().id)
        assertEquals(ChatMentionParser.MatchType.EXACT, result.addressedIdentities.first().matchedBy)
        assertTrue(result.cleanedMessageContent.startsWith("prioritize"))
    }
}
