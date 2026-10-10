package com.kaleaon.mnxmindmaker.security

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stores sensitive values in the OS-backed keystore when available and transparently
 * falls back to an encrypted vault when that path is unavailable.
 */
class SecureVault(private val context: Context) {

    private val delegate: KeyValueVault by lazy {
        runCatching { EncryptedPrefsVault.create(context) }
            .getOrElse { FallbackEncryptedVault(context) }
    }

    fun putString(key: String, value: String) = delegate.putString(key, value)

    fun getString(key: String): String? = delegate.getString(key)

    fun remove(key: String) = delegate.remove(key)

    private interface KeyValueVault {
        fun putString(key: String, value: String)
        fun getString(key: String): String?
        fun remove(key: String)
    }

    private class EncryptedPrefsVault(private val prefs: SharedPreferences) : KeyValueVault {
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun getString(key: String): String? = prefs.getString(key, null)

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }

        companion object {
            private const val PREFS_NAME = "mnx_secure_vault"

            fun create(context: Context): EncryptedPrefsVault {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                return EncryptedPrefsVault(prefs)
            }
        }
    }

    private class FallbackEncryptedVault(context: Context) : KeyValueVault {
        private val prefs: SharedPreferences? = runCatching { context.getSharedPreferences("mnx_secure_vault_fallback", Context.MODE_PRIVATE) }.getOrNull()
        private val inMemoryStore = mutableMapOf<String, String>()
        private val secretKey = deriveDeviceBoundKey(context)

        override fun putString(key: String, value: String) {
            val encrypted = encrypt(value)
            val p = prefs
            if (p != null) {
                p.edit().putString(key, encrypted).apply()
            } else {
                inMemoryStore[key] = encrypted
            }
        }

        override fun getString(key: String): String? {
            val p = prefs
            val raw = if (p != null) p.getString(key, null) else inMemoryStore[key]
            if (raw == null) return null
            return runCatching { decrypt(raw) }.getOrNull()
        }

        override fun remove(key: String) {
            val p = prefs
            if (p != null) {
                p.edit().remove(key).apply()
            } else {
                inMemoryStore.remove(key)
            }
        }

        private fun deriveDeviceBoundKey(context: Context): SecretKeySpec {
            val packageName = runCatching { context.packageName }.getOrNull() ?: "com.kaleaon.mnxmindmaker"
            val androidId = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
                ?: "unknown-device"
            val material = "$packageName|$androidId|mnxmindmaker".toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(material)
            return SecretKeySpec(digest.copyOf(32), "AES")
        }

        private fun encrypt(plaintext: String): String {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            return java.util.Base64.getEncoder().encodeToString(iv + ciphertext)
        }

        private fun decrypt(encoded: String): String {
            val payload = java.util.Base64.getDecoder().decode(encoded)
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext)
            return String(plaintext, StandardCharsets.UTF_8)
        }
    }
}
