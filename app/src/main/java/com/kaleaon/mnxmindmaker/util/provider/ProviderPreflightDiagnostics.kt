package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringWriter
import java.io.PrintWriter
import java.util.concurrent.TimeUnit

data class PreflightRootCause(
    val title: String,
    val description: String,
    val troubleshootingTip: String,
    val rawTrace: String
)

data class PreflightDiagnosticsResult(
    val provider: LlmProvider,
    val endpoint: String,
    val probePath: String,
    val reachable: Boolean,
    val statusCode: Int?,
    val latencyMs: Long,
    val detail: String,
    val rootCause: PreflightRootCause? = null
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
            LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED,
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
                val bodyText = response.body?.string().orEmpty().take(1000)
                val detail = if (response.isSuccessful) {
                    "Probe succeeded"
                } else {
                    "Probe returned HTTP ${response.code}"
                }
                val rootCause = if (response.isSuccessful) null else classifyError(
                    statusCode = response.code,
                    exception = null,
                    endpoint = settings.baseUrl,
                    provider = settings.provider,
                    responseBody = bodyText
                )
                PreflightDiagnosticsResult(
                    provider = settings.provider,
                    endpoint = settings.baseUrl,
                    probePath = probePath,
                    reachable = reachable,
                    statusCode = response.code,
                    latencyMs = latency,
                    detail = detail,
                    rootCause = rootCause
                )
            }
        } catch (e: Exception) {
            val rootCause = classifyError(
                statusCode = null,
                exception = e,
                endpoint = settings.baseUrl,
                provider = settings.provider,
                responseBody = null
            )
            PreflightDiagnosticsResult(
                provider = settings.provider,
                endpoint = settings.baseUrl,
                probePath = probePath,
                reachable = false,
                statusCode = null,
                latencyMs = System.currentTimeMillis() - start,
                detail = e.message ?: e.javaClass.simpleName,
                rootCause = rootCause
            )
        }
    }

    fun classifyError(
        statusCode: Int?,
        exception: Exception?,
        endpoint: String,
        provider: LlmProvider,
        responseBody: String? = null
    ): PreflightRootCause {
        return when {
            statusCode == 401 || statusCode == 403 -> PreflightRootCause(
                title = "Authentication Failed ($statusCode)",
                description = "The AI provider rejected the API key or authorization token.",
                troubleshootingTip = "Check your API key in Settings and verify your provider account status.",
                rawTrace = "HTTP $statusCode from $endpoint\nResponse: ${responseBody.orEmpty().ifBlank { "No body" }}"
            )
            statusCode == 404 -> PreflightRootCause(
                title = "Endpoint or Model Not Found (404)",
                description = "The provider URL or model probe path was not found on the server.",
                troubleshootingTip = "Verify the Base URL and Model ID in your settings.",
                rawTrace = "HTTP 404 at $endpoint\nResponse: ${responseBody.orEmpty().ifBlank { "No body" }}"
            )
            statusCode == 429 -> PreflightRootCause(
                title = "Rate Limit Exceeded (429)",
                description = "The provider account rate limit or quota has been reached.",
                troubleshootingTip = "Wait a few moments before retrying or check your billing plan.",
                rawTrace = "HTTP 429 Rate Limited by $endpoint\nResponse: ${responseBody.orEmpty().ifBlank { "No body" }}"
            )
            statusCode != null && statusCode >= 500 -> PreflightRootCause(
                title = "Provider Service Unavailable ($statusCode)",
                description = "The remote AI provider returned a server-side error.",
                troubleshootingTip = "The remote service may be undergoing maintenance. Try again later.",
                rawTrace = "HTTP $statusCode Server Error from $endpoint\nResponse: ${responseBody.orEmpty().ifBlank { "No body" }}"
            )
            exception != null -> {
                val sw = StringWriter()
                exception.printStackTrace(PrintWriter(sw))
                PreflightRootCause(
                    title = "Network Transport Failure",
                    description = "Could not establish a connection to the provider endpoint.",
                    troubleshootingTip = "Check your internet connection or URL host/port configuration.",
                    rawTrace = "Exception: ${exception.javaClass.name}: ${exception.message}\n$sw"
                )
            }
            else -> PreflightRootCause(
                title = "Preflight Check Failed (${statusCode ?: "Unknown"})",
                description = "An unexpected error occurred while testing the provider connection.",
                troubleshootingTip = "Review technical details or re-check settings configuration.",
                rawTrace = "Status Code: $statusCode\nResponse: ${responseBody.orEmpty().ifBlank { "No body" }}"
            )
        }
    }
}
