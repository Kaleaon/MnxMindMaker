package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import android.content.SharedPreferences
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalModelProfile
import com.kaleaon.mnxmindmaker.model.defaultModel
import com.kaleaon.mnxmindmaker.security.SecureVault

/**
 * Persists LLM API settings with secure encrypted storage for API keys and
 * plain preferences for non-secret tunables.
 */
class LlmSettingsRepository(private val context: Context) {

    private val secureVault = SecureVault(context)

    private val plainPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PLAIN_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveSettings(settings: LlmSettings) {
        val name = settings.provider.name
        secureVault.putString("${name}_apiKey", settings.apiKey)
        plainPrefs.edit()
            .putString("${name}_model", settings.model)
            .putString("${name}_baseUrl", settings.baseUrl)
            .putBoolean("${name}_enabled", settings.enabled)
            .putInt("${name}_maxTokens", settings.maxTokens)
            .putFloat("${name}_temperature", settings.temperature)
            .putString("${name}_localModelPath", settings.localModelPath)
            .putString("${name}_localProfile", settings.localProfile.name)
            .putString("${name}_fallbackOrder", settings.fallbackOrder.name)
            .apply()
    }

    fun loadSettings(provider: LlmProvider): LlmSettings {
        val name = provider.name
        val apiKey = secureVault.getString("${name}_apiKey").orEmpty()
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
            fallbackOrder = fallbackOrder
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
        private const val PLAIN_PREFS_NAME = "mnx_llm_settings"
    }
}
