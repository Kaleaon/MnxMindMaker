package com.kaleaon.mnxmindmaker.ui.mindmap

import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class MindMapProviderSelectionTest {

    @Test
    fun `retry order follows governed chain when gemini and vllm are only alternates`() {
        val ordered = MindMapProviderSelection.orderRetryCandidates(
            current = LlmProvider.OPENAI,
            governedChain = listOf(settings(LlmProvider.OPENAI), settings(LlmProvider.GEMINI), settings(LlmProvider.VLLM_GEMMA4)),
            allUsable = listOf(settings(LlmProvider.OPENAI), settings(LlmProvider.GEMINI), settings(LlmProvider.VLLM_GEMMA4))
        )

        assertEquals(listOf(LlmProvider.GEMINI, LlmProvider.VLLM_GEMMA4), ordered.map { it.provider })
    }

    @Test
    fun `retry order falls back to usable settings order when governed chain has no alternates`() {
        val ordered = MindMapProviderSelection.orderRetryCandidates(
            current = LlmProvider.OPENAI,
            governedChain = listOf(settings(LlmProvider.OPENAI)),
            allUsable = listOf(settings(LlmProvider.OPENAI), settings(LlmProvider.VLLM_GEMMA4), settings(LlmProvider.GEMINI))
        )

        assertEquals(listOf(LlmProvider.VLLM_GEMMA4, LlmProvider.GEMINI), ordered.map { it.provider })
    }

    private fun settings(provider: LlmProvider): LlmSettings = LlmSettings(
        provider = provider,
        enabled = true,
        apiKey = "test-key",
        localModelPath = "/data/local/model.gguf"
    )
}
