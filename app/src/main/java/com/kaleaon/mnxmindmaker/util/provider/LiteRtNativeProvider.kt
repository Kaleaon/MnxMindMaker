package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalRuntimeEngine
import com.kaleaon.mnxmindmaker.util.LlmApiException
import com.kaleaon.mnxmindmaker.util.tooling.AssistantTurn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional in-process LiteRT-LM provider path.
 *
 * This adapter is intentionally reflection-based so the app can build even when the optional
 * native bridge module is not included in the workspace. Integrators can provide:
 * `com.kaleaon.mnxmindmaker.litert.NativeLiteRtBridge` with:
 *
 * `fun generate(localModelPath: String, systemPrompt: String, transcript: String): String`
 */
class LiteRtNativeProvider : AssistantProvider {

    override val id: String = "litert_native"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(reportsTokenUsage = true)

    override fun supports(settings: LlmSettings): Boolean {
        return settings.provider == LlmProvider.LOCAL_ON_DEVICE &&
            settings.runtimeControls.engine == LocalRuntimeEngine.LITERT_LM &&
            settings.baseUrl.trim().equals(IN_PROCESS_SCHEME, ignoreCase = true)
    }

    override fun chat(request: ProviderRequest): AssistantTurn {
        val bridgeClass = runCatching { Class.forName(BRIDGE_CLASS) }.getOrNull()
            ?: throw LlmApiException(
                "LiteRT native bridge unavailable. " +
                    "Provide $BRIDGE_CLASS or switch base URL away from $IN_PROCESS_SCHEME to use bridge mode."
            )
        return runCatching {
            val method = bridgeClass.getMethod(
                "generate",
                String::class.java,
                String::class.java,
                String::class.java
            )
            val transcriptJson = JSONArray(request.transcript.map { JSONObject(it.toString()) }).toString()
            val text = method.invoke(
                null,
                request.settings.localModelPath,
                request.systemPrompt,
                transcriptJson
            ) as? String ?: throw LlmApiException("LiteRT native bridge returned no text output")
            AssistantTurn(text = text.trim())
        }.getOrElse { throwable ->
            throw if (throwable is LlmApiException) throwable else LlmApiException("LiteRT native execution failed", throwable)
        }
    }

    override fun healthCheck(settings: LlmSettings): ProviderHealth {
        if (!supports(settings)) return ProviderHealth(false, "Unsupported settings for LiteRtNativeProvider")
        val bridgeClass = runCatching { Class.forName(BRIDGE_CLASS) }.getOrNull()
            ?: return ProviderHealth(
                false,
                "LiteRT native bridge class missing; configure bridge mode or include optional native module."
            )
        val method = runCatching {
            bridgeClass.getMethod("generate", String::class.java, String::class.java, String::class.java)
        }.getOrNull()
        return if (method == null) {
            ProviderHealth(false, "LiteRT native bridge found, but generate(...) signature is incompatible.")
        } else {
            ProviderHealth(true, "LiteRT native bridge available.")
        }
    }

    companion object {
        private const val IN_PROCESS_SCHEME = "inprocess://litert-lm"
        private const val BRIDGE_CLASS = "com.kaleaon.mnxmindmaker.litert.NativeLiteRtBridge"
    }
}
