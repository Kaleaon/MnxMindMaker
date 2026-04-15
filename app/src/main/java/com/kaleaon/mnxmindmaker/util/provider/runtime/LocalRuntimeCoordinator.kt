package com.kaleaon.mnxmindmaker.util.provider.runtime

import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.util.provider.PreflightDiagnosticsResult
import com.kaleaon.mnxmindmaker.util.provider.ProviderHealth
import com.kaleaon.mnxmindmaker.util.provider.ProviderPreflightDiagnostics
import com.kaleaon.mnxmindmaker.util.provider.ProviderRouter
import com.kaleaon.mnxmindmaker.util.provider.ValidationSeverity
import com.kaleaon.mnxmindmaker.util.provider.validate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

sealed class LocalRuntimeState {
    abstract val diagnostic: RuntimeDiagnostic

    data class Initializing(
        override val diagnostic: RuntimeDiagnostic
    ) : LocalRuntimeState()

    data class Healthy(
        override val diagnostic: RuntimeDiagnostic
    ) : LocalRuntimeState()

    data class Degraded(
        override val diagnostic: RuntimeDiagnostic,
        val failureCount: Int,
        val nextRetryDelayMs: Long
    ) : LocalRuntimeState()

    data class Unreachable(
        override val diagnostic: RuntimeDiagnostic,
        val terminal: Boolean = true
    ) : LocalRuntimeState()
}

data class RuntimeDiagnostic(
    val summary: String,
    val detail: String,
    val suggestion: String? = null
) {
    fun toUserMessage(): String {
        val suffix = suggestion?.takeIf { it.isNotBlank() }?.let { "\nSuggestion: $it" } ?: ""
        return "$summary\nDetail: $detail$suffix"
    }
}

data class LocalRuntimeConnectionReport(
    val preflight: PreflightDiagnosticsResult,
    val health: ProviderHealth,
    val state: LocalRuntimeState
)

class LocalRuntimeCoordinator(
    private val providerRouter: ProviderRouter = ProviderRouter(),
    private val scope: CoroutineScope,
    private val healthCheckIntervalMs: Long = 30_000,
    private val maxStartupRetries: Int = 3,
    private val maxHealthFailures: Int = 3,
    private val baseBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 15_000
) {

    private val _state = MutableStateFlow<LocalRuntimeState>(
        LocalRuntimeState.Unreachable(
            RuntimeDiagnostic(
                summary = "Local runtime not initialized",
                detail = "No runtime startup sequence has run yet.",
                suggestion = "Configure Local On-Device provider in Settings and run preflight."
            )
        )
    )
    val state: StateFlow<LocalRuntimeState> = _state.asStateFlow()

    private var monitorJob: Job? = null

    fun beginMonitoring(settings: LlmSettings, privacyMode: PrivacyMode = PrivacyMode.HYBRID) {
        if (settings.provider.runtime != LlmRuntime.LOCAL_ON_DEVICE || !settings.enabled) {
            stopMonitoring(
                RuntimeDiagnostic(
                    summary = "Local runtime disabled",
                    detail = "Local provider is disabled or not selected.",
                    suggestion = "Enable Local On-Device provider in Settings to monitor runtime health."
                )
            )
            return
        }

        monitorJob?.cancel()
        monitorJob = scope.launch {
            val startupState = runStartupWithRetry(settings, privacyMode)
            if (startupState !is LocalRuntimeState.Healthy) {
                _state.value = startupState
                return@launch
            }
            _state.value = startupState

            var failureCount = 0
            while (isActive) {
                delay(healthCheckIntervalMs)
                val health = withContext(Dispatchers.IO) { providerRouter.healthCheck(settings) }
                if (health.ok) {
                    failureCount = 0
                    _state.value = LocalRuntimeState.Healthy(
                        RuntimeDiagnostic(
                            summary = "Local runtime healthy",
                            detail = health.message
                        )
                    )
                } else {
                    failureCount += 1
                    if (failureCount >= maxHealthFailures) {
                        _state.value = LocalRuntimeState.Unreachable(
                            RuntimeDiagnostic(
                                summary = "Local runtime unreachable",
                                detail = health.message,
                                suggestion = "Verify local runtime process, model path, and network endpoint."
                            ),
                            terminal = true
                        )
                        return@launch
                    }
                    val backoff = backoffForAttempt(failureCount)
                    _state.value = LocalRuntimeState.Degraded(
                        diagnostic = RuntimeDiagnostic(
                            summary = "Local runtime degraded",
                            detail = health.message,
                            suggestion = "Coordinator will retry health checks automatically."
                        ),
                        failureCount = failureCount,
                        nextRetryDelayMs = backoff
                    )
                    delay(backoff)
                }
            }
        }
    }

    suspend fun runConnectionTest(settings: LlmSettings, privacyMode: PrivacyMode = PrivacyMode.HYBRID): LocalRuntimeConnectionReport {
        val preflight = withContext(Dispatchers.IO) { ProviderPreflightDiagnostics.run(settings) }
        val startupState = runStartupWithRetry(settings, privacyMode)
        val health = if (startupState is LocalRuntimeState.Healthy) {
            ProviderHealth(true, startupState.diagnostic.detail)
        } else {
            ProviderHealth(false, startupState.diagnostic.detail)
        }
        return LocalRuntimeConnectionReport(preflight = preflight, health = health, state = startupState)
    }

    fun stopMonitoring(diagnostic: RuntimeDiagnostic? = null) {
        monitorJob?.cancel()
        monitorJob = null
        _state.value = LocalRuntimeState.Unreachable(
            diagnostic = diagnostic ?: RuntimeDiagnostic(
                summary = "Local runtime monitoring stopped",
                detail = "Lifecycle monitoring has been stopped.",
                suggestion = "Restart monitoring after updating local runtime settings."
            ),
            terminal = true
        )
    }

    private suspend fun runStartupWithRetry(settings: LlmSettings, privacyMode: PrivacyMode): LocalRuntimeState {
        val issues = validate(settings, privacyMode)
        val criticalIssue = issues.firstOrNull { it.severity == ValidationSeverity.CRITICAL }
        if (criticalIssue != null) {
            return LocalRuntimeState.Unreachable(
                RuntimeDiagnostic(
                    summary = "Preflight validation failed",
                    detail = criticalIssue.message,
                    suggestion = "Fix validation errors in Settings before connecting."
                ),
                terminal = true
            )
        }

        _state.value = LocalRuntimeState.Initializing(
            RuntimeDiagnostic(
                summary = "Initializing local runtime",
                detail = "Running startup health check.",
                suggestion = "Please wait while runtime readiness is verified."
            )
        )

        var attempt = 0
        var lastMessage = "No startup attempt made"
        while (attempt < maxStartupRetries) {
            attempt += 1
            val health = withContext(Dispatchers.IO) { providerRouter.healthCheck(settings) }
            if (health.ok) {
                return LocalRuntimeState.Healthy(
                    RuntimeDiagnostic(
                        summary = "Local runtime ready",
                        detail = health.message
                    )
                )
            }

            lastMessage = "Attempt $attempt/$maxStartupRetries failed: ${health.message}"
            if (attempt < maxStartupRetries) {
                val backoff = backoffForAttempt(attempt)
                _state.value = LocalRuntimeState.Degraded(
                    diagnostic = RuntimeDiagnostic(
                        summary = "Startup retry scheduled",
                        detail = lastMessage,
                        suggestion = "Coordinator will retry automatically."
                    ),
                    failureCount = attempt,
                    nextRetryDelayMs = backoff
                )
                delay(backoff)
            }
        }

        return LocalRuntimeState.Unreachable(
            RuntimeDiagnostic(
                summary = "Local runtime startup failed",
                detail = lastMessage,
                suggestion = "Ensure the local runtime server is running and model path is valid."
            ),
            terminal = true
        )
    }

    private fun backoffForAttempt(attempt: Int): Long {
        val exp = baseBackoffMs * (1L shl (attempt - 1).coerceAtLeast(0))
        return min(exp, maxBackoffMs)
    }
}
