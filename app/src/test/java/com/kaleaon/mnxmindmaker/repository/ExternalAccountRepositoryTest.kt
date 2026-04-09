package com.kaleaon.mnxmindmaker.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ExternalAccountRepositoryTest {

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
}
