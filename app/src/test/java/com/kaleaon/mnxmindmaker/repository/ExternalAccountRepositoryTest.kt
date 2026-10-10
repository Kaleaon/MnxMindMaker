package com.kaleaon.mnxmindmaker.repository

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ExternalAccountRepositoryTest {

    private val context: Context = createTestContext()

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

private fun createTestContext(filesDir: File = File("/tmp")): Context {
    val prefsMap = mutableMapOf<String, MutableMap<String, Any?>>()
    return object : ContextWrapper(null) {
        override fun getFilesDir(): File = filesDir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val store = prefsMap.getOrPut(name) { mutableMapOf() }
            return FakeSharedPreferences(store)
        }
        override fun getPackageName(): String = "com.kaleaon.mnxmindmaker"
        override fun getContentResolver(): ContentResolver? = null
    }
}

private class FakeSharedPreferences(
    private val data: MutableMap<String, Any?> = mutableMapOf()
) : SharedPreferences {
    override fun getAll(): Map<String, *> = data
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { changes[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { changes[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { changes[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { changes[key] = this; return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) data.clear()
            for ((k, v) in changes) {
                if (v === this) data.remove(k) else data[k] = v
            }
        }
    }
}
