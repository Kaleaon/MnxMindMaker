package com.kaleaon.mnxmindmaker.model

/**
 * Supported external LLM providers.
 */
enum class LlmProvider(val displayName: String, val baseUrl: String) {
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com/v1"),
    OPENAI("OpenAI (GPT)", "https://api.openai.com/v1"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta")
}

/**
 * Configuration for a single LLM provider API.
 */
data class LlmSettings(
    val provider: LlmProvider,
    val apiKey: String = "",
    val model: String = provider.defaultModel(),
    val enabled: Boolean = false,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f
)

fun LlmProvider.defaultModel(): String = when (this) {
    LlmProvider.ANTHROPIC -> "claude-3-5-sonnet-20241022"
    LlmProvider.OPENAI -> "gpt-4o"
    LlmProvider.GEMINI -> "gemini-1.5-pro"
}

/**
 * A prompt request to an LLM for AI mind design assistance.
 */
data class LlmRequest(
    val provider: LlmProvider,
    val systemPrompt: String,
    val userMessage: String
)
