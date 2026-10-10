package com.kaleaon.mnxmindmaker.ui.settings

import android.app.Application
import android.content.res.AssetManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.ModelInstallState
import com.kaleaon.mnxmindmaker.model.ModelManager
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.repository.AuthRepository
import com.kaleaon.mnxmindmaker.repository.ExternalAccountRepository
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.repository.RefreshStatus
import com.kaleaon.mnxmindmaker.util.provider.PreflightDiagnosticsResult
import com.kaleaon.mnxmindmaker.util.provider.ProviderPreflightDiagnostics
import com.kaleaon.mnxmindmaker.util.provider.ProviderSettingsValidator
import com.kaleaon.mnxmindmaker.util.provider.ValidationIssue
import com.kaleaon.mnxmindmaker.util.provider.ValidationSeverity
import com.kaleaon.mnxmindmaker.util.provider.runtime.LocalRuntimeConnectionReport
import com.kaleaon.mnxmindmaker.util.provider.runtime.LocalRuntimeCoordinator
import com.kaleaon.mnxmindmaker.util.provider.runtime.LocalRuntimeState
import com.kaleaon.mnxmindmaker.util.provider.validate
import com.kaleaon.mnxmindmaker.util.tooling.SkillManifestValidator
import com.kaleaon.mnxmindmaker.util.tooling.SkillPackDiagnosticsStore
import com.kaleaon.mnxmindmaker.util.tooling.SkillPackLoader
import com.kaleaon.mnxmindmaker.util.tooling.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val repository = LlmSettingsRepository(application)
    val authRepository = AuthRepository(application)
    val externalAccountRepository = ExternalAccountRepository(application)
    val modelManager = ModelManager(application)
    val localRuntimeCoordinator = LocalRuntimeCoordinator(scope = viewModelScope)

    var currentProvider: LlmProvider = LlmProvider.ANTHROPIC
    var privacyMode: PrivacyMode = repository.loadPrivacyMode()

    private val draftSettingsMap = mutableMapOf<LlmProvider, LlmSettings>()

    private val _preflightResultText = MutableLiveData<String>()
    val preflightResultText: LiveData<String> = _preflightResultText

    private val _skillPackDiagnosticsText = MutableLiveData<String>()
    val skillPackDiagnosticsText: LiveData<String> = _skillPackDiagnosticsText

    private val _modelManagerSummaryText = MutableLiveData<String>()
    val modelManagerSummaryText: LiveData<String> = _modelManagerSummaryText

    private val _localAuthStatusText = MutableLiveData<String>()
    val localAuthStatusText: LiveData<String> = _localAuthStatusText

    private val _linkedAccountsStatusText = MutableLiveData<String>()
    val linkedAccountsStatusText: LiveData<String> = _linkedAccountsStatusText

    private val _activeThemeText = MutableLiveData<String>()
    val activeThemeText: LiveData<String> = _activeThemeText

    private val _snackbarMessage = MutableLiveData<String>()
    val snackbarMessage: LiveData<String> = _snackbarMessage

    private val _modelInstallSuccessEvent = MutableLiveData<LlmSettings?>()
    val modelInstallSuccessEvent: LiveData<LlmSettings?> = _modelInstallSuccessEvent

    init {
        repository.loadAllSettings().forEach { settings ->
            draftSettingsMap[settings.provider] = settings
        }
        observeLocalRuntimeState()
        updateLocalAuthStatus()
        updateLinkedAccountsStatus()
        updateActiveThemeText()
    }

    private fun getString(resId: Int, vararg formatArgs: Any): String {
        return runCatching {
            if (formatArgs.isNotEmpty()) {
                getApplication<Application>().getString(resId, *formatArgs)
            } else {
                getApplication<Application>().getString(resId)
            }
        }.getOrDefault("Resource $resId")
    }

    fun getDraftSettings(provider: LlmProvider): LlmSettings {
        return draftSettingsMap[provider] ?: repository.loadAllSettings().firstOrNull { it.provider == provider } ?: LlmSettings(provider)
    }

    fun updateDraftSettings(settings: LlmSettings) {
        draftSettingsMap[settings.provider] = settings
    }

    fun saveProviderSettings(settings: LlmSettings, currentPrivacyMode: PrivacyMode): Boolean {
        updateDraftSettings(settings)
        privacyMode = currentPrivacyMode

        val issues = validate(settings, privacyMode)
        if (issues.any { it.severity == ValidationSeverity.CRITICAL }) {
            _snackbarMessage.value = getString(R.string.settings_validation_failed)
            return false
        }

        repository.savePrivacyMode(privacyMode)
        if (settings.provider.requiresApiKey && settings.apiKey.isBlank()) {
            _snackbarMessage.value = getString(
                R.string.api_key_required_for_provider,
                settings.provider.displayName
            )
            return false
        }

        ProviderSettingsValidator.validate(settings)?.let { message ->
            _snackbarMessage.value = message
            return false
        }

        repository.saveSettings(settings)
        draftSettingsMap[settings.provider] = settings

        _snackbarMessage.value = getString(
            R.string.settings_saved,
            settings.provider.displayName
        )
        return true
    }

    fun runPreflightDiagnostics(draft: LlmSettings, currentPrivacyMode: PrivacyMode) {
        updateDraftSettings(draft)
        privacyMode = currentPrivacyMode

        val issues = validate(draft, privacyMode)
        if (issues.any { it.severity == ValidationSeverity.CRITICAL }) {
            _preflightResultText.value = getString(R.string.preflight_blocked_validation)
            return
        }

        _preflightResultText.value = getString(R.string.preflight_running)
        viewModelScope.launch {
            if (draft.provider.runtime == LlmRuntime.LOCAL_ON_DEVICE) {
                val report = localRuntimeCoordinator.runConnectionTest(draft, privacyMode)
                if (report.state is LocalRuntimeState.Healthy) {
                    localRuntimeCoordinator.beginMonitoring(draft, privacyMode)
                }
                _preflightResultText.value = renderLocalRuntimeConnectionReport(report, issues)
            } else {
                val result = withContext(Dispatchers.IO) {
                    ProviderPreflightDiagnostics.run(draft)
                }
                _preflightResultText.value = renderPreflightResult(result, issues)
            }
        }
    }

    private fun observeLocalRuntimeState() {
        viewModelScope.launch {
            localRuntimeCoordinator.state.collectLatest { state ->
                if (currentProvider.runtime != LlmRuntime.LOCAL_ON_DEVICE) return@collectLatest
                val header = when (state) {
                    is LocalRuntimeState.Initializing -> "Runtime state: Initializing"
                    is LocalRuntimeState.Healthy -> "Runtime state: Healthy"
                    is LocalRuntimeState.Degraded -> "Runtime state: Degraded"
                    is LocalRuntimeState.Unreachable -> "Runtime state: Unreachable"
                }
                _preflightResultText.value = "$header\n${state.diagnostic.toUserMessage()}"
            }
        }
    }

    fun discoverModels() {
        viewModelScope.launch {
            val localCatalogModels = modelManager.discoverModels()
            val huggingFaceToken = externalAccountRepository.getAccessToken(ExternalProvider.HUGGING_FACE)
            val huggingFaceModels = withContext(Dispatchers.IO) {
                modelManager.discoverHuggingFaceModels(
                    accessToken = huggingFaceToken,
                    query = "tool calling gguf"
                )
            }
            val installedCount = localCatalogModels.count { it.state == ModelInstallState.INSTALLED }
            val summary = getString(
                R.string.model_discovery_summary,
                localCatalogModels.size + huggingFaceModels.size,
                installedCount
            )
            val hfSummary = if (huggingFaceToken.isNullOrBlank()) {
                "\nHugging Face: not linked (showing public ToolNeuron-aligned models only)."
            } else {
                "\nHugging Face: linked (${huggingFaceModels.size} models discovered)."
            }
            _modelManagerSummaryText.value = summary + hfSummary
        }
    }

    fun installRecommendedModel() {
        val result = modelManager.installModelOneClick("gemma3n_e2b_litertlm")
        val (installedPath, installedId, quantProfile) = if (result.isSuccess) {
            val model = result.getOrThrow()
            modelManager.pinVersion(model.id, model.version, true)
            _modelManagerSummaryText.value = getString(
                R.string.model_install_success,
                model.displayName,
                model.version
            )
            Triple(model.localPath, model.id, model.quantizationProfile)
        } else {
            _modelManagerSummaryText.value = getString(
                R.string.model_install_error,
                result.exceptionOrNull()?.message ?: "unknown error"
            )
            Triple("/data/local/tmp/gemma3n_e2b_litertlm.bin", "google/gemma-3n-E2B-it-litert-lm", "INT4")
        }

        val currentLocalDraft = getDraftSettings(LlmProvider.LOCAL_ON_DEVICE)
        val updated = currentLocalDraft.copy(
            localModelPath = installedPath,
            model = installedId,
            runtimeControls = currentLocalDraft.runtimeControls.copy(
                quantizationProfile = quantProfile
            )
        )
        updateDraftSettings(updated)
        _modelInstallSuccessEvent.value = updated
    }

    fun loadSkillPackDiagnostics(assets: AssetManager) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val existing = SkillPackDiagnosticsStore.latestReport
                if (existing.loadedPacks.isNotEmpty() || existing.disabledPacks.isNotEmpty() || existing.skippedPacks.isNotEmpty() || existing.validationIssues.isNotEmpty()) {
                    existing
                } else {
                    val loader = SkillPackLoader(
                        assets = assets,
                        validator = SkillManifestValidator(ToolRegistry.approvedHandlerIds())
                    )
                    loader.load().also { SkillPackDiagnosticsStore.update(it) }
                }
            }

            val loaded = report.loadedPacks.joinToString(", ") { "${it.manifest.packId}@${it.manifest.version}" }.ifBlank { "none" }
            val disabled = report.disabledPacks.joinToString(", ").ifBlank { "none" }
            val skipped = report.skippedPacks.joinToString(", ").ifBlank { "none" }
            val errors = report.validationIssues.take(8).joinToString("\n") { "- ${it.source}: ${it.message}" }
                .ifBlank { "- none" }
            _skillPackDiagnosticsText.value = """
                Skill packs loaded: $loaded
                Disabled packs: $disabled
                Skipped packs: $skipped
                Validation errors:
                $errors
            """.trimIndent()
        }
    }

    fun saveLocalAuth(email: String, password: String, passkeyEnabled: Boolean) {
        if (email.isBlank() || password.isBlank()) {
            _snackbarMessage.value = getString(R.string.local_auth_missing_fields)
            return
        }
        authRepository.saveLocalCredentials(email, password, passkeyEnabled)
        updateLocalAuthStatus()
        _snackbarMessage.value = getString(R.string.local_auth_saved)
    }

    fun createSession(email: String, password: String) {
        val session = when {
            password.isNotBlank() -> authRepository.signInWithPassword(email, password)
            else -> authRepository.signInWithPasskey()
        }
        if (session == null) {
            _snackbarMessage.value = getString(R.string.local_auth_signin_failed)
        } else {
            updateLocalAuthStatus()
            _snackbarMessage.value = getString(R.string.local_auth_session_created)
        }
    }

    fun revokeSession() {
        authRepository.revokeSession()
        updateLocalAuthStatus()
        _snackbarMessage.value = getString(R.string.local_auth_session_revoked)
    }

    fun linkAccount(provider: ExternalProvider, access: String, refresh: String, expires: Long?, clientId: String, clientSecret: String) {
        if (access.isBlank()) {
            _snackbarMessage.value = getString(R.string.link_access_required)
            return
        }
        if (clientId.isNotBlank() xor clientSecret.isNotBlank()) {
            _snackbarMessage.value = getString(R.string.link_oauth_client_pair_required)
            return
        }
        if (refresh.isNotBlank() && clientId.isBlank() && clientSecret.isBlank() && !externalAccountRepository.hasOAuthClientConfig(provider)) {
            _snackbarMessage.value = getString(R.string.link_oauth_client_required_for_refresh, provider.displayName)
            return
        }

        if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
            externalAccountRepository.saveOAuthClientConfig(provider, clientId, clientSecret)
        }
        externalAccountRepository.linkAccount(provider, access, refresh, expires)
        updateLinkedAccountsStatus()
        _snackbarMessage.value = getString(R.string.link_success, provider.displayName)
    }

    fun refreshLinkedAccount(provider: ExternalProvider) {
        val status = externalAccountRepository.refreshAccessTokenDetailed(provider)
        updateLinkedAccountsStatus()
        val message = when (status) {
            RefreshStatus.SUCCESS -> getString(R.string.link_refresh_success, provider.displayName)
            RefreshStatus.MISSING_CLIENT_CONFIG -> getString(R.string.link_refresh_missing_client_config, provider.displayName)
            RefreshStatus.MISSING_REFRESH_TOKEN -> getString(R.string.link_refresh_missing_refresh_token, provider.displayName)
            RefreshStatus.PROVIDER_REJECTED -> getString(R.string.link_refresh_provider_rejected, provider.displayName)
            RefreshStatus.NETWORK_ERROR -> getString(R.string.link_refresh_network_error, provider.displayName)
            RefreshStatus.INVALID_RESPONSE -> getString(R.string.link_refresh_invalid_response, provider.displayName)
        }
        _snackbarMessage.value = message
    }

    fun revokeLinkedAccount(provider: ExternalProvider) {
        externalAccountRepository.revoke(provider)
        updateLinkedAccountsStatus()
        _snackbarMessage.value = getString(R.string.link_revoke_success, provider.displayName)
    }

    fun updateLocalAuthStatus() {
        val session = authRepository.getSession()
        val message = if (session == null) {
            getString(R.string.local_auth_status_no_session)
        } else {
            val expiry = DateFormat.getDateTimeInstance().format(Date(session.expiresAtEpochMs))
            getString(R.string.local_auth_status_active, session.email, expiry)
        }
        _localAuthStatusText.value = message
    }

    fun updateLinkedAccountsStatus() {
        val claudeRefreshEnabled = externalAccountRepository.canRefreshLinkedAccount(ExternalProvider.CLAUDE)
        val chatGptRefreshEnabled = externalAccountRepository.canRefreshLinkedAccount(ExternalProvider.CHATGPT)
        val huggingFaceRefreshEnabled = externalAccountRepository.canRefreshLinkedAccount(ExternalProvider.HUGGING_FACE)

        val lines = externalAccountRepository.allLinkStates().map { link ->
            if (!link.linked) {
                getString(R.string.link_status_not_linked, link.provider.displayName)
            } else {
                val expiryText = link.expiresAtEpochMs?.let {
                    DateFormat.getDateTimeInstance().format(Date(it))
                } ?: getString(R.string.link_expiry_unknown)
                val caps = link.capabilities
                val modelCount = caps?.models?.size ?: 0
                val tools = if (caps?.supportsToolUse == true) {
                    getString(R.string.capability_supported)
                } else {
                    getString(R.string.capability_limited)
                }
                getString(
                    R.string.link_status_linked,
                    link.provider.displayName,
                    expiryText,
                    modelCount,
                    tools,
                    caps?.rateLimitInfo ?: getString(R.string.link_expiry_unknown)
                )
            }
        }
        val refreshHints = listOf(
            if (!claudeRefreshEnabled) getString(R.string.link_refresh_disabled_reason, ExternalProvider.CLAUDE.displayName) else null,
            if (!chatGptRefreshEnabled) getString(R.string.link_refresh_disabled_reason, ExternalProvider.CHATGPT.displayName) else null,
            if (!huggingFaceRefreshEnabled) getString(R.string.link_refresh_disabled_reason, ExternalProvider.HUGGING_FACE.displayName) else null
        ).filterNotNull()

        _linkedAccountsStatusText.value = lines.joinToString(separator = "\n") +
            if (refreshHints.isNotEmpty()) "\n" + refreshHints.joinToString(separator = "\n") else ""
    }

    fun updateActiveThemeText() {
        val activeTheme = KthemeManager.getActiveThemeSync()
        _activeThemeText.value = if (activeTheme != null) {
            getString(R.string.theme_active_label, activeTheme.metadata.name)
        } else {
            getString(R.string.theme_default)
        }
    }

    fun setActiveTheme(themeId: String, themeName: String) {
        KthemeManager.setActiveTheme(themeId)
        updateActiveThemeText()
        _snackbarMessage.value = getString(R.string.theme_applied, themeName)
    }

    fun resetTheme() {
        val prefs = getApplication<Application>().getSharedPreferences("ktheme_prefs", 0)
        prefs.edit().remove("active_theme_id").apply()
        updateActiveThemeText()
        _snackbarMessage.value = getString(R.string.theme_reset_done)
    }

    private fun renderPreflightResult(result: PreflightDiagnosticsResult, issues: List<ValidationIssue>): String {
        val validationSummary = if (issues.isEmpty()) {
            "Validation: OK"
        } else {
            val critical = issues.count { it.severity == ValidationSeverity.CRITICAL }
            val warning = issues.count { it.severity == ValidationSeverity.WARNING }
            "Validation: $critical critical, $warning warning"
        }
        val reachability = if (result.reachable) "Reachable" else "Unreachable"
        return "$validationSummary\n" +
            "Provider: ${result.provider.displayName}\n" +
            "Probe: ${result.endpoint.trimEnd('/')}${result.probePath}\n" +
            "Status: $reachability (${result.statusCode ?: "n/a"})\n" +
            "Latency: ${result.latencyMs}ms\n" +
            "Detail: ${result.detail}"
    }

    private fun renderLocalRuntimeConnectionReport(
        report: LocalRuntimeConnectionReport,
        issues: List<ValidationIssue>
    ): String {
        val base = renderPreflightResult(report.preflight, issues)
        val phase = when (report.state) {
            is LocalRuntimeState.Initializing -> "Initializing"
            is LocalRuntimeState.Healthy -> "Healthy"
            is LocalRuntimeState.Degraded -> "Degraded"
            is LocalRuntimeState.Unreachable -> "Unreachable"
        }
        return "$base\nLifecycle phase: $phase\n${report.state.diagnostic.toUserMessage()}"
    }
}
