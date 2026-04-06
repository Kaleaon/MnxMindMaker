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
