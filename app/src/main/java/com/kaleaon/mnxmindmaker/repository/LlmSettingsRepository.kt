package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings

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
            .putBoolean("${name}_enabled", settings.enabled)
            .putInt("${name}_maxTokens", settings.maxTokens)
            .putFloat("${name}_temperature", settings.temperature)
            .apply()
    }

    fun loadSettings(provider: LlmProvider): LlmSettings {
        val name = provider.name
        val apiKey = encryptedPrefs.getString("${name}_apiKey", "") ?: ""
        val model = plainPrefs.getString("${name}_model", provider.defaultModel())
            ?: provider.defaultModel()
        val enabled = plainPrefs.getBoolean("${name}_enabled", false)
        val maxTokens = plainPrefs.getInt("${name}_maxTokens", 2048)
        val temperature = plainPrefs.getFloat("${name}_temperature", 0.7f)
        return LlmSettings(provider, apiKey, model, enabled, maxTokens, temperature)
    }

    fun loadAllSettings(): List<LlmSettings> = LlmProvider.entries.map { loadSettings(it) }

    fun getActiveProvider(): LlmProvider? {
        return LlmProvider.entries.firstOrNull { loadSettings(it).enabled && loadSettings(it).apiKey.isNotBlank() }
    }

    private fun LlmProvider.defaultModel(): String = when (this) {
        LlmProvider.ANTHROPIC -> "claude-3-5-sonnet-20241022"
        LlmProvider.OPENAI -> "gpt-4o"
        LlmProvider.GEMINI -> "gemini-1.5-pro"
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "mnx_llm_keys"
        private const val PLAIN_PREFS_NAME = "mnx_llm_settings"
    }
}
