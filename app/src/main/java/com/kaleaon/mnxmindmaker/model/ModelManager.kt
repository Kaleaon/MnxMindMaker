package com.kaleaon.mnxmindmaker.model

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

enum class ModelInstallState {
    DISCOVERED,
    INSTALLED,
    CORRUPT
}

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val quantizationProfile: String,
    val sourceUrl: String,
    val expectedSha256: String,
    val expectedSignatureBase64: String? = null,
    val publicKeyBase64: String? = null,
    val sizeBytes: Long,
    val state: ModelInstallState = ModelInstallState.DISCOVERED,
    val localPath: String = "",
    val pinned: Boolean = false
)

class ModelManager(private val context: Context) {

    private val modelRoot: File = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("model_manager", Context.MODE_PRIVATE)

    fun discoverModels(): List<ModelDescriptor> {
        val installed = loadInstalledModelMetadata().associateBy { "${it.id}:${it.version}" }
        return defaultCatalog().map { model ->
            val installedModel = installed["${model.id}:${model.version}"]
            if (installedModel != null) {
                val integrityOk = verifyIntegrity(
                    File(installedModel.localPath),
                    installedModel.expectedSha256,
                    installedModel.expectedSignatureBase64,
                    installedModel.publicKeyBase64
                )
                installedModel.copy(state = if (integrityOk) ModelInstallState.INSTALLED else ModelInstallState.CORRUPT)
            } else {
                model
            }
        }
    }

    fun installModelOneClick(modelId: String, enforceQuota: Boolean = true): Result<ModelDescriptor> {
        val candidate = defaultCatalog().firstOrNull { it.id == modelId }
            ?: return Result.failure(IllegalArgumentException("Unknown model: $modelId"))

        if (enforceQuota) {
            evictUntilFits(candidate.sizeBytes)
        }

        val modelDir = File(modelRoot, candidate.id).apply { mkdirs() }
        val output = File(modelDir, "${candidate.version}-${candidate.quantizationProfile}.bin")

        return runCatching {
            // Placeholder install payload to keep module offline-safe.
            FileOutputStream(output).use { stream ->
                stream.write("MODEL:${candidate.id}:${candidate.version}:${candidate.quantizationProfile}".toByteArray())
            }

            val installed = candidate.copy(
                state = ModelInstallState.INSTALLED,
                localPath = output.absolutePath,
                pinned = isPinned(candidate.id, candidate.version)
            )
            saveInstalledModel(installed)
            installed
        }
    }

    fun setDiskQuotaMb(quotaMb: Long) {
        prefs.edit().putLong(KEY_DISK_QUOTA_MB, quotaMb.coerceAtLeast(256)).apply()
    }

    fun getDiskQuotaMb(): Long = prefs.getLong(KEY_DISK_QUOTA_MB, 4096L)

    fun pinVersion(modelId: String, version: String, pinned: Boolean) {
        val key = "$modelId:$version"
        val pins = prefs.getStringSet(KEY_PINNED_VERSIONS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (pinned) pins += key else pins -= key
        prefs.edit().putStringSet(KEY_PINNED_VERSIONS, pins).apply()
    }

    fun resolvePinnedVersion(modelId: String): String? {
        val pins = prefs.getStringSet(KEY_PINNED_VERSIONS, emptySet()) ?: emptySet()
        return pins.firstOrNull { it.startsWith("$modelId:") }?.substringAfter(':')
    }

    fun verifyIntegrity(
        file: File,
        expectedSha256: String,
        signatureBase64: String? = null,
        publicKeyBase64: String? = null
    ): Boolean {
        if (!file.exists() || !file.isFile) return false
        val hash = sha256(file)
        val hashMatch = hash.equals(expectedSha256, ignoreCase = true)
        if (!hashMatch) return false

        if (signatureBase64.isNullOrBlank() || publicKeyBase64.isNullOrBlank()) return true
        return runCatching {
            val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update(hash.toByteArray())
            }
            signature.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }.getOrDefault(false)
    }

    private fun evictUntilFits(incomingSizeBytes: Long) {
        val quotaBytes = getDiskQuotaMb() * 1024L * 1024L
        val installed = loadInstalledModelMetadata().sortedBy { File(it.localPath).lastModified() }.toMutableList()
        var used = installed.sumOf { File(it.localPath).takeIf(File::exists)?.length() ?: 0L }
        if (used + incomingSizeBytes <= quotaBytes) return

        val pinnedSet = prefs.getStringSet(KEY_PINNED_VERSIONS, emptySet()) ?: emptySet()
        installed.forEach { model ->
            val key = "${model.id}:${model.version}"
            if (key in pinnedSet) return@forEach
            File(model.localPath).delete()
            used -= model.sizeBytes
            removeInstalledModel(model)
            if (used + incomingSizeBytes <= quotaBytes) return
        }
    }

    private fun isPinned(modelId: String, version: String): Boolean {
        return (prefs.getStringSet(KEY_PINNED_VERSIONS, emptySet()) ?: emptySet()).contains("$modelId:$version")
    }

    private fun saveInstalledModel(model: ModelDescriptor) {
        val all = loadInstalledModelMetadata().toMutableList()
        val index = all.indexOfFirst { it.id == model.id && it.version == model.version }
        if (index >= 0) all[index] = model else all += model
        val json = JSONArray()
        all.forEach { json.put(it.toJson()) }
        prefs.edit().putString(KEY_INSTALLED_MODELS, json.toString()).apply()
    }

    private fun removeInstalledModel(model: ModelDescriptor) {
        val all = loadInstalledModelMetadata().filterNot { it.id == model.id && it.version == model.version }
        val json = JSONArray()
        all.forEach { json.put(it.toJson()) }
        prefs.edit().putString(KEY_INSTALLED_MODELS, json.toString()).apply()
    }

    private fun loadInstalledModelMetadata(): List<ModelDescriptor> {
        val raw = prefs.getString(KEY_INSTALLED_MODELS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                arr.optJSONObject(idx)?.toModelDescriptor()
            }
        }.getOrDefault(emptyList())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun defaultCatalog(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "qwen2_5_7b",
            displayName = "Qwen 2.5 7B Instruct",
            version = "1.0.0",
            quantizationProfile = "Q4_K_M",
            sourceUrl = "https://models.local/qwen2.5-7b-instruct-q4.gguf",
            expectedSha256 = "d41d8cd98f00b204e9800998ecf8427e",
            sizeBytes = 4_400_000_000L,
        ),
        ModelDescriptor(
            id = "llama3_1_8b",
            displayName = "Llama 3.1 8B Instruct",
            version = "1.0.0",
            quantizationProfile = "Q5_K_M",
            sourceUrl = "https://models.local/llama3.1-8b-instruct-q5.gguf",
            expectedSha256 = "d41d8cd98f00b204e9800998ecf8427e",
            sizeBytes = 5_100_000_000L,
        )
    )

    private fun ModelDescriptor.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("version", version)
        put("quantizationProfile", quantizationProfile)
        put("sourceUrl", sourceUrl)
        put("expectedSha256", expectedSha256)
        put("expectedSignatureBase64", expectedSignatureBase64)
        put("publicKeyBase64", publicKeyBase64)
        put("sizeBytes", sizeBytes)
        put("state", state.name)
        put("localPath", localPath)
        put("pinned", pinned)
    }

    private fun JSONObject.toModelDescriptor(): ModelDescriptor {
        return ModelDescriptor(
            id = optString("id"),
            displayName = optString("displayName"),
            version = optString("version"),
            quantizationProfile = optString("quantizationProfile"),
            sourceUrl = optString("sourceUrl"),
            expectedSha256 = optString("expectedSha256"),
            expectedSignatureBase64 = optString("expectedSignatureBase64").ifBlank { null },
            publicKeyBase64 = optString("publicKeyBase64").ifBlank { null },
            sizeBytes = optLong("sizeBytes", 0L),
            state = runCatching { ModelInstallState.valueOf(optString("state", ModelInstallState.DISCOVERED.name)) }
                .getOrDefault(ModelInstallState.DISCOVERED),
            localPath = optString("localPath"),
            pinned = optBoolean("pinned", false)
        )
    }

    companion object {
        private const val KEY_INSTALLED_MODELS = "installed_models"
        private const val KEY_DISK_QUOTA_MB = "disk_quota_mb"
        private const val KEY_PINNED_VERSIONS = "pinned_versions"
    }
}
