package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import com.kaleaon.mnxmindmaker.model.ExternalAccountLink
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.IdentityMode
import com.kaleaon.mnxmindmaker.model.ProviderCapabilityMetadata
import com.kaleaon.mnxmindmaker.security.SecureVault
import com.kaleaon.mnxmindmaker.util.ProviderCapabilityDetector
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ExternalAccountRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val vault = SecureVault(context)
    private val detector = ProviderCapabilityDetector()
    private val authRepository = AuthRepository(context)
    private val httpClient = OkHttpClient()

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
        prefs.edit().apply {
            if (expiresAt != null) {
                putLong(expiryKey(provider), expiresAt)
            } else {
                remove(expiryKey(provider))
            }
            apply()
        }

        val metadata = detector.detect(provider, accessToken)
        persistCapabilities(provider, metadata)
        authRepository.setIdentityMode(IdentityMode.EXTERNAL_LINKED)
        return getLinkState(provider)
    }

    fun refreshAccessToken(provider: ExternalProvider): Boolean {
        return refreshAccessTokenDetailed(provider) == RefreshStatus.SUCCESS
    }

    fun saveOAuthClientConfig(provider: ExternalProvider, clientId: String, clientSecret: String) {
        val config = TOKEN_REFRESH_CONFIGS[provider] ?: return
        vault.putString(config.clientIdKey, clientId.trim())
        vault.putString(config.clientSecretKey, clientSecret.trim())
    }

    fun hasOAuthClientConfig(provider: ExternalProvider): Boolean {
        val config = TOKEN_REFRESH_CONFIGS[provider] ?: return false
        val clientId = vault.getString(config.clientIdKey)
        val clientSecret = vault.getString(config.clientSecretKey)
        return !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()
    }

    fun canRefreshLinkedAccount(provider: ExternalProvider): Boolean {
        val refresh = vault.getString(refreshKey(provider))
        val config = TOKEN_REFRESH_CONFIGS[provider] ?: return false
        return validateRefreshPrerequisites(
            refreshToken = refresh,
            clientId = vault.getString(config.clientIdKey),
            clientSecret = vault.getString(config.clientSecretKey)
        ) == RefreshStatus.SUCCESS
    }

    fun refreshAccessTokenDetailed(provider: ExternalProvider): RefreshStatus {
        val refresh = vault.getString(refreshKey(provider)) ?: return RefreshStatus.MISSING_REFRESH_TOKEN
        if (refresh.isBlank()) return RefreshStatus.MISSING_REFRESH_TOKEN
        val config = TOKEN_REFRESH_CONFIGS[provider] ?: return RefreshStatus.INVALID_RESPONSE
        val precheck = validateRefreshPrerequisites(
            refreshToken = refresh,
            clientId = vault.getString(config.clientIdKey),
            clientSecret = vault.getString(config.clientSecretKey)
        )
        if (precheck != RefreshStatus.SUCCESS) return precheck

        val refreshHttpResult = executeRefreshRequest(config, refresh)
        val responseBody = when (refreshHttpResult) {
            is RefreshHttpResult.Success -> refreshHttpResult.body
            RefreshHttpResult.ProviderRejected -> return RefreshStatus.PROVIDER_REJECTED
            RefreshHttpResult.MissingClientConfig -> return RefreshStatus.MISSING_CLIENT_CONFIG
            RefreshHttpResult.TransportFailure -> return RefreshStatus.NETWORK_ERROR
        }
        val payload = parseTokenRefreshResponse(responseBody) ?: return RefreshStatus.INVALID_RESPONSE
        if (payload.accessToken.isBlank()) return RefreshStatus.INVALID_RESPONSE

        vault.putString(accessKey(provider), payload.accessToken)
        if (!payload.refreshToken.isNullOrBlank()) {
            vault.putString(refreshKey(provider), payload.refreshToken)
        }
        payload.expiresInSeconds?.let { seconds ->
            prefs.edit().putLong(expiryKey(provider), expiryFromNow(System.currentTimeMillis(), seconds)).apply()
        }

        val metadata = detector.detect(provider, payload.accessToken)
        persistCapabilities(provider, metadata)
        return RefreshStatus.SUCCESS
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


    fun getAccessToken(provider: ExternalProvider): String? = vault.getString(accessKey(provider))

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

    private fun executeRefreshRequest(config: TokenRefreshConfig, refreshToken: String): RefreshHttpResult {
        val clientId = vault.getString(config.clientIdKey)
        val clientSecret = vault.getString(config.clientSecretKey)
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) return RefreshHttpResult.MissingClientConfig

        val formBody = FormBody.Builder()
            .add("grant_type", config.grantType)
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = Request.Builder()
            .url(config.tokenUrl)
            .post(formBody)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.code in 400..499) {
                    return RefreshHttpResult.ProviderRejected
                }
                if (!response.isSuccessful) return RefreshHttpResult.TransportFailure
                val body = response.body?.string() ?: return RefreshHttpResult.TransportFailure
                RefreshHttpResult.Success(body)
            }
        }.getOrElse { RefreshHttpResult.TransportFailure }
    }

    companion object {
        private const val PREFS_NAME = "mnx_external_accounts"
        private val TOKEN_REFRESH_CONFIGS: Map<ExternalProvider, TokenRefreshConfig> = mapOf(
            ExternalProvider.CLAUDE to TokenRefreshConfig(
                tokenUrl = "https://api.anthropic.com/oauth/token",
                grantType = "refresh_token",
                clientIdKey = "CLAUDE_client_id",
                clientSecretKey = "CLAUDE_client_secret"
            ),
            ExternalProvider.CHATGPT to TokenRefreshConfig(
                tokenUrl = "https://api.openai.com/v1/oauth/token",
                grantType = "refresh_token",
                clientIdKey = "CHATGPT_client_id",
                clientSecretKey = "CHATGPT_client_secret"
            )
        )

        internal fun parseTokenRefreshResponse(responseBody: String): RefreshedTokenPayload? {
            return runCatching {
                val json = JSONObject(responseBody)
                val accessToken = json.optString("access_token", "").trim()
                val refreshToken = json.optString("refresh_token", "").trim().ifBlank { null }
                val expiresIn = when {
                    json.has("expires_in") -> json.optLong("expires_in", -1L).takeIf { it > 0 }
                    else -> null
                }
                RefreshedTokenPayload(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresInSeconds = expiresIn
                )
            }.getOrNull()
        }

        internal fun expiryFromNow(nowEpochMs: Long, expiresInSeconds: Long): Long {
            return nowEpochMs + TimeUnit.SECONDS.toMillis(expiresInSeconds)
        }

        internal fun validateRefreshPrerequisites(
            refreshToken: String?,
            clientId: String?,
            clientSecret: String?
        ): RefreshStatus {
            if (refreshToken.isNullOrBlank()) return RefreshStatus.MISSING_REFRESH_TOKEN
            if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) return RefreshStatus.MISSING_CLIENT_CONFIG
            return RefreshStatus.SUCCESS
        }
    }
}

enum class RefreshStatus {
    SUCCESS,
    MISSING_REFRESH_TOKEN,
    MISSING_CLIENT_CONFIG,
    PROVIDER_REJECTED,
    NETWORK_ERROR,
    INVALID_RESPONSE
}

data class TokenRefreshConfig(
    val tokenUrl: String,
    val grantType: String,
    val clientIdKey: String,
    val clientSecretKey: String
)

data class RefreshedTokenPayload(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long?
)

private sealed interface RefreshHttpResult {
    data class Success(val body: String) : RefreshHttpResult
    data object ProviderRejected : RefreshHttpResult
    data object MissingClientConfig : RefreshHttpResult
    data object TransportFailure : RefreshHttpResult
}
