package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ExternalAccountRepositoryTest {

    private class TestSharedPreferences : android.content.SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : android.content.SharedPreferences.Editor {
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { map[key] = value; return this }
            override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor { map[key] = values; return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { map[key] = value; return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { map[key] = value; return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { map[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { map[key] = value; return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    private val context: Context = object : ContextWrapper(null) {
        private val prefs = TestSharedPreferences()
        override fun getFilesDir(): File = File(System.getProperty("java.io.tmpdir"), "test-files").also { it.mkdirs() }
        override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = prefs
        override fun getPackageName(): String = "com.kaleaon.mnxmindmaker"
        override fun getContentResolver(): android.content.ContentResolver = object : android.content.ContentResolver(null) {}
    }

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
