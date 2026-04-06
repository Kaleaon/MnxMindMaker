package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import com.kaleaon.mnxmindmaker.util.provider.RoutingPolicy
import com.kaleaon.mnxmindmaker.util.tooling.ToolOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonaRuntimeManagerTest {

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
