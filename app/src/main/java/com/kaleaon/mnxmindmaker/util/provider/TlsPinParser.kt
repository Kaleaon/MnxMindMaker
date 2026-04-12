package com.kaleaon.mnxmindmaker.util.provider

object TlsPinParser {

    const val VALIDATION_MESSAGE =
        "TLS pin must be a base64 SHA-256 SPKI hash (43 chars + '=') with or without 'sha256/' prefix."

    private val base64Sha256Regex = Regex("^[A-Za-z0-9+/]{43}=$")

    fun normalizeOrThrow(rawPin: String): String? {
        val trimmed = rawPin.trim()
        if (trimmed.isBlank()) return null

        val withoutPrefix = if (trimmed.startsWith("sha256/", ignoreCase = true)) {
            trimmed.substringAfter('/')
        } else {
            trimmed
        }

        if (!base64Sha256Regex.matches(withoutPrefix)) {
            throw IllegalArgumentException(VALIDATION_MESSAGE)
        }

        return withoutPrefix
    }
}
