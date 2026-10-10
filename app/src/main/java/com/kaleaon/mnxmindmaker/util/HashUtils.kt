package com.kaleaon.mnxmindmaker.util

import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object HashUtils {

    fun sha256Digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun sha256(bytes: ByteArray): ByteArray {
        return sha256Digest().digest(bytes)
    }

    fun sha256(value: String): ByteArray {
        return sha256(value.toByteArray(StandardCharsets.UTF_8))
    }

    fun sha256Bytes(input: InputStream): ByteArray {
        val digest = sha256Digest()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest()
    }

    fun sha256Hex(value: String): String {
        return sha256Hex(value.toByteArray(StandardCharsets.UTF_8))
    }

    fun sha256Hex(bytes: ByteArray): String {
        return toHexString(sha256(bytes))
    }

    fun sha256Hex(file: File): String {
        return file.inputStream().use { sha256Hex(it) }
    }

    fun sha256Hex(input: InputStream): String {
        return toHexString(sha256Bytes(input))
    }

    fun toHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }
}
