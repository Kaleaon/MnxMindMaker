package com.kaleaon.mnxmindmaker.security

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val KEYRING_VAULT_KEY = "app_key_hierarchy.v1"
private const val ENVELOPE_MAGIC = "MMK-ENC-1"
private const val BACKUP_MAGIC = "MMK-BACKUP-1"
private const val BUNDLE_MAGIC = "MMK-BUNDLE-1"

interface KeyHierarchyStore {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
}

private class SecureVaultKeyHierarchyStore(private val secureVault: SecureVault) : KeyHierarchyStore {
    override fun putString(key: String, value: String) = secureVault.putString(key, value)
    override fun getString(key: String): String? = secureVault.getString(key)
}

class AppManagedKeyHierarchy(private val store: KeyHierarchyStore) {

    constructor(secureVault: SecureVault) : this(SecureVaultKeyHierarchyStore(secureVault))

    data class KeyMaterial(val version: Int, val key: ByteArray)

    private val random = SecureRandom()

    fun activeKey(): KeyMaterial {
        val keyring = loadKeyring()
        val active = keyring.getInt("active_version")
        val keys = keyring.getJSONObject("keys")
        val encoded = keys.getString(active.toString())
        return KeyMaterial(active, Base64.getDecoder().decode(encoded))
    }

    fun keyForVersion(version: Int): ByteArray? {
        val keys = loadKeyring().getJSONObject("keys")
        if (!keys.has(version.toString())) return null
        return Base64.getDecoder().decode(keys.getString(version.toString()))
    }

    fun rotate(): Int {
        val keyring = loadKeyring()
        val keys = keyring.getJSONObject("keys")
        val nextVersion = keyring.getInt("active_version") + 1
        keys.put(nextVersion.toString(), randomKey())
        keyring.put("active_version", nextVersion)
        keyring.put("updated_at", System.currentTimeMillis())
        store.putString(KEYRING_VAULT_KEY, keyring.toString())
        return nextVersion
    }

    fun exportEncryptedSnapshot(passphrase: String): String {
        val rawKeyring = loadKeyring().toString().toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = passphraseKey(passphrase, salt)
        val ciphertext = aesGcmEncrypt(rawKeyring, key, iv)
        return JSONObject()
            .put("magic", BACKUP_MAGIC)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", 120_000)
            .put("salt_b64", b64(salt))
            .put("iv_b64", b64(iv))
            .put("ciphertext_b64", b64(ciphertext))
            .toString()
    }

    fun importEncryptedSnapshot(payload: String, passphrase: String) {
        val obj = JSONObject(payload)
        require(obj.optString("magic") == BACKUP_MAGIC) { "Invalid backup payload" }
        val salt = unb64(obj.getString("salt_b64"))
        val iv = unb64(obj.getString("iv_b64"))
        val ciphertext = unb64(obj.getString("ciphertext_b64"))
        val key = passphraseKey(passphrase, salt)
        val plaintext = aesGcmDecrypt(ciphertext, key, iv)
        store.putString(KEYRING_VAULT_KEY, String(plaintext, StandardCharsets.UTF_8))
    }

    private fun loadKeyring(): JSONObject {
        val existing = store.getString(KEYRING_VAULT_KEY)
        if (!existing.isNullOrBlank()) return JSONObject(existing)
        val initial = JSONObject()
            .put("active_version", 1)
            .put("created_at", System.currentTimeMillis())
            .put("updated_at", System.currentTimeMillis())
            .put("keys", JSONObject().put("1", randomKey()))
        store.putString(KEYRING_VAULT_KEY, initial.toString())
        return initial
    }

    private fun randomKey(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return b64(bytes)
    }

    private fun passphraseKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun aesGcmEncrypt(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun b64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
    private fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
}

class EncryptedArtifactStore private constructor(
    private val random: SecureRandom,
    private val hierarchy: AppManagedKeyHierarchy
) {
    constructor(context: Context) : this(SecureRandom(), AppManagedKeyHierarchy(SecureVault(context)))

    internal constructor(hierarchy: AppManagedKeyHierarchy) : this(SecureRandom(), hierarchy)

    constructor(context: Context) : this(
        random = SecureRandom(),
        hierarchy = AppManagedKeyHierarchy(SecureVault(context))
    )

    internal constructor(hierarchy: AppManagedKeyHierarchy) : this(
        random = SecureRandom(),
        hierarchy = hierarchy
    )

    fun writeEncryptedBytes(file: File, plaintext: ByteArray, artifactType: String) {
        file.parentFile?.mkdirs()
        val active = hierarchy.activeKey()
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(active.key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(artifactType.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = JSONObject()
            .put("magic", ENVELOPE_MAGIC)
            .put("key_version", active.version)
            .put("artifact_type", artifactType)
            .put("created_at", System.currentTimeMillis())
            .put("iv_b64", Base64.getEncoder().encodeToString(iv))
            .put("ciphertext_b64", Base64.getEncoder().encodeToString(ciphertext))
            .toString()
        file.writeText(envelope)
    }

    fun readDecryptedBytes(file: File, expectedArtifactType: String): ByteArray {
        val payload = file.readText()
        return decryptIfEnvelope(payload.toByteArray(StandardCharsets.UTF_8), expectedArtifactType)
    }

    fun decryptIfEnvelope(raw: ByteArray, expectedArtifactType: String): ByteArray {
        val text = runCatching { String(raw, StandardCharsets.UTF_8) }.getOrNull() ?: return raw
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return raw
        if (obj.optString("magic") != ENVELOPE_MAGIC) return raw
        val keyVersion = obj.getInt("key_version")
        val artifactType = obj.optString("artifact_type")
        if (artifactType != expectedArtifactType) {
            throw IllegalArgumentException("Unexpected artifact type: $artifactType")
        }
        val key = hierarchy.keyForVersion(keyVersion)
            ?: throw IllegalStateException("Missing key version: $keyVersion")
        val iv = Base64.getDecoder().decode(obj.getString("iv_b64"))
        val ciphertext = Base64.getDecoder().decode(obj.getString("ciphertext_b64"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(expectedArtifactType.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    fun rotateDataKey(): Int = hierarchy.rotate()

    fun exportBackup(filePayloads: Map<String, ByteArray>, passphrase: String, outFile: File): File {
        val manifest = JSONObject()
        filePayloads.forEach { (name, payload) ->
            manifest.put(name, Base64.getEncoder().encodeToString(payload))
        }
        val wrappedHierarchy = hierarchy.exportEncryptedSnapshot(passphrase)
        val backup = JSONObject()
            .put("magic", BUNDLE_MAGIC)
            .put("created_at", System.currentTimeMillis())
            .put("recovery", JSONObject(wrappedHierarchy))
            .put("files", manifest)
        outFile.parentFile?.mkdirs()
        outFile.writeText(backup.toString())
        return outFile
    }

    fun recoverHierarchyFromBackup(backupPayload: String, passphrase: String) {
        val root = runCatching { JSONObject(backupPayload) }
            .getOrElse { throw IllegalArgumentException("Backup payload must be valid JSON", it) }
        val magic = root.optString("magic")
        require(magic == "MMK-BUNDLE-1") { "Invalid backup bundle magic: expected MMK-BUNDLE-1" }

        val recoveryRaw = root.opt("recovery")
            ?: throw IllegalArgumentException("Missing required backup field: recovery")
        val recovery = recoveryRaw as? JSONObject
            ?: throw IllegalArgumentException("Invalid backup field: recovery must be a JSON object")

            .getOrElse { throw IllegalArgumentException("Invalid backup bundle: payload is not valid JSON.", it) }
        require(root.optString("magic") == BUNDLE_MAGIC) {
            "Invalid backup bundle: expected magic '$BUNDLE_MAGIC'."
        }
        if (!root.has("recovery")) {
            throw IllegalArgumentException("Invalid backup bundle: missing 'recovery' object.")
        }
        val recovery = root.optJSONObject("recovery")
            ?: throw IllegalArgumentException("Invalid backup bundle: 'recovery' must be a JSON object.")
        hierarchy.importEncryptedSnapshot(recovery.toString(), passphrase)
    }
}
