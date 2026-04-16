package com.kaleaon.mnxmindmaker.ui.mindmap

import java.util.Locale

/**
 * Parses chat prompts for explicit mind addressing syntax.
 */
class ChatMentionParser {

    data class IdentityCandidate(
        val id: String,
        val label: String,
        val aliases: Set<String> = emptySet()
    )

    data class AddressedIdentity(
        val id: String,
        val label: String,
        val matchedBy: MatchType,
        val token: String
    )

    data class ParseResult(
        val cleanedMessageContent: String,
        val addressedIdentities: List<AddressedIdentity>
    )

    enum class MatchType {
        EXACT,
        ALIAS,
        FUZZY
    }

    fun parse(input: String, candidates: List<IdentityCandidate>): ParseResult {
        if (input.isBlank()) {
            return ParseResult(cleanedMessageContent = "", addressedIdentities = emptyList())
        }

        val mentions = collectMentionTokens(input)
        if (mentions.isEmpty()) {
            return ParseResult(cleanedMessageContent = input.trim(), addressedIdentities = emptyList())
        }

        val addressed = mentions.mapNotNull { mention ->
            resolveMention(token = mention.token, candidates = candidates)
                ?.copy(token = mention.token)
        }

        val cleaned = buildCleanedMessage(input, mentions)
        return ParseResult(cleanedMessageContent = cleaned, addressedIdentities = addressed.distinctBy { it.id })
    }

    private fun collectMentionTokens(input: String): List<MentionToken> {
        val tokens = mutableListOf<MentionToken>()

        val atMentionRegex = Regex("(?<![\\p{L}\\p{N}_])@([\\p{L}\\p{N}_-]+)")
        atMentionRegex.findAll(input).forEach { match ->
            val token = match.groupValues[1].trim()
            if (token.isNotEmpty()) {
                tokens += MentionToken(
                    token = token,
                    start = match.range.first,
                    endExclusive = match.range.last + 1
                )
            }
        }

        val quotedRegex = Regex("\"([^\"]+)\"|'([^']+)'")
        quotedRegex.findAll(input).forEach { match ->
            val token = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (token.isNotEmpty()) {
                tokens += MentionToken(
                    token = token,
                    start = match.range.first,
                    endExclusive = match.range.last + 1
                )
            }
        }

        return tokens.sortedBy { it.start }
    }

    private fun resolveMention(token: String, candidates: List<IdentityCandidate>): AddressedIdentity? {
        if (candidates.isEmpty()) return null
        val normalizedToken = normalize(token)
        if (normalizedToken.isBlank()) return null

        val exactMatches = candidates.filter { normalize(it.label) == normalizedToken }
        if (exactMatches.isNotEmpty()) {
            val winner = exactMatches.sortedWith(candidateComparator(normalizedToken)).first()
            return AddressedIdentity(id = winner.id, label = winner.label, matchedBy = MatchType.EXACT, token = token)
        }

        val aliasMatches = candidates.filter { candidate ->
            candidate.aliases.any { alias -> normalize(alias) == normalizedToken }
        }
        if (aliasMatches.isNotEmpty()) {
            val winner = aliasMatches.sortedWith(candidateComparator(normalizedToken)).first()
            return AddressedIdentity(id = winner.id, label = winner.label, matchedBy = MatchType.ALIAS, token = token)
        }

        val fuzzyMatches = candidates.filter { candidate ->
            val label = normalize(candidate.label)
            label.contains(normalizedToken) || normalizedToken.contains(label)
        }
        if (fuzzyMatches.isNotEmpty()) {
            val winner = fuzzyMatches.sortedWith(candidateComparator(normalizedToken)).first()
            return AddressedIdentity(id = winner.id, label = winner.label, matchedBy = MatchType.FUZZY, token = token)
        }

        return null
    }

    private fun buildCleanedMessage(input: String, mentions: List<MentionToken>): String {
        if (mentions.isEmpty()) return input.trim()
        val merged = mergeRanges(mentions)
        val builder = StringBuilder()
        var cursor = 0
        merged.forEach { range ->
            if (cursor < range.start) {
                builder.append(input.substring(cursor, range.start))
            }
            cursor = maxOf(cursor, range.endExclusive)
        }
        if (cursor < input.length) {
            builder.append(input.substring(cursor))
        }

        return builder.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .trim()
    }

    private fun mergeRanges(tokens: List<MentionToken>): List<MentionToken> {
        if (tokens.isEmpty()) return emptyList()
        val sorted = tokens.sortedBy { it.start }
        val merged = mutableListOf<MentionToken>()
        var current = sorted.first()

        sorted.drop(1).forEach { next ->
            if (next.start <= current.endExclusive) {
                current = current.copy(endExclusive = maxOf(current.endExclusive, next.endExclusive))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun candidateComparator(token: String): Comparator<IdentityCandidate> {
        return compareBy<IdentityCandidate>(
            { levenshtein(normalize(it.label), token) },
            { it.label.length },
            { it.id.lowercase(Locale.ROOT) }
        )
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)

        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[right.length]
    }

    private data class MentionToken(
        val token: String,
        val start: Int,
        val endExclusive: Int
    )
}
