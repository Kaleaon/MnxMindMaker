package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import kotlin.math.max

/**
 * Resolves parsed mentions to concrete mind/persona records.
 *
 * Matching precedence:
 * 1) explicit id
 * 2) exact label
 * 3) alias in node attributes / deployed metadata aliases
 * 4) thresholded fuzzy match
 */
class MindIdentityResolver(
    private val fuzzyThreshold: Double = DEFAULT_FUZZY_THRESHOLD
) {

    fun resolve(
        mentions: List<ParsedMindMention>,
        graph: MindGraph,
        deployedPersonas: List<DeployedPersonaMetadata> = emptyList()
    ): MindIdentityResolutionReport {
        val candidates = buildCandidates(graph.nodes, deployedPersonas)

        val resolved = mutableListOf<ResolvedMindMention>()
        val unresolved = mutableListOf<UnresolvedMindMention>()

        mentions.forEach { mention ->
            val resolution = resolveSingle(mention, candidates)
            if (resolution == null) {
                unresolved += UnresolvedMindMention(
                    mention = mention,
                    reason = "No candidate satisfied id/label/alias/fuzzy matching constraints"
                )
            } else {
                resolved += resolution
            }
        }

        return MindIdentityResolutionReport(
            resolved = resolved,
            unresolved = unresolved
        )
    }

    private fun resolveSingle(
        mention: ParsedMindMention,
        candidates: List<IdentityCandidate>
    ): ResolvedMindMention? {
        val explicitId = mention.explicitId?.trim().orEmpty()
        if (explicitId.isNotEmpty()) {
            val idMatch = candidates.firstOrNull { it.id.equals(explicitId, ignoreCase = true) }
            if (idMatch != null) {
                return idMatch.toResolved(mention, MatchStrategy.EXPLICIT_ID, 1.0)
            }
        }

        val normalized = normalize(mention.text)
        if (normalized.isBlank()) return null

        val exactMatch = candidates.firstOrNull { normalize(it.label) == normalized }
        if (exactMatch != null) {
            return exactMatch.toResolved(mention, MatchStrategy.EXACT_LABEL, 1.0)
        }

        val aliasMatch = candidates.firstOrNull { candidate ->
            candidate.aliases.any { normalize(it) == normalized }
        }
        if (aliasMatch != null) {
            return aliasMatch.toResolved(mention, MatchStrategy.ALIAS, 1.0)
        }

        val fuzzyMatch = candidates
            .asSequence()
            .map { candidate ->
                val bestAliasScore = candidate.aliases
                    .map { similarity(normalized, normalize(it)) }
                    .maxOrNull() ?: 0.0
                val score = max(
                    similarity(normalized, normalize(candidate.label)),
                    bestAliasScore
                )
                candidate to score
            }
            .filter { it.second >= fuzzyThreshold }
            .maxByOrNull { it.second }

        return fuzzyMatch?.let { (candidate, score) ->
            candidate.toResolved(mention, MatchStrategy.FUZZY, score)
        }
    }

    private fun buildCandidates(
        nodes: List<MindNode>,
        deployedPersonas: List<DeployedPersonaMetadata>
    ): List<IdentityCandidate> {
        val nodeCandidates = nodes.map { node ->
            IdentityCandidate(
                id = node.id,
                label = node.label,
                aliases = extractAliases(node.attributes),
                source = IdentitySource.MIND_NODE,
                attributes = node.attributes.toMap()
            )
        }

        val personaCandidates = deployedPersonas.map { persona ->
            IdentityCandidate(
                id = persona.personaId,
                label = persona.displayName.ifBlank { persona.personaId },
                aliases = persona.aliases,
                source = IdentitySource.DEPLOYED_PERSONA,
                attributes = persona.attributes
            )
        }

        return nodeCandidates + personaCandidates
    }

    private fun extractAliases(attributes: Map<String, String>): List<String> {
        val aliasValues = attributes
            .filterKeys { key -> key.contains("alias", ignoreCase = true) }
            .values

        return aliasValues
            .flatMap { raw ->
                raw.split(',', '|', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            .distinctBy { normalize(it) }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0

        val distance = levenshtein(a, b)
        val longest = max(a.length, b.length).coerceAtLeast(1)
        return 1.0 - distance.toDouble() / longest.toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + substitutionCost
                )
            }
            current.copyInto(previous)
        }

        return previous[b.length]
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
    }

    private fun IdentityCandidate.toResolved(
        mention: ParsedMindMention,
        strategy: MatchStrategy,
        score: Double
    ): ResolvedMindMention {
        return ResolvedMindMention(
            mention = mention,
            target = MindIdentityTarget(
                id = id,
                label = label,
                source = source,
                attributes = attributes
            ),
            strategy = strategy,
            confidence = score
        )
    }

    private data class IdentityCandidate(
        val id: String,
        val label: String,
        val aliases: List<String>,
        val source: IdentitySource,
        val attributes: Map<String, String>
    )

    companion object {
        const val DEFAULT_FUZZY_THRESHOLD: Double = 0.88
    }
}

data class ParsedMindMention(
    val text: String,
    val explicitId: String? = null,
    val spanStart: Int? = null,
    val spanEnd: Int? = null
)

data class DeployedPersonaMetadata(
    val personaId: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap()
)

data class MindIdentityResolutionReport(
    val resolved: List<ResolvedMindMention>,
    val unresolved: List<UnresolvedMindMention>
)

data class ResolvedMindMention(
    val mention: ParsedMindMention,
    val target: MindIdentityTarget,
    val strategy: MatchStrategy,
    val confidence: Double
)

data class UnresolvedMindMention(
    val mention: ParsedMindMention,
    val reason: String
)

data class MindIdentityTarget(
    val id: String,
    val label: String,
    val source: IdentitySource,
    val attributes: Map<String, String>
)

enum class MatchStrategy {
    EXPLICIT_ID,
    EXACT_LABEL,
    ALIAS,
    FUZZY
}

enum class IdentitySource {
    MIND_NODE,
    DEPLOYED_PERSONA
}
