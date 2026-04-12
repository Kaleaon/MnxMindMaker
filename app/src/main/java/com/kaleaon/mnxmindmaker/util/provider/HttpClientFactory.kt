package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.util.LlmApiException
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object HttpClientFactory {

    fun clientFor(settings: LlmSettings, baseClient: OkHttpClient): OkHttpClient {
        val normalizedPin = try {
            TlsPinParser.normalizeOrThrow(settings.tlsPinnedSpkiSha256)
        } catch (e: IllegalArgumentException) {
            throw LlmApiException(e.message ?: TlsPinParser.VALIDATION_MESSAGE)
        } ?: return baseClient
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
}
