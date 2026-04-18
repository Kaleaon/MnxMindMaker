package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExternalAccountRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun parseTokenRefreshResponse_parsesSuccessPayload() {
        val payload = ExternalAccountRepository.parseTokenRefreshResponse(
            """
            {
              "access_token": "new-access-token",
              "refresh_token": "new-refresh-token",
              "expires_in": 3600
            }
            """.trimIndent()
        )

        assertNotNull(payload)
        assertEquals("new-access-token", payload?.accessToken)
        assertEquals("new-refresh-token", payload?.refreshToken)
        assertEquals(3600L, payload?.expiresInSeconds)
    }

    @Test
    fun parseTokenRefreshResponse_handlesMissingOptionalFields() {
        val payload = ExternalAccountRepository.parseTokenRefreshResponse(
            """
            {
              "access_token": "access-only"
            }
            """.trimIndent()
        )

        assertNotNull(payload)
        assertEquals("access-only", payload?.accessToken)
        assertNull(payload?.refreshToken)
        assertNull(payload?.expiresInSeconds)
    }

    @Test
    fun parseTokenRefreshResponse_returnsNullForMalformedJson() {
        val payload = ExternalAccountRepository.parseTokenRefreshResponse("{not json")
        assertNull(payload)
    }

    @Test
    fun expiryFromNow_appliesExpiresInSeconds() {
        val now = 1_700_000_000_000L
        val expiresInSeconds = 1800L

        val expiresAt = ExternalAccountRepository.expiryFromNow(now, expiresInSeconds)

        assertEquals(now + TimeUnit.SECONDS.toMillis(expiresInSeconds), expiresAt)
    }

    @Test
    fun validateRefreshPrerequisites_returnsMissingClientConfig_whenClientCredentialsAreMissing() {
        val status = ExternalAccountRepository.validateRefreshPrerequisites(
            refreshToken = "refresh-token",
            clientId = "",
            clientSecret = ""
        )

        assertEquals(RefreshStatus.MISSING_CLIENT_CONFIG, status)
    }

    @Test
    fun validateRefreshPrerequisites_returnsSuccess_whenRefreshTokenAndClientCredentialsExist() {
        val status = ExternalAccountRepository.validateRefreshPrerequisites(
            refreshToken = "refresh-token",
            clientId = "client-id",
            clientSecret = "client-secret"
        )

        assertEquals(RefreshStatus.SUCCESS, status)
    }

    @Test
    fun getLinkState_relinkWithExpiry_setsExpiry() {
        val repository = ExternalAccountRepository(context)
        val provider = ExternalProvider.HUGGING_FACE
        repository.revoke(provider)

        val beforeLink = System.currentTimeMillis()
        repository.linkAccount(
            provider = provider,
            accessToken = "access-token-with-expiry",
            refreshToken = "refresh-token",
            expiresInSeconds = 120L
        )
        val afterLink = System.currentTimeMillis()

        val state = repository.getLinkState(provider)
        val minExpected = beforeLink + TimeUnit.SECONDS.toMillis(120L)
        val maxExpected = afterLink + TimeUnit.SECONDS.toMillis(120L)

        assertTrue(state.linked)
        assertNotNull(state.expiresAtEpochMs)
        assertTrue(state.expiresAtEpochMs!! in minExpected..maxExpected)

        repository.revoke(provider)
    }

    @Test
    fun getLinkState_relinkWithoutExpiry_clearsPreviousExpiry() {
        val repository = ExternalAccountRepository(context)
        val provider = ExternalProvider.HUGGING_FACE
        repository.revoke(provider)

        repository.linkAccount(
            provider = provider,
            accessToken = "access-token-with-expiry",
            refreshToken = "refresh-token",
            expiresInSeconds = 300L
        )
        val firstLinkState = repository.getLinkState(provider)
        assertNotNull(firstLinkState.expiresAtEpochMs)

        repository.linkAccount(
            provider = provider,
            accessToken = "access-token-without-expiry",
            refreshToken = "refresh-token-2",
            expiresInSeconds = null
        )
        val relinkState = repository.getLinkState(provider)

        assertTrue(relinkState.linked)
        assertNull(relinkState.expiresAtEpochMs)
        assertFalse(relinkState.capabilities?.models.isNullOrEmpty())

        repository.revoke(provider)
    }
}
