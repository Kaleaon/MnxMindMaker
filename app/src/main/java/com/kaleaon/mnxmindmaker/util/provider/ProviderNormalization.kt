package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import kotlin.math.sqrt

/**
 * Strict cross-provider contract that all adapters must satisfy before they can be enabled.
 */
data class ProviderCapabilities(
    val supportsChat: Boolean = true,
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val supportsEmbeddings: Boolean = true,
    val supportsReranking: Boolean = true,
    val reportsTokenUsage: Boolean = false
)

enum class ProviderErrorCode {
    INVALID_REQUEST,
    AUTH,
    RATE_LIMIT,
    FEATURE_UNSUPPORTED,
    NETWORK,
    SERVER,
    UNKNOWN
}

class ProviderException(
    val providerId: String,
    val code: ProviderErrorCode,
    message: String,
    val isRetriable: Boolean = false,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

data class NormalizedTokenUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)

data class ProviderChatResult(
    val turn: AssistantTurn,
    val usage: NormalizedTokenUsage,
    val providerId: String
)

data class ProviderEmbeddingRequest(
    val input: List<String>,
    val dimensions: Int = 256
)

data class ProviderEmbeddingResult(
    val vectors: List<List<Float>>,
    val usage: NormalizedTokenUsage,
    val providerId: String
)

data class ProviderRerankRequest(
    val query: String,
    val documents: List<String>,
    val topK: Int = documents.size
)

data class ProviderRerankItem(
    val index: Int,
    val score: Float,
    val document: String
)

data class ProviderRerankResult(
    val items: List<ProviderRerankItem>,
    val usage: NormalizedTokenUsage,
    val providerId: String
)

data class ProviderConformanceResult(
    val providerId: String,
    val passed: Boolean,
    val violations: List<String>
)

internal object ProviderNormalization {

    fun tokenUsageFromRaw(raw: org.json.JSONObject?): NormalizedTokenUsage {
        val usage = raw?.optJSONObject("usage")
        val prompt = usage?.optInt("prompt_tokens")?.takeIf { it >= 0 }
            ?: usage?.optInt("input_tokens")?.takeIf { it >= 0 }
        val completion = usage?.optInt("completion_tokens")?.takeIf { it >= 0 }
            ?: usage?.optInt("output_tokens")?.takeIf { it >= 0 }
        val total = usage?.optInt("total_tokens")?.takeIf { it >= 0 }
            ?: listOfNotNull(prompt, completion).takeIf { it.isNotEmpty() }?.sum()

        return NormalizedTokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total
        )
    }

    fun estimatedUsage(systemPrompt: String, transcript: List<org.json.JSONObject>, output: String): NormalizedTokenUsage {
        val promptWords = (systemPrompt + " " + transcript.joinToString(" ") { it.opt("content")?.toString().orEmpty() })
            .trim()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
        val completionWords = output.trim().split(Regex("\\s+"))
            .count { it.isNotBlank() }
        return NormalizedTokenUsage(
            promptTokens = promptWords,
            completionTokens = completionWords,
            totalTokens = promptWords + completionWords
        )
    }

    fun fallbackEmbeddings(input: List<String>, dimensions: Int): List<List<Float>> {
        require(dimensions > 0) { "Embedding dimensions must be positive." }
        return input.map { text ->
            val vector = FloatArray(dimensions)
            text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.forEach { token ->
                val slot = (token.hashCode().toLong() and 0x7fffffff).rem(dimensions.toLong()).toInt()
                vector[slot] += 1f
            }
            normalize(vector)
        }.map { it.toList() }
    }

    fun fallbackRerank(query: String, documents: List<String>, topK: Int): List<ProviderRerankItem> {
        if (documents.isEmpty()) return emptyList()
        val queryVector = fallbackEmbeddings(listOf(query), 256).first().toFloatArray()
        val scored = documents.mapIndexed { index, document ->
            val docVector = fallbackEmbeddings(listOf(document), 256).first().toFloatArray()
            ProviderRerankItem(index = index, score = dot(queryVector, docVector), document = document)
        }.sortedByDescending { it.score }

        return scored.take(topK.coerceIn(1, scored.size))
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.fold(0.0) { acc, value -> acc + (value * value) }.toFloat())
        if (norm == 0f) return vector
        return FloatArray(vector.size) { idx -> vector[idx] / norm }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }
}

object ProviderConformanceSuite {

    fun validate(provider: AssistantProvider): ProviderConformanceResult {
        val violations = mutableListOf<String>()
        val idRegex = Regex("^[a-z0-9_-]{2,32}$")
        if (!idRegex.matches(provider.id)) {
            violations += "id must match ${idRegex.pattern}"
        }

        val capabilities = provider.capabilities
        if (!capabilities.supportsChat) {
            violations += "supportsChat must be true for enabled providers"
        }
        if (!capabilities.supportsTools) {
            violations += "supportsTools must be true for enabled providers"
        }
        if (!capabilities.supportsStreaming) {
            violations += "supportsStreaming must be true for enabled providers"
        }

        return ProviderConformanceResult(
            providerId = provider.id,
            passed = violations.isEmpty(),
            violations = violations
        )
    }
}

object ProviderConformanceGate {

    fun enforce(providers: List<AssistantProvider>): List<AssistantProvider> {
        return providers.filter { ProviderConformanceSuite.validate(it).passed }
    }
}
