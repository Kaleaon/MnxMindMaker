package com.kaleaon.mnxmindmaker.model

data class OAuthProviderEndpointConfig(
    val provider: ExternalProvider,
    val authorizeUrl: String,
    val tokenUrl: String,
    val defaultScopes: List<String>,
    val requiresPkce: Boolean = true,
    val clientIdKey: String,
    val clientSecretKey: String
)

data class OAuthPendingRequest(
    val state: String,
    val provider: ExternalProvider,
    val codeVerifier: String,
    val redirectUri: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

sealed interface OAuthAuthorizationResult {
    data class Success(
        val provider: ExternalProvider,
        val code: String,
        val state: String,
        val codeVerifier: String,
        val redirectUri: String
    ) : OAuthAuthorizationResult

    data class Error(
        val provider: ExternalProvider?,
        val error: String,
        val errorDescription: String?
    ) : OAuthAuthorizationResult
}

sealed interface OAuthTokenExchangeResult {
    data class Success(
        val provider: ExternalProvider,
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long?
    ) : OAuthTokenExchangeResult

    data class Failure(
        val provider: ExternalProvider,
        val reason: String
    ) : OAuthTokenExchangeResult
}
