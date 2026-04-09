package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.PrivacyMode

enum class PersonaRuntimePhase {
    IDLE,
    ACTIVATING,
    ACTIVE,
    INVOKING,
    DEACTIVATING,
    FAILED
}

enum class PersonaRuntimeErrorCode {
    PERSONA_NOT_FOUND,
    MANIFEST_VIOLATION,
    GOVERNANCE_BLOCKED,
    INVOCATION_FAILED,
    PROVIDER_FAILURE,
    TOOL_ORCHESTRATION_FAILURE,
    INTERNAL_ERROR
}

data class PersonaRuntimeError(
    val code: PersonaRuntimeErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap(),
    val cause: Throwable? = null
)

data class PersonaGovernanceDecision(
    val allowed: Boolean,
    val reason: String,
    val filteredProviderCount: Int = 0,
    val appliedPrivacyMode: PrivacyMode,
    val appliedClassification: DataClassification,
    val appliedFallbackOrder: LlmFallbackOrder
)

data class PersonaRuntimeStatus(
    val personaId: String?,
    val phase: PersonaRuntimePhase,
    val activeProviders: List<LlmProvider> = emptyList(),
    val governance: PersonaGovernanceDecision? = null,
    val lastError: PersonaRuntimeError? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class PersonaInvocationResult(
    val personaId: String,
    val outputText: String,
    val status: PersonaRuntimeStatus
)
