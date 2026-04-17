package com.kaleaon.mnxmindmaker.ui.mindmap

import kotlin.math.max

data class ChatCatchUp(
    val relevantTurnSummaries: List<String>,
    val directMentionsToTargetMind: List<String>,
    val unresolvedReferences: List<String>,
    val estimatedTokens: Int
) {
    fun asSystemPromptSection(): String {
        if (
            relevantTurnSummaries.isEmpty() &&
            directMentionsToTargetMind.isEmpty() &&
            unresolvedReferences.isEmpty()
        ) {
            return ""
        }
        return buildString {
            appendLine("Historical catch-up (bounded):")
            if (relevantTurnSummaries.isEmpty()) {
                appendLine("- Relevant prior turns: none selected.")
            } else {
                appendLine("- Relevant prior turns (ordered):")
                relevantTurnSummaries.forEach { appendLine("  • $it") }
            }
            if (directMentionsToTargetMind.isEmpty()) {
                appendLine("- Direct mentions to target mind: none.")
            } else {
                appendLine("- Direct mentions to target mind:")
                directMentionsToTargetMind.forEach { appendLine("  • $it") }
            }
            if (unresolvedReferences.isEmpty()) {
                appendLine("- Unresolved references requiring clarification: none.")
            } else {
                appendLine("- Unresolved references requiring clarification:")
                unresolvedReferences.forEach { appendLine("  • $it") }
            }
        }.trim()
    }
}

class ChatCatchUpBuilder {

    fun build(
        history: List<ChatMessage>,
        targetMindId: String?,
        currentUserUtterance: String,
        tokenBudget: Int
    ): ChatCatchUp {
        if (tokenBudget <= 0 || history.isEmpty()) {
            return ChatCatchUp(
                relevantTurnSummaries = emptyList(),
                directMentionsToTargetMind = emptyList(),
                unresolvedReferences = detectUnresolvedReferences(currentUserUtterance),
                estimatedTokens = 0
            )
        }

        val queryTerms = tokenize(currentUserUtterance)
        val target = targetMindId.orEmpty().trim()
        val rankedTurns = history.mapIndexed { index, message ->
            val turnText = "${message.prompt}\n${message.response}"
            val turnTerms = tokenize(turnText)
            val overlap = queryTerms.intersect(turnTerms).size
            val directMentionBoost = if (target.isNotBlank() && turnText.contains(target, ignoreCase = true)) 3 else 0
            val recencyBoost = (index + 1).toDouble() / max(history.size, 1)
            RankedTurn(
                index = index,
                message = message,
                score = overlap + directMentionBoost + recencyBoost
            )
        }.sortedByDescending { it.score }

        val directMentions = extractDirectMentions(history, target, limit = 6)
        val unresolvedReferences = detectUnresolvedReferences(currentUserUtterance)

        val mentionTokens = estimateTokens(directMentions.joinToString("\n"))
        val unresolvedTokens = estimateTokens(unresolvedReferences.joinToString("\n"))
        var remainingBudget = max(tokenBudget - mentionTokens - unresolvedTokens - 24, 0)

        val selected = mutableListOf<Pair<Int, String>>()
        for (turn in rankedTurns) {
            if (remainingBudget <= 12) break
            val summary = summarizeTurn(turn.index, turn.message, remainingBudget)
            val summaryTokens = estimateTokens(summary)
            if (summaryTokens <= remainingBudget) {
                selected += turn.index to summary
                remainingBudget -= summaryTokens
            }
            if (selected.size >= 8) break
        }

        val orderedSummaries = selected.sortedBy { it.first }.map { it.second }
        val usedTokens = tokenBudget - remainingBudget
        return ChatCatchUp(
            relevantTurnSummaries = orderedSummaries,
            directMentionsToTargetMind = directMentions,
            unresolvedReferences = unresolvedReferences,
            estimatedTokens = usedTokens
        )
    }

    private fun extractDirectMentions(
        history: List<ChatMessage>,
        targetMindId: String,
        limit: Int
    ): List<String> {
        if (targetMindId.isBlank()) return emptyList()
        return history.asReversed()
            .flatMap { message ->
                listOf("user" to message.prompt, "assistant" to message.response)
            }
            .filter { (_, text) -> text.contains(targetMindId, ignoreCase = true) }
            .take(limit)
            .map { (role, text) ->
                val excerpt = truncate(text.replace(Regex("\\s+"), " "), 140)
                "$role: \"$excerpt\""
            }
            .reversed()
    }

    private fun detectUnresolvedReferences(currentUserUtterance: String): List<String> {
        val lowered = currentUserUtterance.lowercase()
        val unresolved = mutableListOf<String>()
        if (Regex("\\b(it|this|that|they|those|these|he|she|him|her)\\b").containsMatchIn(lowered)) {
            unresolved += "User used pronouns that may have ambiguous antecedents."
        }
        if (Regex("\\b(same as before|as discussed|like earlier|that one)\\b").containsMatchIn(lowered)) {
            unresolved += "User referenced earlier context without a concrete pointer."
        }
        if (Regex("\\b(fix that|update that|do it)\\b").containsMatchIn(lowered)) {
            unresolved += "Action request contains an underspecified target."
        }
        return unresolved
    }

    private fun summarizeTurn(index: Int, message: ChatMessage, remainingBudget: Int): String {
        val remainingChars = max(remainingBudget * 4, 48)
        val userExcerpt = truncate(clean(message.prompt), remainingChars / 2)
        val assistantExcerpt = truncate(clean(message.response), remainingChars / 2)
        return "Turn ${index + 1} — User: $userExcerpt | Assistant: $assistantExcerpt"
    }

    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars.coerceAtLeast(4) - 1).trimEnd() + "…"
    }

    private fun tokenize(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z0-9_@-]+"))
        .filter { it.length >= 3 }
        .toSet()

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return max(1, text.length / 4)
    }

    private data class RankedTurn(
        val index: Int,
        val message: ChatMessage,
        val score: Double
    )
}
