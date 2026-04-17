package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.MindGraph
import org.json.JSONObject

/** Risk class for policy + UI approval messaging. */
enum class ToolRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

/** Backward-compatible operation class used by tests/policy. */
enum class ToolOperationClass {
    READ_ONLY,
    MUTATING,
    HIGH_RISK
}

enum class PolicyDecisionType {
    ALLOW,
    REQUIRE_USER_APPROVAL,
    DENY
}

/**
 * Canonical tool contract.
 *
 * Contract fields intentionally map to LLM function/tool schemas:
 * - name
 * - schema (inputSchema)
 * - execute (handler/ToolHandler)
 * - risk_level (riskLevel)
 * - requires_confirmation (requiresConfirmation)
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val operationClass: ToolOperationClass,
    val inputSchema: JSONObject = JSONObject(),
    val riskLevel: ToolRiskLevel = when (operationClass) {
        ToolOperationClass.READ_ONLY -> ToolRiskLevel.LOW
        ToolOperationClass.MUTATING -> ToolRiskLevel.MEDIUM
        ToolOperationClass.HIGH_RISK -> ToolRiskLevel.HIGH
    },
    val requiresConfirmation: Boolean = operationClass != ToolOperationClass.READ_ONLY,
    val execute: ToolHandler? = null
)

data class PersonaPolicy(
    val allowToolNames: Set<String> = emptySet(),
    val denyToolNames: Set<String> = emptySet(),
    val strictLocalOnly: Boolean = false
)

data class DeploymentManifestPolicy(
    val defaultPersonaId: String? = null,
    val personaPolicies: Map<String, PersonaPolicy> = emptyMap()
) {
    fun policyForPersona(personaId: String?): PersonaPolicy? {
        val resolvedPersonaId = personaId ?: defaultPersonaId ?: return null
        return personaPolicies[resolvedPersonaId]
    }
}

data class ToolPolicyContext(
    val personaId: String? = null,
    val deploymentPolicy: DeploymentManifestPolicy? = null,
    val declarativePolicies: DeclarativeRuntimePolicies? = null
)

enum class PolicyDefaultDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY
}

enum class SensitivityLevel {
    LOW,
    MEDIUM,
    HIGH,
    RESTRICTED
}

data class ToolPermissionPolicy(
    val allowToolNames: Set<String> = emptySet(),
    val denyToolNames: Set<String> = emptySet(),
    val defaultDecision: PolicyDefaultDecision = PolicyDefaultDecision.REQUIRE_APPROVAL
)

data class DataEgressPolicy(
    val allowRemoteEgress: Boolean = true,
    val allowHosts: Set<String> = emptySet(),
    val denyHosts: Set<String> = emptySet(),
    val maxDataClassification: DataClassification = DataClassification.RESTRICTED
)

data class ProviderSelectionPolicy(
    val allowProviders: Set<LlmProvider> = emptySet(),
    val denyProviders: Set<LlmProvider> = emptySet(),
    val requireLocalRuntime: Boolean = false
)

data class SensitivityConstraintPolicy(
    val maxSensitivity: SensitivityLevel = SensitivityLevel.RESTRICTED
)

data class AllowedActionPolicy(
    val allowActions: Set<String> = emptySet(),
    val denyActions: Set<String> = emptySet()
)

/** Declarative runtime policy profile applied before model/tool operations. */
data class DeclarativeRuntimePolicies(
    val toolPermissions: ToolPermissionPolicy = ToolPermissionPolicy(),
    val dataEgress: DataEgressPolicy = DataEgressPolicy(),
    val providerSelection: ProviderSelectionPolicy = ProviderSelectionPolicy(),
    val sensitivity: SensitivityConstraintPolicy = SensitivityConstraintPolicy(),
    val allowedActions: AllowedActionPolicy = AllowedActionPolicy()
)

data class ModelOperation(
    val provider: LlmProvider,
    val baseUrl: String,
    val dataClassification: DataClassification
)

data class ToolInvocation(
    val id: String,
    val toolName: String,
    val argumentsJson: JSONObject = JSONObject()
) {
    // Compatibility aliases used by older call sites.
    val name: String get() = toolName
    val arguments: JSONObject get() = argumentsJson
}

data class PolicyDecision(
    val type: PolicyDecisionType,
    val reason: String,
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW,
    val requiresConfirmation: Boolean = type == PolicyDecisionType.REQUIRE_USER_APPROVAL,
    val explicitActionType: String? = null
)

data class ToolResult(
    val toolUseId: String,
    val isError: Boolean,
    val contentJson: JSONObject,
    val rolledBack: Boolean = false
) {
    constructor(
        toolCallId: String,
        toolName: String,
        success: Boolean,
        outputText: String,
        outputJson: JSONObject = JSONObject()
    ) : this(
        toolUseId = toolCallId,
        isError = !success,
        contentJson = JSONObject(outputJson.toString())
            .put("tool_name", toolName)
            .put("output_text", outputText)
    )

    // Compatibility aliases used by old orchestration loop.
    val toolCallId: String get() = toolUseId
    val toolName: String get() = contentJson.optString("tool_name")
    val success: Boolean get() = !isError
    val outputText: String get() = contentJson.toString()
    val outputJson: JSONObject get() = contentJson
}


data class MutationSequenceResult(
    val graph: MindGraph,
    val toolResults: List<ToolResult>,
    val rolledBack: Boolean
)

data class ToolExecutionOutcome(
    val contentJson: JSONObject,
    val mutatedGraph: Boolean
)

fun interface ToolHandler {
    fun execute(invocation: ToolInvocation, graph: MindGraph): ToolExecutionOutcome
}
