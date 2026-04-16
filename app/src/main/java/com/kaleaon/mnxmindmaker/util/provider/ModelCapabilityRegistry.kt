package com.kaleaon.mnxmindmaker.util.provider

import android.content.Context
import android.content.SharedPreferences
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import org.json.JSONObject
import kotlin.math.max

data class ModelCapabilityProfile(
    val provider: LlmProvider,
    val modelId: String,
    val supportsToolCalls: Boolean,
    val supportsJsonMode: Boolean,
    val supportsVision: Boolean,
    val contextWindowTokens: Int,
    val detectedAtEpochMs: Long,
    val detectionSource: String = "heuristic-runtime"
)

class RuntimeModelCapabilityDetector(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun detect(settings: LlmSettings): ModelCapabilityProfile {
        val model = settings.model.trim()
        val normalized = model.lowercase()

        val defaults = when (settings.provider) {
            LlmProvider.ANTHROPIC -> CapabilityDefaults(
                supportsToolCalls = true,
                supportsJsonMode = true,
                supportsVision = normalized.startsWith("claude-3"),
                contextWindowTokens = 200_000
            )

            LlmProvider.OPENAI -> CapabilityDefaults(
                supportsToolCalls = true,
                supportsJsonMode = true,
                supportsVision = normalized.contains("4o") || normalized.contains("4.1") || normalized.startsWith("o"),
                contextWindowTokens = 128_000
            )

            LlmProvider.GEMINI -> CapabilityDefaults(
                supportsToolCalls = true,
                supportsJsonMode = true,
                supportsVision = true,
                contextWindowTokens = if (normalized.contains("1.5-pro") || normalized.contains("2.0")) 1_000_000 else 128_000
            )

            LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED,
            LlmProvider.VLLM_GEMMA4,
            LlmProvider.LOCAL_ON_DEVICE -> CapabilityDefaults(
                supportsToolCalls = false,
                supportsJsonMode = false,
                supportsVision = false,
                contextWindowTokens = max(2_048, settings.runtimeControls.contextWindowTokens)
            )
        }

        return ModelCapabilityProfile(
            provider = settings.provider,
            modelId = model,
            supportsToolCalls = defaults.supportsToolCalls,
            supportsJsonMode = defaults.supportsJsonMode,
            supportsVision = defaults.supportsVision,
            contextWindowTokens = defaults.contextWindowTokens,
            detectedAtEpochMs = clock()
        )
    }

    private data class CapabilityDefaults(
        val supportsToolCalls: Boolean,
        val supportsJsonMode: Boolean,
        val supportsVision: Boolean,
        val contextWindowTokens: Int
    )
}

interface ModelCapabilitySource {
    fun capabilitiesFor(settings: LlmSettings): ModelCapabilityProfile
}

class ModelCapabilityRegistry(
    private val prefs: SharedPreferences,
    private val detector: RuntimeModelCapabilityDetector = RuntimeModelCapabilityDetector(),
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ModelCapabilitySource {

    override fun capabilitiesFor(settings: LlmSettings): ModelCapabilityProfile {
        val cacheKey = cacheKey(settings.provider, settings.model)
        val cached = readCached(cacheKey)
        val now = clock()
        if (cached != null && (now - cached.detectedAtEpochMs) < refreshIntervalMs) {
            return cached
        }
        val detected = detector.detect(settings)
        persist(cacheKey, detected)
        return detected
    }

    private fun persist(cacheKey: String, profile: ModelCapabilityProfile) {
        val json = JSONObject()
            .put("provider", profile.provider.name)
            .put("modelId", profile.modelId)
            .put("supportsToolCalls", profile.supportsToolCalls)
            .put("supportsJsonMode", profile.supportsJsonMode)
            .put("supportsVision", profile.supportsVision)
            .put("contextWindowTokens", profile.contextWindowTokens)
            .put("detectedAtEpochMs", profile.detectedAtEpochMs)
            .put("detectionSource", profile.detectionSource)
        prefs.edit().putString(cacheKey, json.toString()).apply()
    }

    private fun readCached(cacheKey: String): ModelCapabilityProfile? {
        val raw = prefs.getString(cacheKey, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val provider = LlmProvider.valueOf(json.getString("provider"))
            ModelCapabilityProfile(
                provider = provider,
                modelId = json.optString("modelId"),
                supportsToolCalls = json.optBoolean("supportsToolCalls", false),
                supportsJsonMode = json.optBoolean("supportsJsonMode", false),
                supportsVision = json.optBoolean("supportsVision", false),
                contextWindowTokens = json.optInt("contextWindowTokens", 8_192),
                detectedAtEpochMs = json.optLong("detectedAtEpochMs", 0L),
                detectionSource = json.optString("detectionSource", "heuristic-runtime")
            )
        }.getOrNull()
    }

    private fun cacheKey(provider: LlmProvider, modelId: String): String {
        return "capabilities_${provider.name}_${modelId.trim().lowercase()}"
    }

    companion object {
        private const val PREFS_NAME = "mnx_model_capabilities"
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

        fun create(context: Context): ModelCapabilityRegistry {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ModelCapabilityRegistry(prefs = prefs)
        }
    }
}
