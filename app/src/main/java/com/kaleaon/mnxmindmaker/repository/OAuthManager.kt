package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.OAuthAuthorizationResult
import com.kaleaon.mnxmindmaker.model.OAuthPendingRequest
import com.kaleaon.mnxmindmaker.model.OAuthProviderEndpointConfig
import com.kaleaon.mnxmindmaker.model.OAuthTokenExchangeResult
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OAuthManager(
    private val context: Context? = null,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val pendingRequests = ConcurrentHashMap<String, OAuthPendingRequest>()

    fun getEndpointConfig(provider: ExternalProvider): OAuthProviderEndpointConfig? {
        return ENDPOINT_CONFIGS[provider]
    }

    fun buildAuthorizationUrl(
        provider: ExternalProvider,
        clientId: String,
        redirectUri: String = DEFAULT_REDIRECT_URI,
        customScope: String? = null
    ): String? {
        val config = ENDPOINT_CONFIGS[provider] ?: return null
        if (clientId.isBlank()) return null

        purgeExpiredPendingRequests()

        val state = UUID.randomUUID().toString()
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        val pending = OAuthPendingRequest(
            state = state,
            provider = provider,
            codeVerifier = verifier,
            redirectUri = redirectUri
        )
        pendingRequests[state] = pending

        val scope = customScope?.ifBlank { null } ?: config.defaultScopes.joinToString(" ")

        val baseUrl = config.authorizeUrl.toHttpUrlOrNull() ?: return null
        return baseUrl.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", scope)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    fun launchOAuthCustomTab(context: Context, authorizationUrl: String): Boolean {
        return runCatching {
            val uri = Uri.parse(authorizationUrl)
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(context, uri)
            true
        }.getOrElse {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }

    fun parseAndValidateCallbackUri(uriString: String): OAuthAuthorizationResult {
        val httpUrl = uriString.toHttpUrlOrNull()
            ?: return OAuthAuthorizationResult.Error(
                provider = null,
                error = "invalid_uri",
                errorDescription = "Invalid callback URI"
            )

        val error = httpUrl.queryParameter("error")
        val errorDesc = httpUrl.queryParameter("error_description")
        val state = httpUrl.queryParameter("state")
        val code = httpUrl.queryParameter("code")

        if (!error.isNullOrBlank()) {
            val pending = state?.let { pendingRequests.remove(it) }
            return OAuthAuthorizationResult.Error(
                provider = pending?.provider,
                error = error,
                errorDescription = errorDesc
            )
        }

        if (state.isNullOrBlank() || code.isNullOrBlank()) {
            return OAuthAuthorizationResult.Error(
                provider = null,
                error = "invalid_callback",
                errorDescription = "Missing state or code parameter in callback URI"
            )
        }

        val pending = pendingRequests.remove(state)
            ?: return OAuthAuthorizationResult.Error(
                provider = null,
                error = "state_mismatch",
                errorDescription = "State parameter did not match any active OAuth authorization request"
            )

        return OAuthAuthorizationResult.Success(
            provider = pending.provider,
            code = code,
            state = state,
            codeVerifier = pending.codeVerifier,
            redirectUri = pending.redirectUri
        )
    }

    fun parseAndValidateCallbackUri(uri: Uri): OAuthAuthorizationResult {
        return parseAndValidateCallbackUri(uri.toString())
    }

    fun exchangeAuthorizationCode(
        provider: ExternalProvider,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String? = null
    ): OAuthTokenExchangeResult {
        val config = ENDPOINT_CONFIGS[provider]
            ?: return OAuthTokenExchangeResult.Failure(provider, "Unsupported provider for OAuth exchange")

        val formBuilder = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)

        if (!clientSecret.isNullOrBlank()) {
            formBuilder.add("client_secret", clientSecret)
        }

        val request = Request.Builder()
            .url(config.tokenUrl)
            .post(formBuilder.build())
            .header("Accept", "application/json")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorDetail = parseErrorResponse(body) ?: "HTTP ${response.code}"
                    return OAuthTokenExchangeResult.Failure(provider, "Token endpoint error: $errorDetail")
                }

                val payload = ExternalAccountRepository.parseTokenRefreshResponse(body)
                    ?: return OAuthTokenExchangeResult.Failure(provider, "Failed to parse token payload")

                if (payload.accessToken.isBlank()) {
                    return OAuthTokenExchangeResult.Failure(provider, "Access token was empty in response")
                }

                OAuthTokenExchangeResult.Success(
                    provider = provider,
                    accessToken = payload.accessToken,
                    refreshToken = payload.refreshToken,
                    expiresInSeconds = payload.expiresInSeconds
                )
            }
        }.getOrElse { ex ->
            OAuthTokenExchangeResult.Failure(provider, ex.message ?: "Network error during code exchange")
        }
    }

    private fun purgeExpiredPendingRequests() {
        val now = System.currentTimeMillis()
        val expiredMs = TimeUnit.MINUTES.toMillis(15)
        pendingRequests.entries.removeIf { now - it.value.createdAtEpochMs > expiredMs }
    }

    companion object {
        const val DEFAULT_REDIRECT_URI = "https://mnxmindmaker.app/oauth/callback"

        val ENDPOINT_CONFIGS: Map<ExternalProvider, OAuthProviderEndpointConfig> = mapOf(
            ExternalProvider.CLAUDE to OAuthProviderEndpointConfig(
                provider = ExternalProvider.CLAUDE,
                authorizeUrl = "https://api.anthropic.com/oauth/authorize",
                tokenUrl = "https://api.anthropic.com/oauth/token",
                defaultScopes = listOf("user:read", "messages:write"),
                requiresPkce = true,
                clientIdKey = "CLAUDE_client_id",
                clientSecretKey = "CLAUDE_client_secret"
            ),
            ExternalProvider.CHATGPT to OAuthProviderEndpointConfig(
                provider = ExternalProvider.CHATGPT,
                authorizeUrl = "https://auth.openai.com/authorize",
                tokenUrl = "https://api.openai.com/v1/oauth/token",
                defaultScopes = listOf("openid", "profile", "model.request"),
                requiresPkce = true,
                clientIdKey = "CHATGPT_client_id",
                clientSecretKey = "CHATGPT_client_secret"
            ),
            ExternalProvider.HUGGING_FACE to OAuthProviderEndpointConfig(
                provider = ExternalProvider.HUGGING_FACE,
                authorizeUrl = "https://huggingface.co/oauth/authorize",
                tokenUrl = "https://huggingface.co/oauth/token",
                defaultScopes = listOf("openid", "profile", "read-repos"),
                requiresPkce = true,
                clientIdKey = "HUGGING_FACE_client_id",
                clientSecretKey = "HUGGING_FACE_client_secret"
            )
        )

        internal fun generateCodeVerifier(): String {
            val random = SecureRandom()
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        internal fun generateCodeChallenge(verifier: String): String {
            val bytes = verifier.toByteArray(Charsets.US_ASCII)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        private fun parseErrorResponse(body: String): String? {
            return runCatching {
                val json = JSONObject(body)
                json.optString("error_description").ifBlank {
                    json.optString("error").ifBlank { null }
                }
            }.getOrNull()
        }
    }
}
