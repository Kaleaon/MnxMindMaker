package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class PreflightDiagnosticsResult(
    val provider: LlmProvider,
    val endpoint: String,
    val probePath: String,
    val reachable: Boolean,
    val statusCode: Int?,
    val latencyMs: Long,
    val detail: String
)

object ProviderPreflightDiagnostics {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun run(settings: LlmSettings): PreflightDiagnosticsResult {
        val probePath = when (settings.provider) {
            LlmProvider.ANTHROPIC -> "/messages"
            LlmProvider.OPENAI,
            LlmProvider.GEMINI,
            LlmProvider.VLLM_GEMMA4,
            LlmProvider.LOCAL_ON_DEVICE -> "/models"
        }

        val probeUrl = settings.baseUrl.trimEnd('/') + probePath
        val requestBuilder = Request.Builder()
            .url(probeUrl)
            .get()
            .addHeader("content-type", "application/json")

        if (settings.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${settings.apiKey}")
            if (settings.provider == LlmProvider.ANTHROPIC) {
                requestBuilder.addHeader("x-api-key", settings.apiKey)
                requestBuilder.addHeader("anthropic-version", "2023-06-01")
            }
        }

        val start = System.currentTimeMillis()
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                val reachable = response.code in 200..499
                val detail = if (response.isSuccessful) {
                    "Probe succeeded"
                } else {
                    "Probe returned HTTP ${response.code}"
                }
                PreflightDiagnosticsResult(
                    provider = settings.provider,
                    endpoint = settings.baseUrl,
                    probePath = probePath,
                    reachable = reachable,
                    statusCode = response.code,
                    latencyMs = latency,
                    detail = detail
                )
            }
        } catch (e: Exception) {
            PreflightDiagnosticsResult(
                provider = settings.provider,
                endpoint = settings.baseUrl,
                probePath = probePath,
                reachable = false,
                statusCode = null,
                latencyMs = System.currentTimeMillis() - start,
                detail = e.message ?: e.javaClass.simpleName
            )
        }
    }
}
