package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.model.LlmProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCatchUpBuilderTest {

    private val builder = ChatCatchUpBuilder()

    @Test
    fun build_prioritizesRelevantTurnsAndDirectMentionsWithinBudget() {
        val history = listOf(
            message(
                id = "1",
                prompt = "Let's discuss node alpha-1 and memory routing.",
                response = "alpha-1 should retain episodic markers."
            ),
            message(
                id = "2",
                prompt = "Any updates for the relationship graph?",
                response = "No direct action."
            ),
            message(
                id = "3",
                prompt = "Can alpha-1 be merged with beta?",
                response = "Not yet, we need confidence stats."
            )
        )

        val catchUp = builder.build(
            history = history,
            targetMindId = "alpha-1",
            currentUserUtterance = "Update alpha-1 with confidence thresholds",
            tokenBudget = 120
        )

        assertTrue(catchUp.relevantTurnSummaries.isNotEmpty())
        assertTrue(catchUp.directMentionsToTargetMind.isNotEmpty())
        assertTrue(catchUp.estimatedTokens <= 120)
    }

    @Test
    fun build_flagsAmbiguousReferencesInCurrentUtterance() {
        val catchUp = builder.build(
            history = listOf(message(id = "1", prompt = "Discussed several nodes", response = "Done.")),
            targetMindId = "node-42",
            currentUserUtterance = "Fix that and keep it same as before.",
            tokenBudget = 100
        )

        assertTrue(catchUp.unresolvedReferences.isNotEmpty())
    }

    private fun message(id: String, prompt: String, response: String): ChatMessage = ChatMessage(
        id = id,
        prompt = prompt,
        response = response,
        createdTimestamp = 1L,
        providerChoice = ComposerProviderChoice.AUTO,
        provenance = MessageProvenance(
            provider = LlmProvider.OPENAI,
            model = "test-model"
        )
    )
}
