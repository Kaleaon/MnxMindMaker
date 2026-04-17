package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MindIdentityResolverTest {

    private val graph = MindGraph(
        name = "Test Graph",
        nodes = mutableListOf(
            MindNode(
                id = "node-identity",
                label = "Astra",
                type = NodeType.IDENTITY,
                attributes = mutableMapOf("aliases" to "astral, astra prime")
            ),
            MindNode(
                id = "node-memory",
                label = "Memory Keeper",
                type = NodeType.MEMORY,
                attributes = mutableMapOf("alias_primary" to "archivist")
            )
        )
    )

    @Test
    fun `resolves with documented precedence and returns unresolved mentions`() {
        val resolver = MindIdentityResolver()
        val mentions = listOf(
            ParsedMindMention(text = "ignored because id wins", explicitId = "node-memory"),
            ParsedMindMention(text = "astra"),
            ParsedMindMention(text = "archivist"),
            ParsedMindMention(text = "astra prm"),
            ParsedMindMention(text = "no match")
        )

        val report = resolver.resolve(
            mentions = mentions,
            graph = graph,
            deployedPersonas = listOf(
                DeployedPersonaMetadata(
                    personaId = "mentor-01",
                    displayName = "Mentor",
                    aliases = listOf("coach", "guide")
                )
            )
        )

        assertEquals(4, report.resolved.size)
        assertEquals(1, report.unresolved.size)

        assertEquals(MatchStrategy.EXPLICIT_ID, report.resolved[0].strategy)
        assertEquals("node-memory", report.resolved[0].target.id)

        assertEquals(MatchStrategy.EXACT_LABEL, report.resolved[1].strategy)
        assertEquals("node-identity", report.resolved[1].target.id)

        assertEquals(MatchStrategy.ALIAS, report.resolved[2].strategy)
        assertEquals("node-memory", report.resolved[2].target.id)

        assertEquals(MatchStrategy.FUZZY, report.resolved[3].strategy)
        assertEquals("node-identity", report.resolved[3].target.id)

        assertEquals("no match", report.unresolved.single().mention.text)
    }

    @Test
    fun `resolves deployed persona metadata when graph labels do not match`() {
        val resolver = MindIdentityResolver()

        val report = resolver.resolve(
            mentions = listOf(ParsedMindMention(text = "coach")),
            graph = graph,
            deployedPersonas = listOf(
                DeployedPersonaMetadata(
                    personaId = "persona-coach",
                    displayName = "Runtime Mentor",
                    aliases = listOf("coach")
                )
            )
        )

        val resolved = report.resolved.single()
        assertEquals(IdentitySource.DEPLOYED_PERSONA, resolved.target.source)
        assertEquals("persona-coach", resolved.target.id)
        assertTrue(report.unresolved.isEmpty())
    }

    @Test
    fun `fuzzy matching is safely thresholded`() {
        val strictResolver = MindIdentityResolver(fuzzyThreshold = 0.95)

        val report = strictResolver.resolve(
            mentions = listOf(ParsedMindMention(text = "astra prm")),
            graph = graph
        )

        assertTrue(report.resolved.isEmpty())
        assertEquals("astra prm", report.unresolved.single().mention.text)
    }
}
