package com.kaleaon.mnxmindmaker.tooling

import com.kaleaon.mnxmindmaker.util.provider.ProviderDriverId
import com.kaleaon.mnxmindmaker.util.provider.ProviderPluginManifest
import com.kaleaon.mnxmindmaker.util.provider.ProviderPluginRegistry
import com.kaleaon.mnxmindmaker.util.provider.RsaPluginManifestSignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ProviderPluginRegistryTest {

    @Test
    fun `signed manifest can be registered enabled and converted to settings`() {
        val keyPair = keyPair()
        val verifier = RsaPluginManifestSignatureVerifier(keyPair.public)
        val registry = ProviderPluginRegistry(appVersion = "1.5.0", signatureVerifier = verifier)

        val manifest = unsignedManifest(
            pluginId = "groq-openai",
            driver = ProviderDriverId.OPENAI_COMPATIBLE,
            version = "1.2.0",
            minAppVersion = "1.0.0"
        )
        val signed = manifest.copy(signature = sign(manifest, keyPair.private))

        val result = registry.registerFromJson(toJson(signed))
        assertTrue(result.accepted)
        assertTrue(registry.enable("groq-openai"))

        val settings = registry.buildDynamicSettings(apiKeysByPluginId = mapOf("groq-openai" to "secret-key"))
        assertEquals(1, settings.size)
        assertEquals("groq-openai", settings.first().pluginId)
        assertEquals("1.2.0", settings.first().pluginVersion)
        assertEquals("OpenAI-compatible (Self-hosted)", settings.first().provider.displayName)
        assertEquals("https://api.example.ai/v1", settings.first().baseUrl)
    }

    @Test
    fun `tampered manifest is rejected by signature verification`() {
        val keyPair = keyPair()
        val verifier = RsaPluginManifestSignatureVerifier(keyPair.public)
        val registry = ProviderPluginRegistry(appVersion = "2.0.0", signatureVerifier = verifier)

        val manifest = unsignedManifest(pluginId = "tampered", version = "1.0.0")
        val signed = manifest.copy(signature = sign(manifest, keyPair.private))

        val tamperedJson = toJson(signed).replace("api.example.ai", "evil.example.ai")
        val result = registry.registerFromJson(tamperedJson)

        assertFalse(result.accepted)
    }

    @Test
    fun `version pinning and compatibility checks are enforced`() {
        val keyPair = keyPair()
        val verifier = RsaPluginManifestSignatureVerifier(keyPair.public)
        val registry = ProviderPluginRegistry(appVersion = "1.8.0", signatureVerifier = verifier)

        val v1 = unsignedManifest(pluginId = "pin-test", version = "1.0.0", maxAppVersion = "2.0.0")
        val v2 = unsignedManifest(pluginId = "pin-test", version = "2.0.0", minAppVersion = "2.1.0")

        assertTrue(registry.registerFromJson(toJson(v1.copy(signature = sign(v1, keyPair.private)))).accepted)
        assertTrue(registry.registerFromJson(toJson(v2.copy(signature = sign(v2, keyPair.private)))).accepted)

        assertTrue(registry.enable("pin-test", versionPin = "2.0.0"))
        assertTrue("Pinned version is incompatible and should not resolve", registry.resolveEnabledManifests().isEmpty())

        assertTrue(registry.enable("pin-test"))
        val resolved = registry.resolveEnabledManifests()
        assertEquals(1, resolved.size)
        assertEquals("1.0.0", resolved.first().version)

        assertTrue(registry.disable("pin-test"))
        assertTrue(registry.resolveEnabledManifests().isEmpty())
    }

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun unsignedManifest(
        pluginId: String,
        driver: ProviderDriverId = ProviderDriverId.OPENAI_COMPATIBLE,
        version: String,
        minAppVersion: String? = null,
        maxAppVersion: String? = null
    ): ProviderPluginManifest = ProviderPluginManifest(
        pluginId = pluginId,
        displayName = "Plugin $pluginId",
        driver = driver,
        version = version,
        baseUrl = "https://api.example.ai/v1",
        defaultModel = "model/$pluginId",
        requiresApiKey = true,
        minAppVersion = minAppVersion,
        maxAppVersion = maxAppVersion,
        signature = ""
    )

    private fun sign(manifest: ProviderPluginManifest, privateKey: java.security.PrivateKey): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(manifest.canonicalPayload().toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun toJson(manifest: ProviderPluginManifest): String {
        return """
            {
              "pluginId":"${manifest.pluginId}",
              "displayName":"${manifest.displayName}",
              "driver":"${manifest.driver.name}",
              "version":"${manifest.version}",
              "baseUrl":"${manifest.baseUrl}",
              "defaultModel":"${manifest.defaultModel}",
              "requiresApiKey":${manifest.requiresApiKey},
              "runtime":"${manifest.runtime.name}",
              "minAppVersion":"${manifest.minAppVersion.orEmpty()}",
              "maxAppVersion":"${manifest.maxAppVersion.orEmpty()}",
              "signature":"${manifest.signature}"
            }
        """.trimIndent()
    }
}
