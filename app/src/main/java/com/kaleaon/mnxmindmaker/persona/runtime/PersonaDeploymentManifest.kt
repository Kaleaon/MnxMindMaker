package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.PrivacyMode

data class PersonaDeploymentManifest(
    val personaId: String = "unknown",
    val allowedProviders: Set<LlmProvider> = emptySet(),
    val maxOutboundClassification: DataClassification = DataClassification.SENSITIVE,
    val enforcedPrivacyMode: PrivacyMode? = null,
    val fallbackOrder: LlmFallbackOrder? = null,
    val allowTools: Boolean = true,
    val target: RuntimeTarget = RuntimeTarget(),
    val inference: InferenceParams = InferenceParams(),
    val toolPolicy: ToolPolicy = ToolPolicy(),
    val classification: ClassificationPrivacyConstraints = ClassificationPrivacyConstraints(),
    val fallback: FallbackStrategy = FallbackStrategy()
) {
    companion object {
        fun defaults(): PersonaDeploymentManifest = PersonaDeploymentManifest()
    }
}

data class RuntimeTarget(
    val provider: String = "unspecified",
    val model: String = "default",
    val runtime: String = "general"
)

data class InferenceParams(
    val temperature: Double = 0.7,
    val maxTokens: Int = 1024,
    val contextWindow: Int = 8192
)

data class ToolPolicy(
    val allowlist: List<String> = emptyList(),
    val policy: String = "deny_by_default"
)

data class ClassificationPrivacyConstraints(
    val classification: String = "internal",
    val privacy: String = "standard"
)

data class FallbackStrategy(
    val mode: String = "none",
    val target: String = "none",
    val maxRetries: Int = 0
)
