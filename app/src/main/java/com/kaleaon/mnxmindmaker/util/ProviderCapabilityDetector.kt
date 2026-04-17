package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.ProviderCapabilityMetadata
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class ProviderCapabilityDetector {

    private val httpClient = OkHttpClient()
    private val cacheTtlMs: Long = 30 * 60 * 1000L
    private val cache = mutableMapOf<ExternalProvider, ProviderCapabilityMetadata>()

    fun detect(provider: ExternalProvider, accessToken: String? = null): ProviderCapabilityMetadata {
        val cached = cache[provider]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.detectedAtEpochMs <= cacheTtlMs) {
            return cached
        }
        val probe = when (provider) {
            ExternalProvider.CLAUDE -> probeClaudeModels(accessToken)
            ExternalProvider.CHATGPT -> probeOpenAiModels(accessToken)
            ExternalProvider.HUGGING_FACE -> probeHuggingFaceModels(accessToken)
        }

        val fallbackModels = fallbackModels(provider)
        val metadata = ProviderCapabilityMetadata(
            models = probe.models.ifEmpty { fallbackModels },
            supportsToolUse = probe.supportsToolUse,
            rateLimitInfo = probe.rateLimitInfo,
            detectedAtEpochMs = now
        )
        cache[provider] = metadata
        return metadata
    }

    private fun fallbackModels(provider: ExternalProvider): List<String> = when (provider) {
        ExternalProvider.CLAUDE -> listOf("claude-3-7-sonnet", "claude-3-5-sonnet")
        ExternalProvider.CHATGPT -> listOf("gpt-4o", "gpt-4.1", "gpt-4.1-mini")
        ExternalProvider.HUGGING_FACE -> listOf(
            "google/gemma-3n-E2B-it-litert-lm",
            "Qwen/Qwen2.5-7B-Instruct",
            "meta-llama/Llama-3.1-8B-Instruct"
        )
    }

    private fun probeOpenAiModels(accessToken: String?): ProbeResult {
        if (accessToken.isNullOrBlank()) {
            return ProbeResult(
                models = emptyList(),
                supportsToolUse = true,
                rateLimitInfo = "OpenAI account limits unavailable (token not provided); using fallback model hints."
            )
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        return executeModelProbe(request) { json ->
            val data = json.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty().trim()
                    if (id.isNotBlank()) add(id)
                }
            }
        }.copy(rateLimitInfo = "OpenAI limits are account-tier dependent.")
    }

    private fun probeClaudeModels(accessToken: String?): ProbeResult {
        if (accessToken.isNullOrBlank()) {
            return ProbeResult(
                models = emptyList(),
                supportsToolUse = true,
                rateLimitInfo = "Anthropic limits unavailable (token not provided); using fallback model hints."
            )
        }
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/models")
            .header("x-api-key", accessToken)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "application/json")
            .get()
            .build()
        return executeModelProbe(request) { json ->
            val data = json.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty().trim()
                    if (id.isNotBlank()) add(id)
                }
            }
        }.copy(rateLimitInfo = "Anthropic limits are account-tier dependent.")
    }

    private fun probeHuggingFaceModels(accessToken: String?): ProbeResult {
        val builder = Request.Builder()
            .url("https://huggingface.co/api/models?search=instruct&limit=20")
            .header("Accept", "application/json")
            .get()
        if (!accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        val request = builder.build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ProbeResult(
                        models = emptyList(),
                        supportsToolUse = true,
                        rateLimitInfo = "Capability probe HTTP ${response.code}; using fallback hints."
                    )
                }
                val body = response.body?.string().orEmpty()
                val payload = JSONArray(body)
                val models = buildList {
                    for (i in 0 until payload.length()) {
                        val id = payload.optJSONObject(i)?.optString("id").orEmpty().trim()
                        if (id.isNotBlank()) add(id)
                    }
                }
                ProbeResult(
                    models = models,
                    supportsToolUse = true,
                    rateLimitInfo = "Hugging Face limits depend on account and endpoint."
                )
            }
        }.getOrElse {
            ProbeResult(
                models = emptyList(),
                supportsToolUse = true,
                rateLimitInfo = "Capability probe unavailable (${it.message ?: it.javaClass.simpleName}); using fallback hints."
            )
        }
    }

    private fun executeModelProbe(
        request: Request,
        parser: (JSONObject) -> List<String>
    ): ProbeResult {
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ProbeResult(
                        models = emptyList(),
                        supportsToolUse = true,
                        rateLimitInfo = "Capability probe HTTP ${response.code}; using fallback hints."
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                ProbeResult(
                    models = parser(json),
                    supportsToolUse = true,
                    rateLimitInfo = "Capability probe succeeded."
                )
            }
        }.getOrElse {
            ProbeResult(
                models = emptyList(),
                supportsToolUse = true,
                rateLimitInfo = "Capability probe unavailable (${it.message ?: it.javaClass.simpleName}); using fallback hints."
            )
        }
    }

    private data class ProbeResult(
        val models: List<String>,
        val supportsToolUse: Boolean,
        val rateLimitInfo: String
    )
}
