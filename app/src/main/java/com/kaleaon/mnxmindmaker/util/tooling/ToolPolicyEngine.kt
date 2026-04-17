package com.kaleaon.mnxmindmaker.util.tooling

import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import java.net.URI

/**
 * Evaluates whether a tool call can run, requires user approval, or must be denied.
 */
class ToolPolicyEngine(
    private val specsByName: Map<String, ToolSpec>
) {

    fun evaluate(invocation: ToolInvocation): PolicyDecision = evaluate(invocation, MindGraph(), ToolPolicyContext())

    fun evaluate(invocation: ToolInvocation, graph: MindGraph): PolicyDecision =
        evaluate(invocation, graph, ToolPolicyContext())

    fun evaluate(
        invocation: ToolInvocation,
        graph: MindGraph,
        context: ToolPolicyContext
    ): PolicyDecision {
        val spec = specsByName[invocation.toolName]
            ?: return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Unknown tool '${invocation.toolName}' denied by default",
                riskLevel = ToolRiskLevel.HIGH
            )

        val personaPolicy = context.deploymentPolicy?.policyForPersona(context.personaId)
        if (invocation.toolName in (personaPolicy?.denyToolNames ?: emptySet())) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Tool '${invocation.toolName}' denied by persona policy",
                riskLevel = spec.riskLevel
            )
        }
        if ((personaPolicy?.allowToolNames ?: emptySet()).isNotEmpty() &&
            invocation.toolName !in (personaPolicy?.allowToolNames ?: emptySet())
        ) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Tool '${invocation.toolName}' not in persona allowlist",
                riskLevel = spec.riskLevel
            )
        }

        val explicitAction = inferExplicitActionType(invocation.toolName)
        val declarativeDecision = evaluateDeclarativeToolPolicies(invocation, spec, explicitAction, context)
        if (declarativeDecision != null) return declarativeDecision

        val targetedNodes = extractTargetNodeIds(invocation)
            .mapNotNull { id -> graph.nodes.firstOrNull { it.id == id } }

        val hasInvariantTarget = targetedNodes.any {
            it.attributes[PROTECTION_LEVEL_KEY].equals(PROTECTION_INVARIANT, ignoreCase = true)
        }
        if (hasInvariantTarget && spec.operationClass != ToolOperationClass.READ_ONLY) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Writes to invariant nodes are denied",
                riskLevel = ToolRiskLevel.HIGH,
                explicitActionType = explicitAction
            )
        }

        val hasProtectedTarget = targetedNodes.any {
            it.attributes[PROTECTION_LEVEL_KEY].equals(PROTECTION_PROTECTED, ignoreCase = true)
        }

        val requiresApprovalByRisk = spec.requiresConfirmation || explicitAction != null

        return when {
            spec.operationClass == ToolOperationClass.READ_ONLY && !requiresApprovalByRisk -> PolicyDecision(
                type = PolicyDecisionType.ALLOW,
                reason = "Read-only tool",
                riskLevel = spec.riskLevel
            )

            hasProtectedTarget && spec.operationClass != ToolOperationClass.READ_ONLY -> PolicyDecision(
                type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                reason = "Mutating protected nodes requires explicit user approval",
                riskLevel = spec.riskLevel,
                requiresConfirmation = true,
                explicitActionType = explicitAction
            )

            else -> PolicyDecision(
                type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                reason = when {
                    explicitAction != null -> "High-risk '${explicitAction}' action requires explicit user approval"
                    spec.operationClass == ToolOperationClass.HIGH_RISK -> "High-risk tool requires explicit user approval"
                    else -> "Mutating tool requires explicit user approval"
                },
                riskLevel = spec.riskLevel,
                requiresConfirmation = true,
                explicitActionType = explicitAction
            )
        }
    }

    fun evaluateModelOperation(
        operation: ModelOperation,
        context: ToolPolicyContext = ToolPolicyContext()
    ): PolicyDecision {
        val policies = context.declarativePolicies ?: return PolicyDecision(
            type = PolicyDecisionType.ALLOW,
            reason = "No declarative runtime policy configured",
            riskLevel = ToolRiskLevel.LOW
        )

        val providerPolicy = policies.providerSelection
        if (operation.provider in providerPolicy.denyProviders) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Provider '${operation.provider.name}' denied by provider selection policy",
                riskLevel = ToolRiskLevel.HIGH
            )
        }
        if (providerPolicy.allowProviders.isNotEmpty() && operation.provider !in providerPolicy.allowProviders) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Provider '${operation.provider.name}' not in provider allowlist",
                riskLevel = ToolRiskLevel.HIGH
            )
        }
        if (providerPolicy.requireLocalRuntime && operation.provider.runtime != LlmRuntime.LOCAL_ON_DEVICE) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Remote provider denied by requireLocalRuntime policy",
                riskLevel = ToolRiskLevel.HIGH
            )
        }

        val egressDecision = evaluateEgress(operation.baseUrl, operation.dataClassification, policies)
        if (egressDecision != null) return egressDecision

        val actionDecision = evaluateAction("model_inference", policies)
        if (actionDecision != null) return actionDecision

        return PolicyDecision(
            type = PolicyDecisionType.ALLOW,
            reason = "Model operation allowed by declarative policies",
            riskLevel = ToolRiskLevel.LOW
        )
    }

    private fun evaluateDeclarativeToolPolicies(
        invocation: ToolInvocation,
        spec: ToolSpec,
        explicitAction: String?,
        context: ToolPolicyContext
    ): PolicyDecision? {
        val policies = context.declarativePolicies ?: return null
        val permissions = policies.toolPermissions

        if (invocation.toolName in permissions.denyToolNames) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Tool '${invocation.toolName}' denied by declarative tool permission policy",
                riskLevel = spec.riskLevel,
                explicitActionType = explicitAction
            )
        }
        if (permissions.allowToolNames.isNotEmpty() && invocation.toolName !in permissions.allowToolNames) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Tool '${invocation.toolName}' not in declarative tool allowlist",
                riskLevel = spec.riskLevel,
                explicitActionType = explicitAction
            )
        }

        val sensitivityDecision = evaluateToolSensitivity(invocation, policies)
        if (sensitivityDecision != null) return sensitivityDecision

        val egressDecision = evaluateEgress(
            rawEndpoint = extractEgressEndpoint(invocation),
            dataClassification = classifySensitivity(invocation.argumentsJson.optString("sensitivity", "low")),
            policies = policies
        )
        if (egressDecision != null) return egressDecision

        val inferredAction = explicitAction ?: inferActionFromToolClass(spec.operationClass)
        val actionDecision = evaluateAction(inferredAction, policies)
        if (actionDecision != null) return actionDecision

        return when (permissions.defaultDecision) {
            PolicyDefaultDecision.ALLOW -> null
            PolicyDefaultDecision.DENY -> PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Default declarative tool permission is deny",
                riskLevel = spec.riskLevel,
                explicitActionType = explicitAction
            )
            PolicyDefaultDecision.REQUIRE_APPROVAL -> PolicyDecision(
                type = PolicyDecisionType.REQUIRE_USER_APPROVAL,
                reason = "Default declarative tool permission requires explicit approval",
                riskLevel = spec.riskLevel,
                requiresConfirmation = true,
                explicitActionType = explicitAction
            )
        }
    }

    private fun evaluateToolSensitivity(
        invocation: ToolInvocation,
        policies: DeclarativeRuntimePolicies
    ): PolicyDecision? {
        val declared = invocation.argumentsJson.optString("sensitivity")
        if (declared.isBlank()) return null
        val sensitivity = parseSensitivityLevel(declared) ?: return null
        if (sensitivity.ordinal <= policies.sensitivity.maxSensitivity.ordinal) return null
        return PolicyDecision(
            type = PolicyDecisionType.DENY,
            reason = "Sensitivity '$sensitivity' exceeds declarative max '${policies.sensitivity.maxSensitivity}'",
            riskLevel = ToolRiskLevel.HIGH
        )
    }

    private fun evaluateEgress(
        rawEndpoint: String?,
        dataClassification: DataClassification,
        policies: DeclarativeRuntimePolicies
    ): PolicyDecision? {
        val egress = policies.dataEgress
        if (classificationRank(dataClassification) > classificationRank(egress.maxDataClassification)) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Data classification '$dataClassification' exceeds egress max '${egress.maxDataClassification}'",
                riskLevel = ToolRiskLevel.HIGH
            )
        }

        val endpoint = rawEndpoint?.trim().orEmpty()
        if (endpoint.isBlank()) return null
        val host = runCatching { URI(endpoint).host?.lowercase() }.getOrNull().orEmpty()

        if (!egress.allowRemoteEgress && host.isNotBlank()) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Remote egress denied by declarative policy",
                riskLevel = ToolRiskLevel.HIGH
            )
        }
        if (host.isNotBlank() && host in egress.denyHosts.map { it.lowercase() }.toSet()) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Host '$host' denied by egress policy",
                riskLevel = ToolRiskLevel.HIGH
            )
        }
        if (egress.allowHosts.isNotEmpty() && host.isNotBlank() &&
            host !in egress.allowHosts.map { it.lowercase() }.toSet()
        ) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Host '$host' not in egress allowlist",
                riskLevel = ToolRiskLevel.HIGH
            )
        }
        return null
    }

    private fun evaluateAction(action: String?, policies: DeclarativeRuntimePolicies): PolicyDecision? {
        val normalized = action?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return null
        val actionPolicy = policies.allowedActions
        if (normalized in actionPolicy.denyActions.map { it.lowercase() }.toSet()) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Action '$normalized' denied by allowed-action policy",
                riskLevel = ToolRiskLevel.HIGH,
                explicitActionType = normalized
            )
        }
        if (actionPolicy.allowActions.isNotEmpty() &&
            normalized !in actionPolicy.allowActions.map { it.lowercase() }.toSet()
        ) {
            return PolicyDecision(
                type = PolicyDecisionType.DENY,
                reason = "Action '$normalized' not in allowed-action policy allowlist",
                riskLevel = ToolRiskLevel.HIGH,
                explicitActionType = normalized
            )
        }
        return null
    }

    private fun inferExplicitActionType(toolName: String): String? {
        val name = toolName.lowercase()
        return when {
            "delete" in name || "remove" in name -> "delete"
            "send" in name || "post" in name -> "send"
            "execute" in name || "command" in name || "terminal" in name -> "execute"
            else -> null
        }
    }

    private fun inferActionFromToolClass(operationClass: ToolOperationClass): String = when (operationClass) {
        ToolOperationClass.READ_ONLY -> "read"
        ToolOperationClass.MUTATING -> "write"
        ToolOperationClass.HIGH_RISK -> "execute"
    }

    private fun parseSensitivityLevel(raw: String): SensitivityLevel? {
        return runCatching { SensitivityLevel.valueOf(raw.trim().uppercase()) }.getOrNull()
    }

    private fun classifySensitivity(raw: String): DataClassification = when (parseSensitivityLevel(raw)) {
        SensitivityLevel.RESTRICTED -> DataClassification.RESTRICTED
        SensitivityLevel.HIGH, SensitivityLevel.MEDIUM -> DataClassification.SENSITIVE
        else -> DataClassification.PUBLIC
    }

    private fun extractEgressEndpoint(invocation: ToolInvocation): String? {
        val args = invocation.argumentsJson
        val keys = listOf("url", "uri", "endpoint", "host", "base_url")
        return keys.firstNotNullOfOrNull { key ->
            args.optString(key).takeIf { it.isNotBlank() }
        }
    }

    private fun classificationRank(classification: DataClassification): Int = when (classification) {
        DataClassification.PUBLIC -> 0
        DataClassification.SENSITIVE -> 1
        DataClassification.RESTRICTED -> 2
    }

    companion object {
        const val PROTECTION_LEVEL_KEY = "protection_level"
        const val PROTECTION_PROTECTED = "protected"
        const val PROTECTION_INVARIANT = "invariant"

        fun extractTargetNodeIds(invocation: ToolInvocation): List<String> {
            val args = invocation.argumentsJson
            val keys = listOf("node_id", "from_id", "to_id", "parent_id")
            val ids = mutableListOf<String>()
            keys.forEach { key ->
                val value = args.optString(key)
                if (value.isNotBlank()) ids += value
            }

            val nodeIds = args.optJSONArray("node_ids")
            if (nodeIds != null) {
                for (i in 0 until nodeIds.length()) {
                    val v = nodeIds.optString(i)
                    if (v.isNotBlank()) ids += v
                }
            }
            return ids
        }
    }
}
