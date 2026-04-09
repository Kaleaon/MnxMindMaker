package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kaleaon.mnxmindmaker.model.ComputeBackend
import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalModelProfile
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.model.RetrievalModePreference
import com.kaleaon.mnxmindmaker.model.defaultModel

/**
 * Persists LLM + privacy settings in encrypted shared preferences.
 */
class LlmSettingsRepository(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveSettings(settings: LlmSettings) {
        val name = settings.provider.name
        prefs.edit()
            .putString("${name}_apiKey", settings.apiKey)
            .putString("${name}_model", settings.model)
            .putString("${name}_baseUrl", settings.baseUrl)
            .putBoolean("${name}_enabled", settings.enabled)
            .putInt("${name}_maxTokens", settings.maxTokens)
            .putFloat("${name}_temperature", settings.temperature)
            .putString("${name}_localModelPath", settings.localModelPath)
            .putString("${name}_localProfile", settings.localProfile.name)
            .putString("${name}_fallbackOrder", settings.fallbackOrder.name)
            .putString("${name}_computeBackend", settings.runtimeControls.computeBackend.name)
            .putInt("${name}_contextWindow", settings.runtimeControls.contextWindowTokens)
            .putString("${name}_quantProfile", settings.runtimeControls.quantizationProfile)
            .putInt("${name}_maxRamMb", settings.runtimeControls.maxRamMb)
            .putInt("${name}_maxVramMb", settings.runtimeControls.maxVramMb)
            .putString("${name}_classification", settings.outboundClassification.name)
            .putString("${name}_tlsPin", settings.tlsPinnedSpkiSha256)
            .putBoolean("${name}_enableWakeUpContext", settings.enableWakeUpContext)
            .putInt("${name}_wakeUpTokenBudget", settings.wakeUpTokenBudget)
            .putString("${name}_retrievalModePreference", settings.retrievalModePreference.name)
            .apply()
    }

    fun loadSettings(provider: LlmProvider): LlmSettings {
        val name = provider.name
        val apiKey = prefs.getString("${name}_apiKey", "") ?: ""
        val model = prefs.getString("${name}_model", provider.defaultModel()) ?: provider.defaultModel()
        val baseUrl = prefs.getString("${name}_baseUrl", provider.baseUrl) ?: provider.baseUrl
        val enabled = prefs.getBoolean("${name}_enabled", false)
        val maxTokens = prefs.getInt("${name}_maxTokens", 2048)
        val temperature = prefs.getFloat("${name}_temperature", 0.7f)
        val localModelPath = prefs.getString("${name}_localModelPath", "") ?: ""
        val localProfile = prefs.getString("${name}_localProfile", LocalModelProfile.BALANCED.name)
            ?.let { runCatching { LocalModelProfile.valueOf(it) }.getOrNull() }
            ?: LocalModelProfile.BALANCED
        val fallbackOrder = prefs.getString("${name}_fallbackOrder", LlmFallbackOrder.REMOTE_ONLY.name)
            ?.let { runCatching { LlmFallbackOrder.valueOf(it) }.getOrNull() }
            ?: LlmFallbackOrder.REMOTE_ONLY
        val computeBackend = prefs.getString("${name}_computeBackend", ComputeBackend.AUTO.name)
            ?.let { runCatching { ComputeBackend.valueOf(it) }.getOrNull() }
            ?: ComputeBackend.AUTO
        val runtimeControls = LocalRuntimeControls(
            computeBackend = computeBackend,
            contextWindowTokens = prefs.getInt("${name}_contextWindow", localProfile.contextWindowTokens),
            quantizationProfile = prefs.getString("${name}_quantProfile", "Q4_K_M") ?: "Q4_K_M",
            maxRamMb = prefs.getInt("${name}_maxRamMb", 4096),
            maxVramMb = prefs.getInt("${name}_maxVramMb", 2048)
        )
        val classification = prefs.getString("${name}_classification", DataClassification.SENSITIVE.name)
            ?.let { runCatching { DataClassification.valueOf(it) }.getOrNull() }
            ?: DataClassification.SENSITIVE
        val tlsPin = prefs.getString("${name}_tlsPin", "") ?: ""
        val enableWakeUpContext = prefs.getBoolean("${name}_enableWakeUpContext", true)
        val wakeUpTokenBudget = prefs.getInt("${name}_wakeUpTokenBudget", 1024)
        val retrievalModePreference = prefs.getString(
            "${name}_retrievalModePreference",
            RetrievalModePreference.SUMMARY.name
        )?.let { runCatching { RetrievalModePreference.valueOf(it) }.getOrNull() }
            ?: RetrievalModePreference.SUMMARY

        return LlmSettings(
            provider = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            enabled = enabled,
            maxTokens = maxTokens,
            temperature = temperature,
            localModelPath = localModelPath,
            localProfile = localProfile,
            fallbackOrder = fallbackOrder,
            runtimeControls = runtimeControls,
            outboundClassification = classification,
            tlsPinnedSpkiSha256 = tlsPin,
            enableWakeUpContext = enableWakeUpContext,
            wakeUpTokenBudget = wakeUpTokenBudget,
            retrievalModePreference = retrievalModePreference
        )
    }

    fun savePrivacyMode(mode: PrivacyMode) {
        prefs.edit().putString(KEY_PRIVACY_MODE, mode.name).apply()
    }

    fun loadPrivacyMode(): PrivacyMode {
        val raw = prefs.getString(KEY_PRIVACY_MODE, PrivacyMode.HYBRID.name) ?: PrivacyMode.HYBRID.name
        return runCatching { PrivacyMode.valueOf(raw) }.getOrDefault(PrivacyMode.HYBRID)
    }

    fun loadAllSettings(): List<LlmSettings> = LlmProvider.entries.map { loadSettings(it) }

    fun getInvocationChain(): List<LlmSettings> {
        val all = loadAllSettings()
        val usable = all.filter { it.enabled && it.isUsable() }
        val local = usable.firstOrNull { it.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE }
        val remote = usable.filter { it.provider.runtime == LlmRuntime.REMOTE_API }

        if (loadPrivacyMode() == PrivacyMode.STRICT_LOCAL_ONLY) {
            return listOfNotNull(local)
        }

        if (local != null && local.fallbackOrder == LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK) {
            return listOf(local) + remote
        }
        return if (remote.isNotEmpty()) remote else listOfNotNull(local)
    }

    fun getLocalFallbackCandidate(): LlmSettings? {
        val local = loadSettings(LlmProvider.LOCAL_ON_DEVICE)
        return local.takeIf { it.enabled && it.isUsable() }
    }

    private fun LlmSettings.isUsable(): Boolean {
        return when {
            provider.runtime == LlmRuntime.LOCAL_ON_DEVICE -> {
                localModelPath.isNotBlank() && baseUrl.isNotBlank()
            }
            provider.requiresApiKey -> apiKey.isNotBlank()
            else -> true
        }
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "mnx_secure_llm_settings"
        private const val KEY_PRIVACY_MODE = "global_privacy_mode"
    }
}
