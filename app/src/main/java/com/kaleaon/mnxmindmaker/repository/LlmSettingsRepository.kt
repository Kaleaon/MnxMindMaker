package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalModelProfile
import com.kaleaon.mnxmindmaker.model.ComputeBackend
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.model.defaultModel

/**
 * Persists LLM API settings using encrypted shared preferences.
 * API keys are stored encrypted; other settings in plain preferences.
 */
class LlmSettingsRepository(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val plainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSettings(settings: LlmSettings) {
        val name = settings.provider.name
        encryptedPrefs.edit()
            .putString("${name}_apiKey", settings.apiKey)
            .apply()
        plainPrefs.edit()
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
            .apply()
    }

    fun loadSettings(provider: LlmProvider): LlmSettings {
        val name = provider.name
        val apiKey = encryptedPrefs.getString("${name}_apiKey", "") ?: ""
        val model = plainPrefs.getString("${name}_model", provider.defaultModel())
            ?: provider.defaultModel()
        val baseUrl = plainPrefs.getString("${name}_baseUrl", provider.baseUrl) ?: provider.baseUrl
        val enabled = plainPrefs.getBoolean("${name}_enabled", false)
        val maxTokens = plainPrefs.getInt("${name}_maxTokens", 2048)
        val temperature = plainPrefs.getFloat("${name}_temperature", 0.7f)
        val localModelPath = plainPrefs.getString("${name}_localModelPath", "") ?: ""
        val localProfile = plainPrefs.getString("${name}_localProfile", LocalModelProfile.BALANCED.name)
            ?.let { runCatching { LocalModelProfile.valueOf(it) }.getOrNull() }
            ?: LocalModelProfile.BALANCED
        val fallbackOrder = plainPrefs.getString("${name}_fallbackOrder", LlmFallbackOrder.REMOTE_ONLY.name)
            ?.let { runCatching { LlmFallbackOrder.valueOf(it) }.getOrNull() }
            ?: LlmFallbackOrder.REMOTE_ONLY
        val computeBackend = plainPrefs.getString("${name}_computeBackend", ComputeBackend.AUTO.name)
            ?.let { runCatching { ComputeBackend.valueOf(it) }.getOrNull() }
            ?: ComputeBackend.AUTO
        val runtimeControls = LocalRuntimeControls(
            computeBackend = computeBackend,
            contextWindowTokens = plainPrefs.getInt("${name}_contextWindow", localProfile.contextWindowTokens),
            quantizationProfile = plainPrefs.getString("${name}_quantProfile", "Q4_K_M") ?: "Q4_K_M",
            maxRamMb = plainPrefs.getInt("${name}_maxRamMb", 4096),
            maxVramMb = plainPrefs.getInt("${name}_maxVramMb", 2048)
        )
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
            runtimeControls = runtimeControls
        )
    }

    fun loadAllSettings(): List<LlmSettings> = LlmProvider.entries.map { loadSettings(it) }

    fun getActiveProvider(): LlmProvider? = getInvocationChain().firstOrNull()?.provider

    fun getInvocationChain(): List<LlmSettings> {
        val all = loadAllSettings()
        val usable = all.filter { it.enabled && it.isUsable() }
        val local = usable.firstOrNull { it.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE }
        val remote = usable.filter { it.provider.runtime == LlmRuntime.REMOTE_API }

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
        private const val ENCRYPTED_PREFS_NAME = "mnx_llm_keys"
        private const val PLAIN_PREFS_NAME = "mnx_llm_settings"
    }
}
