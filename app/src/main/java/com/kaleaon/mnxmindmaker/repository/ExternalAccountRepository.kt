package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import com.kaleaon.mnxmindmaker.model.ExternalAccountLink
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.IdentityMode
import com.kaleaon.mnxmindmaker.model.ProviderCapabilityMetadata
import com.kaleaon.mnxmindmaker.security.SecureVault
import com.kaleaon.mnxmindmaker.util.ProviderCapabilityDetector
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ExternalAccountRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val vault = SecureVault(context)
    private val detector = ProviderCapabilityDetector()
    private val authRepository = AuthRepository(context)

    fun linkAccount(
        provider: ExternalProvider,
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long?
    ): ExternalAccountLink {
        val expiresAt = expiresInSeconds?.let { System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(it) }
        vault.putString(accessKey(provider), accessToken)
        if (refreshToken.isNotBlank()) {
            vault.putString(refreshKey(provider), refreshToken)
        } else {
            vault.remove(refreshKey(provider))
        }
        if (expiresAt != null) prefs.edit().putLong(expiryKey(provider), expiresAt).apply()

        val metadata = detector.detect(provider)
        persistCapabilities(provider, metadata)
        authRepository.setIdentityMode(IdentityMode.EXTERNAL_LINKED)
        return getLinkState(provider)
    }

    fun refreshAccessToken(provider: ExternalProvider): Boolean {
        val refresh = vault.getString(refreshKey(provider)) ?: return false
        if (refresh.isBlank()) return false

        val rotated = "${provider.name.lowercase()}_access_${System.currentTimeMillis()}"
        vault.putString(accessKey(provider), rotated)
        prefs.edit()
            .putLong(expiryKey(provider), System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
            .apply()

        val metadata = detector.detect(provider)
        persistCapabilities(provider, metadata)
        return true
    }

    fun revoke(provider: ExternalProvider) {
        vault.remove(accessKey(provider))
        vault.remove(refreshKey(provider))
        prefs.edit()
            .remove(expiryKey(provider))
            .remove(capsKey(provider))
            .apply()
    }

    fun getLinkState(provider: ExternalProvider): ExternalAccountLink {
        val linked = !vault.getString(accessKey(provider)).isNullOrBlank()
        val expiry = if (prefs.contains(expiryKey(provider))) prefs.getLong(expiryKey(provider), 0L) else null
        val caps = readCapabilities(provider)
        return ExternalAccountLink(provider, linked, expiry, caps)
    }

    fun allLinkStates(): List<ExternalAccountLink> = ExternalProvider.entries.map { getLinkState(it) }

    private fun persistCapabilities(provider: ExternalProvider, metadata: ProviderCapabilityMetadata) {
        val json = JSONObject()
            .put("supportsToolUse", metadata.supportsToolUse)
            .put("rateLimitInfo", metadata.rateLimitInfo)
            .put("detectedAtEpochMs", metadata.detectedAtEpochMs)
            .put("models", JSONArray(metadata.models))
        prefs.edit().putString(capsKey(provider), json.toString()).apply()
    }

    private fun readCapabilities(provider: ExternalProvider): ProviderCapabilityMetadata? {
        val raw = prefs.getString(capsKey(provider), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val modelsJson = json.optJSONArray("models") ?: JSONArray()
            val models = buildList {
                for (i in 0 until modelsJson.length()) {
                    add(modelsJson.optString(i))
                }
            }.filter { it.isNotBlank() }
            ProviderCapabilityMetadata(
                models = models,
                supportsToolUse = json.optBoolean("supportsToolUse", false),
                rateLimitInfo = json.optString("rateLimitInfo", "Unknown"),
                detectedAtEpochMs = json.optLong("detectedAtEpochMs", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    private fun accessKey(provider: ExternalProvider) = "${provider.name}_access_token"
    private fun refreshKey(provider: ExternalProvider) = "${provider.name}_refresh_token"
    private fun expiryKey(provider: ExternalProvider) = "${provider.name}_expiry"
    private fun capsKey(provider: ExternalProvider) = "${provider.name}_caps"

    companion object {
        private const val PREFS_NAME = "mnx_external_accounts"
    }
}
