package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalRuntimeEngine
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class LlmEdgeProvider(
    private val httpClient: OkHttpClient = defaultClient(),
    private val jsonMediaType: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
) : AssistantProvider {

    override val id: String = "llmedge"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(reportsTokenUsage = true)

    override fun supports(settings: LlmSettings): Boolean = settings.provider == LlmProvider.LOCAL_ON_DEVICE

    override fun chat(request: ProviderRequest): AssistantTurn {
        return runCatching {
            val body = ProviderJsonAdapters.openAiBody(
                settings = request.settings,
                systemPrompt = request.systemPrompt,
                transcript = request.transcript,
                tools = if (request.settings.capabilities.supportsToolPlanning) request.tools else emptyList()
            ).apply {
                put("extra_body", JSONObject().apply {
                    if (request.settings.localModelPath.isNotBlank()) {
                        put("local_model_path", request.settings.localModelPath)
                    }
                    put("runtime", request.settings.runtimeControls.engine.runtimeTag())
                })
            }

            val apiKey = request.settings.apiKey.ifBlank { "EMPTY" }
            val httpRequest = Request.Builder()
                .url("${request.settings.baseUrl}/chat/completions")
                .post(body.toString().toRequestBody(jsonMediaType))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("content-type", "application/json")
                .build()

            httpClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: throw LlmApiException("Empty response from LLMEdge")
                if (!response.isSuccessful) {
                    throw LlmApiException("LLMEdge error ${response.code}: $responseBody")
                }
                ProviderJsonAdapters.parseOpenAiTurn(JSONObject(responseBody))
            }
        }.getOrElse { throwable ->
            throw mapException("LLMEdge chat failure", throwable)
        }
    }

    override fun healthCheck(settings: LlmSettings): ProviderHealth {
        if (!supports(settings)) return ProviderHealth(false, "Unsupported settings for LlmEdgeProvider")

        return try {
            val probe = Request.Builder()
                .url("${settings.baseUrl.trimEnd('/')}/${settings.runtimeControls.engine.healthProbePath()}")
                .get()
                .build()

            httpClient.newCall(probe).execute().use { response ->
                if (response.isSuccessful) {
                    ProviderHealth(true, "LLMEdge runtime reachable")
                } else {
                    ProviderHealth(false, "LLMEdge readiness check failed with HTTP ${response.code}")
                }
            }
        } catch (throwable: Throwable) {
            ProviderHealth(
                false,
                mapException("LLMEdge readiness check failed", throwable).message
                    ?: "Unknown health check failure"
            )
        }
    }

    private fun mapException(prefix: String, throwable: Throwable): LlmApiException {
        if (throwable is LlmApiException) return throwable

        val message = when (throwable) {
            is UnknownHostException -> "$prefix: unable to resolve host (${throwable.message ?: "unknown host"})"
            is ConnectException -> "$prefix: connection refused (${throwable.message ?: "connect failed"})"
            is SocketTimeoutException -> "$prefix: timeout while contacting runtime"
            is IOException -> "$prefix: I/O error (${throwable.message ?: throwable.javaClass.simpleName})"
            is IllegalArgumentException -> "$prefix: invalid request (${throwable.message ?: "bad input"})"
            else -> "$prefix: ${throwable.message ?: throwable.javaClass.simpleName}"
        }
        return LlmApiException(message, throwable)
    }

    private fun LocalRuntimeEngine.runtimeTag(): String = when (this) {
        LocalRuntimeEngine.LLMEDGE -> "llmedge"
        LocalRuntimeEngine.LITERT_LM -> "litert-lm"
    }

    private fun LocalRuntimeEngine.healthProbePath(): String = when (this) {
        LocalRuntimeEngine.LLMEDGE,
        LocalRuntimeEngine.LITERT_LM -> "models"
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
