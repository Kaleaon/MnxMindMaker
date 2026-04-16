package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Driver families that dynamic plugins can target without requiring new app binaries.
 */
enum class ProviderDriverId {
    OPENAI,
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
    LOCAL_ON_DEVICE
}

data class ProviderPluginManifest(
    val pluginId: String,
    val displayName: String,
    val driver: ProviderDriverId,
    val version: String,
    val baseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean = true,
    val runtime: LlmRuntime = LlmRuntime.REMOTE_API,
    val minAppVersion: String? = null,
    val maxAppVersion: String? = null,
    val signature: String
) {
    fun canonicalPayload(): String {
        return buildString {
            append("pluginId=").append(pluginId).append('\n')
            append("displayName=").append(displayName).append('\n')
            append("driver=").append(driver.name).append('\n')
            append("version=").append(version).append('\n')
            append("baseUrl=").append(baseUrl).append('\n')
            append("defaultModel=").append(defaultModel).append('\n')
            append("requiresApiKey=").append(requiresApiKey).append('\n')
            append("runtime=").append(runtime.name).append('\n')
            append("minAppVersion=").append(minAppVersion.orEmpty()).append('\n')
            append("maxAppVersion=").append(maxAppVersion.orEmpty())
        }
    }

    fun isCompatibleWith(appVersion: String): Boolean {
        val minOk = minAppVersion?.let { SemVer.compare(appVersion, it) >= 0 } ?: true
        val maxOk = maxAppVersion?.let { SemVer.compare(appVersion, it) <= 0 } ?: true
        return minOk && maxOk
    }

    fun toSettings(enabled: Boolean = true, apiKey: String = ""): LlmSettings {
        val provider = when (driver) {
            ProviderDriverId.OPENAI -> LlmProvider.OPENAI
            ProviderDriverId.OPENAI_COMPATIBLE -> LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED
            ProviderDriverId.ANTHROPIC -> LlmProvider.ANTHROPIC
            ProviderDriverId.GEMINI -> LlmProvider.GEMINI
            ProviderDriverId.LOCAL_ON_DEVICE -> LlmProvider.LOCAL_ON_DEVICE
        }

        return LlmSettings(
            provider = provider,
            apiKey = apiKey,
            model = defaultModel,
            baseUrl = baseUrl,
            enabled = enabled,
            pluginId = pluginId,
            pluginVersion = version,
            pluginDriver = driver.name,
            pluginDisplayName = displayName
        )
    }

    companion object {
        fun fromJson(raw: String): ProviderPluginManifest {
            val json = JSONObject(raw)
            return ProviderPluginManifest(
                pluginId = json.getString("pluginId"),
                displayName = json.getString("displayName"),
                driver = ProviderDriverId.valueOf(json.getString("driver")),
                version = json.getString("version"),
                baseUrl = json.getString("baseUrl"),
                defaultModel = json.getString("defaultModel"),
                requiresApiKey = json.optBoolean("requiresApiKey", true),
                runtime = runCatching { LlmRuntime.valueOf(json.optString("runtime", LlmRuntime.REMOTE_API.name)) }
                    .getOrDefault(LlmRuntime.REMOTE_API),
                minAppVersion = json.optString("minAppVersion").ifBlank { null },
                maxAppVersion = json.optString("maxAppVersion").ifBlank { null },
                signature = json.getString("signature")
            )
        }
    }
}

interface PluginManifestSignatureVerifier {
    fun verify(manifest: ProviderPluginManifest): Boolean
}

class RsaPluginManifestSignatureVerifier(
    private val trustedPublicKey: PublicKey
) : PluginManifestSignatureVerifier {
    override fun verify(manifest: ProviderPluginManifest): Boolean {
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(trustedPublicKey)
        verifier.update(manifest.canonicalPayload().toByteArray(StandardCharsets.UTF_8))
        val signatureBytes = runCatching { Base64.getDecoder().decode(manifest.signature) }.getOrElse { return false }
        return verifier.verify(signatureBytes)
    }
}

data class PluginRegistrationResult(
    val accepted: Boolean,
    val reason: String
)

class ProviderPluginRegistry(
    private val appVersion: String,
    private val signatureVerifier: PluginManifestSignatureVerifier,
    private val supportedDrivers: Set<ProviderDriverId> = ProviderDriverId.entries.toSet()
) {
    private data class PluginState(
        val enabled: Boolean = false,
        val pinnedVersion: String? = null
    )

    private val manifestsByPlugin = mutableMapOf<String, MutableMap<String, ProviderPluginManifest>>()
    private val states = mutableMapOf<String, PluginState>()

    fun registerFromJson(rawManifest: String): PluginRegistrationResult {
        val manifest = runCatching { ProviderPluginManifest.fromJson(rawManifest) }
            .getOrElse { return PluginRegistrationResult(false, "Invalid manifest JSON: ${it.message}") }

        if (!supportedDrivers.contains(manifest.driver)) {
            return PluginRegistrationResult(false, "Unsupported driver: ${manifest.driver}")
        }
        if (!signatureVerifier.verify(manifest)) {
            return PluginRegistrationResult(false, "Manifest signature verification failed")
        }
        manifestsByPlugin.getOrPut(manifest.pluginId) { mutableMapOf() }[manifest.version] = manifest
        states.putIfAbsent(manifest.pluginId, PluginState())
        return PluginRegistrationResult(true, "Registered ${manifest.pluginId}@${manifest.version}")
    }

    fun enable(pluginId: String, versionPin: String? = null): Boolean {
        val manifests = manifestsByPlugin[pluginId] ?: return false
        if (versionPin != null && manifests[versionPin] == null) return false
        states[pluginId] = PluginState(enabled = true, pinnedVersion = versionPin)
        return true
    }

    fun disable(pluginId: String): Boolean {
        if (!states.containsKey(pluginId)) return false
        states[pluginId] = states.getValue(pluginId).copy(enabled = false)
        return true
    }

    fun resolveEnabledManifests(): List<ProviderPluginManifest> {
        return states.mapNotNull { (pluginId, state) ->
            if (!state.enabled) return@mapNotNull null
            val manifests = manifestsByPlugin[pluginId].orEmpty()
            if (state.pinnedVersion != null) {
                manifests[state.pinnedVersion]?.takeIf { it.isCompatibleWith(appVersion) }
            } else {
                manifests.values
                    .filter { it.isCompatibleWith(appVersion) }
                    .maxWithOrNull(compareBy { SemVer.parse(it.version) })
            }
        }
    }

    fun buildDynamicSettings(apiKeysByPluginId: Map<String, String> = emptyMap()): List<LlmSettings> {
        return resolveEnabledManifests().map { manifest ->
            manifest.toSettings(apiKey = apiKeysByPluginId[manifest.pluginId].orEmpty())
        }
    }
}

private object SemVer {
    data class Parsed(val major: Int, val minor: Int, val patch: Int) : Comparable<Parsed> {
        override fun compareTo(other: Parsed): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }
    }

    fun parse(raw: String): Parsed {
        val numeric = raw.substringBefore('-')
        val parts = numeric.split('.')
        return Parsed(
            major = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            minor = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }

    fun compare(left: String, right: String): Int = parse(left).compareTo(parse(right))
}
