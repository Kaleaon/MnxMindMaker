package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import com.kaleaon.mnxmindmaker.util.tooling.ToolSpec
import org.json.JSONObject
import java.io.File

data class ProviderRequest(
    val settings: LlmSettings,
    val systemPrompt: String,
    val transcript: List<JSONObject>,
    val tools: List<ToolSpec> = emptyList()
)

data class ProviderHealth(
    val ok: Boolean,
    val message: String
)

interface AssistantProvider {
    val id: String

    /**
     * Strict capability advertisement consumed by conformance tests and provider gating.
     */
    val capabilities: ProviderCapabilities
        get() = ProviderCapabilities()

    fun supports(settings: LlmSettings): Boolean

    fun chat(request: ProviderRequest): AssistantTurn

    fun completeChat(request: ProviderRequest): ProviderChatResult {
        val turn = try {
            chat(request)
        } catch (throwable: Throwable) {
            throw normalizeError(throwable)
        }
        val extracted = ProviderNormalization.tokenUsageFromRaw(turn.raw)
        val usage = if (extracted.totalTokens != null || extracted.promptTokens != null || extracted.completionTokens != null) {
            extracted
        } else {
            ProviderNormalization.estimatedUsage(request.systemPrompt, request.transcript, turn.text)
        }
        return ProviderChatResult(turn = turn, usage = usage, providerId = id)
    }

    fun streamChat(request: ProviderRequest, onToken: (String) -> Unit = {}): AssistantTurn {
        val turn = chat(request)
        if (turn.text.isNotBlank()) {
            turn.text.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
                onToken("$token ")
            }
        }
        return turn
    }

    fun streamCompleteChat(request: ProviderRequest, onToken: (String) -> Unit = {}): ProviderChatResult {
        val turn = try {
            streamChat(request, onToken)
        } catch (throwable: Throwable) {
            throw normalizeError(throwable)
        }
        val extracted = ProviderNormalization.tokenUsageFromRaw(turn.raw)
        val usage = if (extracted.totalTokens != null || extracted.promptTokens != null || extracted.completionTokens != null) {
            extracted
        } else {
            ProviderNormalization.estimatedUsage(request.systemPrompt, request.transcript, turn.text)
        }
        return ProviderChatResult(turn = turn, usage = usage, providerId = id)
    }

    fun embed(settings: LlmSettings, input: List<String>): List<List<Float>> {
        return ProviderNormalization.fallbackEmbeddings(input, dimensions = 256)
    }

    fun embedNormalized(settings: LlmSettings, request: ProviderEmbeddingRequest): ProviderEmbeddingResult {
        if (request.input.isEmpty()) {
            throw ProviderException(
                providerId = id,
                code = ProviderErrorCode.INVALID_REQUEST,
                message = "Embedding input cannot be empty",
                isRetriable = false
            )
        }
        val vectors = try {
            if (request.dimensions == 256) {
                embed(settings, request.input)
            } else {
                ProviderNormalization.fallbackEmbeddings(request.input, request.dimensions)
            }
        } catch (throwable: Throwable) {
            throw normalizeError(throwable)
        }
        val usage = NormalizedTokenUsage(
            promptTokens = request.input.sumOf { it.split(Regex("\\s+")).count { token -> token.isNotBlank() } },
            completionTokens = 0,
            totalTokens = request.input.sumOf { it.split(Regex("\\s+")).count { token -> token.isNotBlank() } }
        )
        return ProviderEmbeddingResult(vectors = vectors, usage = usage, providerId = id)
    }

    fun rerankNormalized(settings: LlmSettings, request: ProviderRerankRequest): ProviderRerankResult {
        if (request.documents.isEmpty()) {
            throw ProviderException(
                providerId = id,
                code = ProviderErrorCode.INVALID_REQUEST,
                message = "Rerank documents cannot be empty",
                isRetriable = false
            )
        }
        val items = try {
            ProviderNormalization.fallbackRerank(request.query, request.documents, request.topK)
        } catch (throwable: Throwable) {
            throw normalizeError(throwable)
        }
        val tokenEstimate = request.query.split(Regex("\\s+")).count { it.isNotBlank() } +
            request.documents.sumOf { doc -> doc.split(Regex("\\s+")).count { it.isNotBlank() } }
        return ProviderRerankResult(
            items = items,
            usage = NormalizedTokenUsage(
                promptTokens = tokenEstimate,
                completionTokens = 0,
                totalTokens = tokenEstimate
            ),
            providerId = id
        )
    }

    fun transcribe(settings: LlmSettings, audioFile: File): String {
        throw ProviderException(
            providerId = id,
            code = ProviderErrorCode.FEATURE_UNSUPPORTED,
            message = "Transcription not implemented for provider $id",
            isRetriable = false
        )
    }

    fun normalizeError(throwable: Throwable): ProviderException {
        return when (throwable) {
            is ProviderException -> throwable
            is IllegalArgumentException -> ProviderException(
                providerId = id,
                code = ProviderErrorCode.INVALID_REQUEST,
                message = throwable.message ?: "Invalid request",
                isRetriable = false,
                cause = throwable
            )
            else -> ProviderException(
                providerId = id,
                code = ProviderErrorCode.UNKNOWN,
                message = throwable.message ?: "Provider failure",
                isRetriable = false,
                cause = throwable
            )
        }
    }

    fun healthCheck(settings: LlmSettings): ProviderHealth
}
