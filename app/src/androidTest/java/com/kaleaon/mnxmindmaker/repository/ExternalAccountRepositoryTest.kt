package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExternalAccountRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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
