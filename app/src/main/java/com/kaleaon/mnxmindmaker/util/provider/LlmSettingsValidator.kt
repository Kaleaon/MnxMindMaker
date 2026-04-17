package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalRuntimeEngine
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ValidationSeverity {
    WARNING,
    CRITICAL
}

data class ValidationIssue(
    val severity: ValidationSeverity,
    val field: String,
    val message: String
)

fun validate(settings: LlmSettings): List<ValidationIssue> = validate(settings, null)

fun validate(settings: LlmSettings, privacyMode: PrivacyMode?): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()

    if (settings.model.isBlank()) {
        issues += ValidationIssue(ValidationSeverity.CRITICAL, "model", "Model is required.")
    }

    val baseUrl = settings.baseUrl.trim()
    val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
    if (parsedBaseUrl == null || parsedBaseUrl.scheme !in listOf("http", "https")) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "baseUrl",
            "Base URL must be a valid http/https URL."
        )
    }

    if (settings.maxTokens <= 0) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "maxTokens",
            "Max tokens must be greater than 0."
        )
    }

    val contextWindow = settings.runtimeControls.contextWindowTokens
    if (contextWindow < 256 || contextWindow > 1_048_576) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "contextWindowTokens",
            "Context window must be between 256 and 1,048,576 tokens."
        )
    }

    if (settings.maxTokens > contextWindow) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "maxTokens",
            "Max tokens cannot exceed context window tokens."
        )
    }

    if (settings.wakeUpTokenBudget < 0) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "wakeUpTokenBudget",
            "Wake-up token budget cannot be negative."
        )
    }

    if (settings.wakeUpTokenBudget > contextWindow) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "wakeUpTokenBudget",
            "Wake-up token budget cannot exceed context window tokens."
        )
    }

    try {
        TlsPinParser.normalizeOrThrow(settings.tlsPinnedSpkiSha256)
    } catch (_: IllegalArgumentException) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "tlsPinnedSpkiSha256",
            TlsPinParser.VALIDATION_MESSAGE
        )
    }

    if (privacyMode == PrivacyMode.STRICT_LOCAL_ONLY && settings.provider.runtime != LlmRuntime.LOCAL_ON_DEVICE && settings.enabled) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "provider",
            "Privacy mode Strict local-only does not allow enabling remote providers."
        )
    }

    if (settings.provider.requiresApiKey && settings.enabled && settings.apiKey.isBlank()) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "apiKey",
            "API key is required when this provider is enabled."
        )
    }

    if (settings.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE && settings.enabled && settings.localModelPath.isBlank()) {
        issues += ValidationIssue(
            ValidationSeverity.CRITICAL,
            "localModelPath",
            "Local model path is required when local runtime is enabled."
        )
    }

    if (
        settings.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE &&
        settings.runtimeControls.engine == LocalRuntimeEngine.LITERT_LM &&
        settings.localModelPath.isNotBlank() &&
        !settings.localModelPath.endsWith(".litertlm", ignoreCase = true)
    ) {
        issues += ValidationIssue(
            ValidationSeverity.WARNING,
            "localModelPath",
            "LiteRT-LM usually expects a .litertlm package path (or a runtime alias mapped by your bridge)."
        )
    }

    return issues
}

fun validateRuntimeEndpoint(endpoint: String): List<ValidationIssue> {
    if (endpoint.isBlank()) return emptyList()
    val parsed = endpoint.trim().toHttpUrlOrNull()
    if (parsed == null || parsed.scheme !in listOf("http", "https")) {
        return listOf(
            ValidationIssue(
                ValidationSeverity.CRITICAL,
                "runtimeEndpoint",
                "Runtime endpoint must be a valid http/https URL."
            )
        )
    }
    return emptyList()
}
