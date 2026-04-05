package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import android.util.Base64
import com.kaleaon.mnxmindmaker.model.IdentityMode
import com.kaleaon.mnxmindmaker.model.LocalAuthSession
import com.kaleaon.mnxmindmaker.security.SecureVault
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AuthRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val vault = SecureVault(context)

    fun getIdentityMode(): IdentityMode {
        val raw = prefs.getString(KEY_IDENTITY_MODE, IdentityMode.LOCAL_AUTH.name) ?: IdentityMode.LOCAL_AUTH.name
        return runCatching { IdentityMode.valueOf(raw) }.getOrDefault(IdentityMode.LOCAL_AUTH)
    }

    fun setIdentityMode(mode: IdentityMode) {
        prefs.edit().putString(KEY_IDENTITY_MODE, mode.name).apply()
    }

    fun saveLocalCredentials(email: String, password: String, enablePasskey: Boolean): LocalAuthSession {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPassword(password, salt)
        vault.putString(KEY_EMAIL, email)
        vault.putString(KEY_PASSWORD_HASH, hash)
        vault.putString(KEY_PASSWORD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))

        if (enablePasskey) {
            val passkeyId = randomToken(24)
            vault.putString(KEY_PASSKEY_ID, passkeyId)
        } else {
            vault.remove(KEY_PASSKEY_ID)
        }
        setIdentityMode(IdentityMode.LOCAL_AUTH)
        return createSession(email)
    }

    fun signInWithPassword(email: String, password: String): LocalAuthSession? {
        val storedEmail = vault.getString(KEY_EMAIL) ?: return null
        if (!storedEmail.equals(email, ignoreCase = true)) return null

        val storedHash = vault.getString(KEY_PASSWORD_HASH) ?: return null
        val saltEncoded = vault.getString(KEY_PASSWORD_SALT) ?: return null
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        val candidate = hashPassword(password, salt)
        if (candidate != storedHash) return null

        return createSession(storedEmail)
    }

    fun signInWithPasskey(): LocalAuthSession? {
        val storedEmail = vault.getString(KEY_EMAIL) ?: return null
        val passkeyId = vault.getString(KEY_PASSKEY_ID) ?: return null
        if (passkeyId.isBlank()) return null
        return createSession(storedEmail)
    }

    fun getPasskeyStatus(): Boolean = !vault.getString(KEY_PASSKEY_ID).isNullOrBlank()

    fun getSession(): LocalAuthSession? {
        val token = vault.getString(KEY_SESSION_TOKEN) ?: return null
        val email = vault.getString(KEY_EMAIL) ?: return null
        val expiresAt = prefs.getLong(KEY_SESSION_EXPIRY, 0L)
        if (expiresAt <= System.currentTimeMillis()) {
            revokeSession()
            return null
        }
        return LocalAuthSession(token, email, expiresAt)
    }

    fun revokeSession() {
        vault.remove(KEY_SESSION_TOKEN)
        prefs.edit().remove(KEY_SESSION_EXPIRY).apply()
    }

    fun getStoredEmail(): String = vault.getString(KEY_EMAIL).orEmpty()

    private fun createSession(email: String): LocalAuthSession {
        val token = randomToken(48)
        val expiry = System.currentTimeMillis() + SESSION_TTL_MS
        vault.putString(KEY_SESSION_TOKEN, token)
        prefs.edit().putLong(KEY_SESSION_EXPIRY, expiry).apply()
        return LocalAuthSession(token, email, expiry)
    }

    private fun hashPassword(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = skf.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun randomToken(byteSize: Int): String {
        val bytes = ByteArray(byteSize).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object {
        private const val PREFS_NAME = "mnx_identity"
        private const val KEY_IDENTITY_MODE = "identity_mode"
        private const val KEY_EMAIL = "identity_email"
        private const val KEY_PASSWORD_HASH = "identity_password_hash"
        private const val KEY_PASSWORD_SALT = "identity_password_salt"
        private const val KEY_PASSKEY_ID = "identity_passkey_id"
        private const val KEY_SESSION_TOKEN = "identity_session_token"
        private const val KEY_SESSION_EXPIRY = "identity_session_expiry"
        private const val SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
