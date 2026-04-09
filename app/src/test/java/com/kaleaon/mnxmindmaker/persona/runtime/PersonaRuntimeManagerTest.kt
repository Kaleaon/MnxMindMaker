package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.util.observability.InMemoryTraceStore
import com.kaleaon.mnxmindmaker.util.observability.TraceEventType
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonaRuntimeManagerTest {

    @Test
    fun `deployment tracer records deployment activation and invocation with correlation id`() {
        val store = InMemoryTraceStore()
        val tracer = PersonaDeploymentTracer(store)

        val context = tracer.deployPersona(
            requestId = "deploy-1",
            personaVersion = "v1.2.3",
            deploymentManifestHash = "manifest-abc",
            deployDurationMs = 425,
            shouldFail = false
        )
        tracer.activatePersona(
            requestId = "activate-1",
            context = context,
            provider = "OPENAI",
            success = true
        )
        tracer.recordInvocation(
            requestId = "invoke-1",
            context = context,
            provider = "OPENAI",
            latencyMs = 250,
            success = true,
            errorRate = 0.0
        )

        assertEquals("v1.2.3:manifest-abc", context.correlationId)

        val traces = store.list()
        assertEquals(3, traces.size)

        val allEvents = traces.flatMap { it.events }
        assertTrue(allEvents.any { it.type == TraceEventType.DEPLOY_INITIATED })
        assertTrue(allEvents.any { it.type == TraceEventType.DEPLOY_COMPLETED })
        assertTrue(allEvents.any { it.type == TraceEventType.ACTIVATION_SUCCESS })
        val invocation = allEvents.first { it.type == TraceEventType.INVOCATION_METRIC }
        assertEquals("v1.2.3", invocation.payload["persona_version"])
        assertEquals("manifest-abc", invocation.payload["deployment_manifest_hash"])
        assertEquals("v1.2.3:manifest-abc", invocation.payload["correlation_id"])
    }

    @Test
    fun `deployment tracer records deploy and activation failure events`() {
        val store = InMemoryTraceStore()
        val tracer = PersonaDeploymentTracer(store)

        val context = tracer.deployPersona(
            requestId = "deploy-err",
            personaVersion = "v2",
            deploymentManifestHash = "hash2",
            deployDurationMs = 50,
            shouldFail = true,
            failureReason = "upload timeout"
        )
        tracer.activatePersona(
            requestId = "activate-err",
            context = context,
            provider = "LOCAL",
            success = false,
            failureReason = "warmup failure"
        )

        val allEvents = store.list().flatMap { it.events }
        assertTrue(allEvents.any { it.type == TraceEventType.DEPLOY_FAILED })
        assertTrue(allEvents.any { it.type == TraceEventType.ACTIVATION_FAILURE })
    }

    @Test
    fun `activate persona enforces manifest and governance`() {
        val settings = listOf(
            LlmSettings(
                provider = LlmProvider.LOCAL_ON_DEVICE,
                enabled = true,
                localModelPath = "/tmp/model.gguf",
                outboundClassification = DataClassification.SENSITIVE,
                fallbackOrder = LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK
            ),
            LlmSettings(
                provider = LlmProvider.OPENAI,
                enabled = true,
                apiKey = "key",
                outboundClassification = DataClassification.RESTRICTED
            )
        )

        val manager = PersonaRuntimeManager(
            providerRouter = ProviderRouter(emptyList()),
            toolOrchestratorFactory = { _, _, _ -> throw UnsupportedOperationException("not used in activation test") },
            settingsProvider = { settings },
            privacyModeProvider = { PrivacyMode.HYBRID },
            manifestProvider = {
                PersonaDeploymentManifest(
                    personaId = it,
                    allowedProviders = setOf(LlmProvider.LOCAL_ON_DEVICE, LlmProvider.OPENAI),
                    maxOutboundClassification = DataClassification.SENSITIVE,
                    fallbackOrder = LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK
                )
            }
        )

        val status = manager.activatePersona("pyn-core")

        assertEquals(PersonaRuntimePhase.ACTIVE, status.phase)
        assertEquals(listOf(LlmProvider.LOCAL_ON_DEVICE), status.activeProviders)
        assertTrue(status.governance?.allowed == true)
    }

    @Test
    fun `activate from mnx file uses file stem as persona id`() {
        val manager = PersonaRuntimeManager(
            providerRouter = ProviderRouter(emptyList()),
            toolOrchestratorFactory = { _, _, _ -> throw UnsupportedOperationException("not used in activation test") },
            settingsProvider = {
                listOf(
                    LlmSettings(
                        provider = LlmProvider.LOCAL_ON_DEVICE,
                        enabled = true,
                        localModelPath = "/tmp/model.gguf",
                        outboundClassification = DataClassification.PUBLIC
                    )
                )
            },
            privacyModeProvider = { PrivacyMode.STRICT_LOCAL_ONLY },
            manifestProvider = {
                PersonaDeploymentManifest(personaId = it, allowedProviders = setOf(LlmProvider.LOCAL_ON_DEVICE))
            }
        )

        val status = manager.activatePersona(File("/tmp/mentor.mnx"))
        assertEquals("mentor", status.personaId)
    }

    @Test
    fun `deactivate returns idle status`() {
        val manager = PersonaRuntimeManager(
            providerRouter = ProviderRouter(emptyList()),
            toolOrchestratorFactory = { _, _, _ -> throw UnsupportedOperationException("not used in activation test") },
            settingsProvider = { emptyList() },
            privacyModeProvider = { PrivacyMode.HYBRID },
            manifestProvider = { PersonaDeploymentManifest(personaId = it) }
        )

        manager.activatePersona("any")
        val status = manager.deactivatePersona("any")

        assertEquals(PersonaRuntimePhase.IDLE, status.phase)
        assertEquals("any", status.personaId)
    }
}
