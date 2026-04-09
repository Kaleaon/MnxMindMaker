package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.LlmApiException
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object HttpClientFactory {

    private val base64Sha256Regex = Regex("^[A-Za-z0-9+/]{43}=$")

    fun clientFor(settings: LlmSettings, baseClient: OkHttpClient): OkHttpClient {
        val normalizedPin = normalizePin(settings.tlsPinnedSpkiSha256) ?: return baseClient
        val host = settings.baseUrl.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }
            ?: throw LlmApiException(
                "TLS pinning requires a valid base URL host. Received baseUrl='${settings.baseUrl}'."
            )

        val pinner = CertificatePinner.Builder()
            .add(host, "sha256/$normalizedPin")
            .build()

        return baseClient.newBuilder()
            .certificatePinner(pinner)
            .build()
    }

    private fun normalizePin(rawPin: String): String? {
        val trimmed = rawPin.trim()
        if (trimmed.isBlank()) return null

        val withoutPrefix = if (trimmed.startsWith("sha256/", ignoreCase = true)) {
            trimmed.substringAfter('/')
        } else {
            trimmed
        }

        if (!base64Sha256Regex.matches(withoutPrefix)) {
            throw LlmApiException(
                "Invalid tlsPinnedSpkiSha256 format. Expected base64 SHA-256 SPKI hash (43 chars + '='), without the 'sha256/' prefix."
            )
        }

        return withoutPrefix
    }
}
