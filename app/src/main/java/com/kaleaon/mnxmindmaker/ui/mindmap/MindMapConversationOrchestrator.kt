package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.repository.PersistedChatMessage
import org.json.JSONObject

internal data class MentionMatch(
    val raw: String,
    val handle: String,
    val startIndex: Int,
    val endIndexExclusive: Int
)

internal data class MentionResolution(
    val match: MentionMatch,
    val resolvedMindId: String?,
    val resolvedMindLabel: String?
)

internal data class CatchUpSnippet(
    val text: String,
    val sourceMessageId: String
)

internal data class ActivationResult(
    val success: Boolean,
    val mindId: String?,
    val feedback: String
)

internal object MindMapConversationOrchestrator {

    private val mentionRegex = Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9._-]{2,40})")

    fun parseMentions(prompt: String): List<MentionMatch> {
        return mentionRegex.findAll(prompt).map { hit ->
            MentionMatch(
                raw = hit.value,
                handle = hit.groupValues[1],
                startIndex = hit.range.first,
                endIndexExclusive = hit.range.last + 1
            )
        }.toList()
    }

    fun resolveMentions(
        prompt: String,
        knownMinds: Map<String, String>
    ): List<MentionResolution> {
        val indexed = knownMinds.entries.associateBy { it.key.lowercase() }
        return parseMentions(prompt).map { match ->
            val candidate = indexed[match.handle.lowercase()]
            MentionResolution(
                match = match,
                resolvedMindId = candidate?.value,
                resolvedMindLabel = candidate?.key
            )
        }
    }

    fun buildCatchUp(
        messages: List<PersistedChatMessage>,
        query: String,
        maxChars: Int
    ): String {
        if (maxChars <= 0 || messages.isEmpty()) return ""
        val qTokens = query.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.toSet()
        val ranked = messages
            .asSequence()
            .map { message ->
                val corpus = "${message.prompt} ${message.response}".lowercase()
                val score = qTokens.count { token -> corpus.contains(token) }
                message to score
            }
            .sortedWith(compareByDescending<Pair<PersistedChatMessage, Int>> { it.second }.thenByDescending { it.first.createdTimestamp })
            .map { it.first }
            .toList()

        val assembled = buildString {
            ranked.forEach { message ->
                append("• User: ")
                appendLine(message.prompt.trim())
                append("  Assistant: ")
                appendLine(message.response.trim())
            }
        }.trim()

        return if (assembled.length <= maxChars) assembled else assembled.take(maxChars)
    }

    fun activateOnMention(
        resolutions: List<MentionResolution>,
        activate: (mindId: String) -> Boolean
    ): List<ActivationResult> {
        return resolutions.map { resolution ->
            val mindId = resolution.resolvedMindId
            if (mindId == null) {
                ActivationResult(
                    success = false,
                    mindId = null,
                    feedback = "System: Could not resolve mention ${resolution.match.raw}."
                )
            } else {
                val success = activate(mindId)
                ActivationResult(
                    success = success,
                    mindId = mindId,
                    feedback = if (success) {
                        "System: Activated ${resolution.resolvedMindLabel ?: resolution.match.raw}."
                    } else {
                        "System: Failed activating ${resolution.resolvedMindLabel ?: resolution.match.raw}."
                    }
                )
            }
        }
    }

    fun assembleTranscript(
        userPrompt: String,
        addressedMindLabel: String?,
        mindResponse: String?,
        unresolvedMentions: List<String>
    ): List<JSONObject> {
        val transcript = mutableListOf<JSONObject>()
        transcript += JSONObject().put("role", "user").put("content", userPrompt)

        if (!addressedMindLabel.isNullOrBlank() && !mindResponse.isNullOrBlank()) {
            transcript += JSONObject()
                .put("role", "assistant")
                .put("name", addressedMindLabel)
                .put("content", mindResponse)
        }

        unresolvedMentions.forEach { mention ->
            transcript += JSONObject()
                .put("role", "system")
                .put("content", "Unresolved mention: @$mention")
        }

        return transcript
    }
}
