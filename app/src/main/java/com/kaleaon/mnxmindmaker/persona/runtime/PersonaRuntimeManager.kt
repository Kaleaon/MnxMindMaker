package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.util.observability.CorrelationIds
import com.kaleaon.mnxmindmaker.util.observability.RequestTracer
import com.kaleaon.mnxmindmaker.util.observability.TraceStore

data class PersonaDeploymentContext(
    val personaVersion: String,
    val deploymentManifestHash: String,
    val correlationId: String
)

class PersonaRuntimeManager(
    private val traceStore: TraceStore,
    private val tracerFactory: (requestId: String) -> RequestTracer = { id -> RequestTracer(id) }
) {

    fun deployPersona(
        requestId: String,
        personaVersion: String,
        deploymentManifestHash: String,
        deployDurationMs: Long,
        shouldFail: Boolean = false,
        failureReason: String = ""
    ): PersonaDeploymentContext {
        val correlation = CorrelationIds.from(personaVersion, deploymentManifestHash)
        val tracer = tracerFactory(requestId)
        tracer.recordDeployInitiated(personaVersion, deploymentManifestHash)

        if (shouldFail) {
            tracer.recordDeployFailed(personaVersion, deploymentManifestHash, failureReason.ifBlank { "unknown" })
        } else {
            tracer.recordDeployCompleted(personaVersion, deploymentManifestHash, deployDurationMs)
        }

        traceStore.append(tracer.finish())
        return PersonaDeploymentContext(
            personaVersion = personaVersion,
            deploymentManifestHash = deploymentManifestHash,
            correlationId = correlation.correlationId
        )
    }

    fun activatePersona(
        requestId: String,
        context: PersonaDeploymentContext,
        provider: String,
        success: Boolean,
        failureReason: String = ""
    ) {
        val tracer = tracerFactory(requestId)
        if (success) {
            tracer.recordActivationSuccess(context.personaVersion, context.deploymentManifestHash, provider)
        } else {
            tracer.recordActivationFailure(
                context.personaVersion,
                context.deploymentManifestHash,
                provider,
                failureReason.ifBlank { "activation_failure" }
            )
        }
        traceStore.append(tracer.finish())
    }

    fun recordInvocation(
        requestId: String,
        context: PersonaDeploymentContext,
        provider: String,
        latencyMs: Long,
        success: Boolean,
        errorRate: Double
    ) {
        val tracer = tracerFactory(requestId)
        tracer.recordProviderInvocationMetric(
            personaVersion = context.personaVersion,
            deploymentManifestHash = context.deploymentManifestHash,
            provider = provider,
            latencyMs = latencyMs,
            success = success,
            errorRate = errorRate
        )
        traceStore.append(tracer.finish())
    }
}
import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.util.observability.RequestTracer
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import com.kaleaon.mnxmindmaker.util.provider.RoutingPolicy
import com.kaleaon.mnxmindmaker.util.tooling.ToolOrchestrator
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates persona activation + invocation while enforcing governance and deployment constraints.
 */
class PersonaRuntimeManager(
    private val providerRouter: ProviderRouter,
    private val toolOrchestratorFactory: (List<LlmSettings>, RoutingPolicy, RequestTracer?) -> ToolOrchestrator,
    private val settingsProvider: () -> List<LlmSettings>,
    private val privacyModeProvider: () -> PrivacyMode,
    private val manifestProvider: (String) -> PersonaDeploymentManifest?,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    private val activePersonas = ConcurrentHashMap<String, ActivePersona>()

    fun activatePersona(personaId: String): PersonaRuntimeStatus {
        updateStatus(personaId, PersonaRuntimePhase.ACTIVATING)

        val manifest = manifestProvider(personaId)
            ?: return fail(
                personaId = personaId,
                code = PersonaRuntimeErrorCode.PERSONA_NOT_FOUND,
                message = "No deployment manifest for persona '$personaId'"
            )

        val governance = applyGovernance(settingsProvider(), manifest)
        if (!governance.allowed) {
            return fail(
                personaId = personaId,
                code = PersonaRuntimeErrorCode.GOVERNANCE_BLOCKED,
                message = governance.reason,
                details = mapOf(
                    "privacy_mode" to governance.appliedPrivacyMode.name,
                    "classification" to governance.appliedClassification.name,
                    "fallback_order" to governance.appliedFallbackOrder.name
                )
            )
        }

        val allowedChain = buildAllowedChain(settingsProvider(), manifest, governance)
        if (allowedChain.isEmpty()) {
            return fail(
                personaId = personaId,
                code = PersonaRuntimeErrorCode.MANIFEST_VIOLATION,
                message = "No providers satisfy deployment manifest constraints"
            )
        }

        activePersonas[personaId] = ActivePersona(
            personaId = personaId,
            manifest = manifest,
            governedChain = allowedChain,
            governanceDecision = governance
        )

        return PersonaRuntimeStatus(
            personaId = personaId,
            phase = PersonaRuntimePhase.ACTIVE,
            activeProviders = allowedChain.map { it.provider },
            governance = governance,
            updatedAtEpochMs = nowMs()
        )
    }

    fun activatePersona(mnxFile: File): PersonaRuntimeStatus {
        val personaId = mnxFile.nameWithoutExtension
        return activatePersona(personaId)
    }

    fun deactivatePersona(personaId: String): PersonaRuntimeStatus {
        updateStatus(personaId, PersonaRuntimePhase.DEACTIVATING)
        activePersonas.remove(personaId)
        return PersonaRuntimeStatus(
            personaId = personaId,
            phase = PersonaRuntimePhase.IDLE,
            updatedAtEpochMs = nowMs()
        )
    }

    suspend fun invokePersona(personaId: String, userInput: String): PersonaInvocationResult {
        val active = activePersonas[personaId]
            ?: throw PersonaRuntimeException(
                PersonaRuntimeError(
                    code = PersonaRuntimeErrorCode.PERSONA_NOT_FOUND,
                    message = "Persona '$personaId' is not active"
                )
            )

        val invocationTracer = RequestTracer("persona:$personaId:${nowMs()}")
        updateStatus(personaId, PersonaRuntimePhase.INVOKING)

        val routingPolicy = RoutingPolicy(
            userPreference = active.governedChain.firstOrNull()?.provider,
            allowOfflineFallback = active.governanceDecision.appliedPrivacyMode != PrivacyMode.STRICT_LOCAL_ONLY
        )

        return runCatching {
            val orchestrator = toolOrchestratorFactory(active.governedChain, routingPolicy, invocationTracer)
            val output = orchestrator.run(
                systemPrompt = buildSystemPrompt(active.manifest),
                userPrompt = userInput
            )
            val status = PersonaRuntimeStatus(
                personaId = personaId,
                phase = PersonaRuntimePhase.ACTIVE,
                activeProviders = active.governedChain.map { it.provider },
                governance = active.governanceDecision,
                updatedAtEpochMs = nowMs()
            )
            PersonaInvocationResult(personaId = personaId, outputText = output, status = status)
        }.getOrElse { error ->
            val runtimeError = PersonaRuntimeError(
                code = if (error is PersonaRuntimeException) error.error.code else PersonaRuntimeErrorCode.INVOCATION_FAILED,
                message = error.message ?: "Unknown invocation failure",
                cause = error
            )
            val status = PersonaRuntimeStatus(
                personaId = personaId,
                phase = PersonaRuntimePhase.FAILED,
                activeProviders = active.governedChain.map { it.provider },
                governance = active.governanceDecision,
                lastError = runtimeError,
                updatedAtEpochMs = nowMs()
            )
            throw PersonaRuntimeException(runtimeError, status)
        }
    }

    fun getStatus(personaId: String): PersonaRuntimeStatus {
        val active = activePersonas[personaId]
        return if (active == null) {
            PersonaRuntimeStatus(personaId = personaId, phase = PersonaRuntimePhase.IDLE, updatedAtEpochMs = nowMs())
        } else {
            PersonaRuntimeStatus(
                personaId = personaId,
                phase = PersonaRuntimePhase.ACTIVE,
                activeProviders = active.governedChain.map { it.provider },
                governance = active.governanceDecision,
                updatedAtEpochMs = nowMs()
            )
        }
    }

    private fun applyGovernance(
        allSettings: List<LlmSettings>,
        manifest: PersonaDeploymentManifest
    ): PersonaGovernanceDecision {
        val privacy = manifest.enforcedPrivacyMode ?: privacyModeProvider()
        val maxClassification = manifest.maxOutboundClassification
        val fallbackOrder = manifest.fallbackOrder ?: inferFallbackOrder(allSettings)

        val filtered = allSettings
            .asSequence()
            .filter { it.enabled }
            .filter { classificationRank(it.outboundClassification) <= classificationRank(maxClassification) }
            .filter {
                when (privacy) {
                    PrivacyMode.STRICT_LOCAL_ONLY -> it.provider == LlmProvider.LOCAL_ON_DEVICE
                    PrivacyMode.HYBRID -> true
                }
            }
            .toList()

        if (filtered.isEmpty()) {
            return PersonaGovernanceDecision(
                allowed = false,
                reason = "No provider passed governance gates",
                appliedPrivacyMode = privacy,
                appliedClassification = maxClassification,
                appliedFallbackOrder = fallbackOrder
            )
        }

        return PersonaGovernanceDecision(
            allowed = true,
            reason = "Governance checks passed",
            filteredProviderCount = filtered.size,
            appliedPrivacyMode = privacy,
            appliedClassification = maxClassification,
            appliedFallbackOrder = fallbackOrder
        )
    }

    private fun buildAllowedChain(
        allSettings: List<LlmSettings>,
        manifest: PersonaDeploymentManifest,
        governance: PersonaGovernanceDecision
    ): List<LlmSettings> {
        val providerScoped = allSettings.filter { settings ->
            manifest.allowedProviders.isEmpty() || settings.provider in manifest.allowedProviders
        }

        val route = providerRouter.selectGovernedChain(
            settingsChain = providerScoped,
            privacyMode = governance.appliedPrivacyMode,
            maxClassification = governance.appliedClassification,
            fallbackOrder = governance.appliedFallbackOrder
        )

        return route.settings
    }

    private fun inferFallbackOrder(settings: List<LlmSettings>): LlmFallbackOrder {
        return settings.firstOrNull { it.provider == LlmProvider.LOCAL_ON_DEVICE }?.fallbackOrder
            ?: LlmFallbackOrder.REMOTE_ONLY
    }

    private fun classificationRank(classification: DataClassification): Int = when (classification) {
        DataClassification.PUBLIC -> 0
        DataClassification.SENSITIVE -> 1
        DataClassification.RESTRICTED -> 2
    }

    private fun buildSystemPrompt(manifest: PersonaDeploymentManifest): String {
        return buildString {
            appendLine("You are persona '${manifest.personaId}'.")
            appendLine("Respect deployment constraints and governance gates at all times.")
            appendLine("Allowed providers: ${manifest.allowedProviders.ifEmpty { LlmProvider.entries.toSet() }.joinToString { it.name }}")
            appendLine("Max classification: ${manifest.maxOutboundClassification.name}")
            appendLine("Tools enabled: ${manifest.allowTools}")
        }
    }

    private fun updateStatus(personaId: String, phase: PersonaRuntimePhase) {
        val existing = activePersonas[personaId] ?: return
        activePersonas[personaId] = existing.copy(lastPhase = phase)
    }

    private fun fail(
        personaId: String,
        code: PersonaRuntimeErrorCode,
        message: String,
        details: Map<String, String> = emptyMap()
    ): PersonaRuntimeStatus {
        return PersonaRuntimeStatus(
            personaId = personaId,
            phase = PersonaRuntimePhase.FAILED,
            lastError = PersonaRuntimeError(code = code, message = message, details = details),
            updatedAtEpochMs = nowMs()
        )
    }

    private data class ActivePersona(
        val personaId: String,
        val manifest: PersonaDeploymentManifest,
        val governedChain: List<LlmSettings>,
        val governanceDecision: PersonaGovernanceDecision,
        val lastPhase: PersonaRuntimePhase = PersonaRuntimePhase.ACTIVE
    )
}

class PersonaRuntimeException(
    val error: PersonaRuntimeError,
    val status: PersonaRuntimeStatus? = null
) : RuntimeException(error.message, error.cause)
