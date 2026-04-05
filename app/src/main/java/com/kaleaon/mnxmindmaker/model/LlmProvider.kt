package com.kaleaon.mnxmindmaker.model

/**
 * Execution runtime for a provider.
 */
enum class LlmRuntime {
    REMOTE_API,
    LOCAL_ON_DEVICE
}

/**
 * Global privacy modes.
 */
enum class PrivacyMode {
    STRICT_LOCAL_ONLY,
    HYBRID
}

/**
 * End-to-end classification tags used for outbound data governance.
 */
enum class DataClassification {
    PUBLIC,
    SENSITIVE,
    RESTRICTED
}

/**
 * Preferred resolution order when local inference is enabled.
 */
enum class LlmFallbackOrder {
    REMOTE_ONLY,
    LOCAL_FIRST_REMOTE_FALLBACK
}

/**
 * Local profile presets trade quality for speed / memory.
 */
enum class LocalModelProfile(val displayName: String, val contextWindowTokens: Int) {
    FAST("Fast (4k)", 4096),
    BALANCED("Balanced (8k)", 8192),
    QUALITY("Quality (16k)", 16384)
}

/**
 * Capability flags used by higher-level features (tool planning, packet generation).
 */
data class LlmCapabilityFlags(
    val supportsToolPlanning: Boolean,
    val supportsPacketGeneration: Boolean,
    val supportsTokenStreaming: Boolean,
    val supportsCancellation: Boolean,
    val contextWindowTokens: Int
)

/**
 * Supported external LLM providers.
 */
enum class LlmProvider(
    val displayName: String,
    val baseUrl: String,
    val requiresApiKey: Boolean = true,
    val runtime: LlmRuntime = LlmRuntime.REMOTE_API
) {
    ANTHROPIC("Anthropic (Claude)", "https://api.anthropic.com/v1"),
    OPENAI("OpenAI (GPT)", "https://api.openai.com/v1"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
    VLLM_GEMMA4("vLLM Gemma 4 (Self-hosted)", "http://10.0.2.2:8000/v1", requiresApiKey = false),
    LOCAL_ON_DEVICE("Local On-Device Runtime", "http://10.0.2.2:8000/v1", requiresApiKey = false, runtime = LlmRuntime.LOCAL_ON_DEVICE)
}

/**
 * Configuration for a single LLM provider API.
 */
data class LlmSettings(
    val provider: LlmProvider,
    val apiKey: String = "",
    val model: String = provider.defaultModel(),
    val baseUrl: String = provider.baseUrl,
    val enabled: Boolean = false,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val localModelPath: String = "",
    val localProfile: LocalModelProfile = LocalModelProfile.BALANCED,
    val fallbackOrder: LlmFallbackOrder = LlmFallbackOrder.REMOTE_ONLY,
    val outboundClassification: DataClassification = DataClassification.SENSITIVE,
    val tlsPinnedSpkiSha256: String = ""
) {
    val capabilities: LlmCapabilityFlags
        get() = provider.defaultCapabilities(localProfile)
}

fun LlmProvider.defaultModel(): String = when (this) {
    LlmProvider.ANTHROPIC -> "claude-3-5-sonnet-20241022"
    LlmProvider.OPENAI -> "gpt-4o"
    LlmProvider.GEMINI -> "gemini-1.5-pro"
    LlmProvider.VLLM_GEMMA4 -> "google/gemma-4-E4B-it"
    LlmProvider.LOCAL_ON_DEVICE -> "local/default"
}

fun LlmProvider.defaultCapabilities(profile: LocalModelProfile = LocalModelProfile.BALANCED): LlmCapabilityFlags {
    return when (this) {
        LlmProvider.ANTHROPIC,
        LlmProvider.OPENAI,
        LlmProvider.GEMINI,
        LlmProvider.VLLM_GEMMA4 -> LlmCapabilityFlags(
            supportsToolPlanning = true,
            supportsPacketGeneration = true,
            supportsTokenStreaming = true,
            supportsCancellation = true,
            contextWindowTokens = 128000
        )

        LlmProvider.LOCAL_ON_DEVICE -> LlmCapabilityFlags(
            supportsToolPlanning = false,
            supportsPacketGeneration = true,
            supportsTokenStreaming = true,
            supportsCancellation = true,
            contextWindowTokens = profile.contextWindowTokens
        )
    }
}

/**
 * A prompt request to an LLM for AI mind design assistance.
 */
data class LlmRequest(
    val provider: LlmProvider,
    val systemPrompt: String,
    val userMessage: String
)
