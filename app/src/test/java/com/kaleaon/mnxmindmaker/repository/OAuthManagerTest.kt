package com.kaleaon.mnxmindmaker.repository

import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.OAuthAuthorizationResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthManagerTest {

    private lateinit var oauthManager: OAuthManager

    @Before
    fun setUp() {
        oauthManager = OAuthManager()
    }

    @Test
    fun generateCodeVerifier_returnsValidUrlSafeString() {
        val verifier = OAuthManager.generateCodeVerifier()
        assertNotNull(verifier)
        assertTrue("Verifier length should be >= 43", verifier.length >= 43)
        assertTrue("Verifier should be URL safe", verifier.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }

    @Test
    fun generateCodeChallenge_createsBase64UrlSha256Digest() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = OAuthManager.generateCodeChallenge(verifier)
        assertNotNull(challenge)
        assertTrue("Challenge should be URL safe", challenge.matches(Regex("^[a-zA-Z0-9_-]+$")))
    }

    @Test
    fun buildAuthorizationUrl_buildsValidUrlWithQueryParams() {
        val clientId = "test-client-id-123"
        val redirectUri = "https://mnxmindmaker.app/oauth/callback"

        val urlString = oauthManager.buildAuthorizationUrl(
            provider = ExternalProvider.CLAUDE,
            clientId = clientId,
            redirectUri = redirectUri
        )

        assertNotNull(urlString)
        val httpUrl = urlString!!.toHttpUrlOrNull()
        assertNotNull(httpUrl)

        assertEquals("https", httpUrl!!.scheme)
        assertEquals("api.anthropic.com", httpUrl.host)
        assertEquals("/oauth/authorize", httpUrl.encodedPath)
        assertEquals("code", httpUrl.queryParameter("response_type"))
        assertEquals(clientId, httpUrl.queryParameter("client_id"))
        assertEquals(redirectUri, httpUrl.queryParameter("redirect_uri"))
        assertEquals("S256", httpUrl.queryParameter("code_challenge_method"))
        assertNotNull(httpUrl.queryParameter("state"))
        assertNotNull(httpUrl.queryParameter("code_challenge"))
    }

    @Test
    fun buildAuthorizationUrl_returnsNullWhenClientIdIsBlank() {
        val urlString = oauthManager.buildAuthorizationUrl(
            provider = ExternalProvider.CLAUDE,
            clientId = ""
        )
        assertNull(urlString)
    }

    @Test
    fun parseAndValidateCallbackUri_validState_returnsSuccess() {
        val clientId = "test-client-id"
        val authUrl = oauthManager.buildAuthorizationUrl(
            provider = ExternalProvider.CHATGPT,
            clientId = clientId
        )
        assertNotNull(authUrl)
        val httpUrl = authUrl!!.toHttpUrlOrNull()!!
        val state = httpUrl.queryParameter("state")!!

        val callbackUri = "https://mnxmindmaker.app/oauth/callback?code=auth-code-789&state=$state"
        val result = oauthManager.parseAndValidateCallbackUri(callbackUri)

        assertTrue(result is OAuthAuthorizationResult.Success)
        val success = result as OAuthAuthorizationResult.Success
        assertEquals(ExternalProvider.CHATGPT, success.provider)
        assertEquals("auth-code-789", success.code)
        assertEquals(state, success.state)
        assertNotNull(success.codeVerifier)
    }

    @Test
    fun parseAndValidateCallbackUri_stateMismatch_returnsError() {
        val callbackUri = "https://mnxmindmaker.app/oauth/callback?code=auth-code-789&state=unknown-state"
        val result = oauthManager.parseAndValidateCallbackUri(callbackUri)

        assertTrue(result is OAuthAuthorizationResult.Error)
        val error = result as OAuthAuthorizationResult.Error
        assertEquals("state_mismatch", error.error)
    }

    @Test
    fun parseAndValidateCallbackUri_providerError_returnsError() {
        val callbackUri = "https://mnxmindmaker.app/oauth/callback?error=access_denied&error_description=User+cancelled"
        val result = oauthManager.parseAndValidateCallbackUri(callbackUri)

        assertTrue(result is OAuthAuthorizationResult.Error)
        val error = result as OAuthAuthorizationResult.Error
        assertEquals("access_denied", error.error)
        assertEquals("User cancelled", error.errorDescription)
    }
}
