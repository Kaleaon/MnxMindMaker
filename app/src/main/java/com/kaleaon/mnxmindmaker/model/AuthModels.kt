package com.kaleaon.mnxmindmaker.model

enum class IdentityMode {
    LOCAL_AUTH,
    EXTERNAL_LINKED
}

data class LocalAuthSession(
    val sessionToken: String,
    val email: String,
    val expiresAtEpochMs: Long
)

enum class ExternalProvider(val displayName: String) {
    CLAUDE("Claude"),
    CHATGPT("ChatGPT"),
    HUGGING_FACE("Hugging Face")
}

data class ProviderCapabilityMetadata(
    val models: List<String> = emptyList(),
    val supportsToolUse: Boolean = false,
    val rateLimitInfo: String = "Unknown",
    val detectedAtEpochMs: Long = System.currentTimeMillis()
)

data class ExternalAccountLink(
    val provider: ExternalProvider,
    val linked: Boolean,
    val expiresAtEpochMs: Long?,
    val capabilities: ProviderCapabilityMetadata?
)
