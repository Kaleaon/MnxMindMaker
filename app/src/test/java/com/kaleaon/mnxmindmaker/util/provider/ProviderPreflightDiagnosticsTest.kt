package com.kaleaon.mnxmindmaker.util.provider

import com.kaleaon.mnxmindmaker.model.LlmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class ProviderPreflightDiagnosticsTest {

    @Test
    fun classifyError_handles401And403() {
        val cause401 = ProviderPreflightDiagnostics.classifyError(
            statusCode = 401,
            exception = null,
            endpoint = "https://api.openai.com/v1",
            provider = LlmProvider.OPENAI,
            responseBody = "Invalid API Key"
        )
        assertEquals("Authentication Failed (401)", cause401.title)
        assertTrue(cause401.rawTrace.contains("Invalid API Key"))

        val cause403 = ProviderPreflightDiagnostics.classifyError(
            statusCode = 403,
            exception = null,
            endpoint = "https://api.anthropic.com/v1",
            provider = LlmProvider.ANTHROPIC,
            responseBody = "Forbidden"
        )
        assertEquals("Authentication Failed (403)", cause403.title)
    }

    @Test
    fun classifyError_handles404() {
        val cause = ProviderPreflightDiagnostics.classifyError(
            statusCode = 404,
            exception = null,
            endpoint = "https://example.com/v1",
            provider = LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED,
            responseBody = "Not found"
        )
        assertEquals("Endpoint or Model Not Found (404)", cause.title)
        assertTrue(cause.troubleshootingTip.contains("Base URL"))
    }

    @Test
    fun classifyError_handles429() {
        val cause = ProviderPreflightDiagnostics.classifyError(
            statusCode = 429,
            exception = null,
            endpoint = "https://generativelanguage.googleapis.com",
            provider = LlmProvider.GEMINI,
            responseBody = "Quota exceeded"
        )
        assertEquals("Rate Limit Exceeded (429)", cause.title)
        assertTrue(cause.troubleshootingTip.contains("Wait a few moments"))
    }

    @Test
    fun classifyError_handles500Plus() {
        val cause = ProviderPreflightDiagnostics.classifyError(
            statusCode = 503,
            exception = null,
            endpoint = "https://api.anthropic.com",
            provider = LlmProvider.ANTHROPIC,
            responseBody = "Service Unavailable"
        )
        assertEquals("Provider Service Unavailable (503)", cause.title)
    }

    @Test
    fun classifyError_handlesNetworkException() {
        val exception = SocketTimeoutException("Read timed out")
        val cause = ProviderPreflightDiagnostics.classifyError(
            statusCode = null,
            exception = exception,
            endpoint = "http://localhost:8000",
            provider = LlmProvider.VLLM_GEMMA4
        )
        assertEquals("Network Transport Failure", cause.title)
        assertTrue(cause.rawTrace.contains("SocketTimeoutException"))
    }
}
