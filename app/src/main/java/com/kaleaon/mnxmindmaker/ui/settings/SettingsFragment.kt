package com.kaleaon.mnxmindmaker.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsBinding
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.model.ComputeBackend
import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalModelProfile
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.model.ModelManager
import com.kaleaon.mnxmindmaker.model.ModelInstallState
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.model.RetrievalModePreference
import com.kaleaon.mnxmindmaker.model.defaultModel
import com.kaleaon.mnxmindmaker.repository.AuthRepository
import com.kaleaon.mnxmindmaker.repository.ExternalAccountRepository
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository
import com.kaleaon.mnxmindmaker.util.provider.ProviderSettingsValidator
import java.text.DateFormat
import java.util.Date

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: LlmSettingsRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var externalAccountRepository: ExternalAccountRepository
    private lateinit var modelManager: ModelManager
    private var currentProvider: LlmProvider = LlmProvider.ANTHROPIC
    private var currentSettings: MutableList<LlmSettings> = mutableListOf()

    private var themeAdapter: ThemeAdapter? = null
    private val tlsPinPattern = Regex("^[A-Za-z0-9+/]{43}=$")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LlmSettingsRepository(requireContext())
        authRepository = AuthRepository(requireContext())
        externalAccountRepository = ExternalAccountRepository(requireContext())
        modelManager = ModelManager(requireContext())
        currentSettings = repository.loadAllSettings().toMutableList()

        binding.btnOpenDeploy.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_deployFragment)
        }

        // ---- Ktheme picker --------------------------------------------------
        setupThemePicker()

        // ---- LLM provider picker --------------------------------------------
        val providers = LlmProvider.entries.map { it.displayName }
        binding.spinnerProvider.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            providers
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerPrivacyMode.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.privacy_mode_strict),
                getString(R.string.privacy_mode_hybrid)
            )
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerClassification.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            DataClassification.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val privacyMode = repository.loadPrivacyMode()
        binding.spinnerPrivacyMode.setSelection(if (privacyMode == PrivacyMode.STRICT_LOCAL_ONLY) 0 else 1)

        binding.spinnerLocalProfile.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            LocalModelProfile.entries.map { it.displayName }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerFallbackOrder.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.fallback_remote_only),
                getString(R.string.fallback_local_first)
            )
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerComputeBackend.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            ComputeBackend.entries.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerRetrievalModePreference.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.retrieval_mode_raw_verbatim),
                getString(R.string.retrieval_mode_summary),
                getString(R.string.retrieval_mode_hierarchical)
            )
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentProvider = LlmProvider.entries[pos]
                loadProviderSettings(currentProvider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadProviderSettings(currentProvider)

        setupLocalAuthSection()
        setupAccountLinkingSection()

        binding.btnSaveSettings.setOnClickListener { saveCurrentProvider() }
        binding.btnDiscoverModels.setOnClickListener {
            val models = modelManager.discoverModels()
            binding.tvModelManagerSummary.text = getString(
                R.string.model_discovery_summary,
                models.size,
                models.count { it.state == ModelInstallState.INSTALLED }
            )
        }
        binding.btnInstallRecommended.setOnClickListener {
            val result = modelManager.installModelOneClick("qwen2_5_7b")
            if (result.isSuccess) {
                val model = result.getOrThrow()
                modelManager.pinVersion(model.id, model.version, true)
                binding.tvModelManagerSummary.text = getString(
                    R.string.model_install_success,
                    model.displayName,
                    model.version
                )
                binding.etLocalModelPath.setText(model.localPath)
                binding.etModel.setText(model.id)
                binding.etQuantizationProfile.setText(model.quantizationProfile)
            } else {
                binding.tvModelManagerSummary.text = getString(
                    R.string.model_install_error,
                    result.exceptionOrNull()?.message ?: "unknown error"
                )
            }
        }

        // Show documentation links
        binding.tvAnthropicInfo.text = getString(R.string.anthropic_api_info)
        binding.tvOpenAiInfo.text = getString(R.string.openai_api_info)
        binding.tvGeminiInfo.text = getString(R.string.gemini_api_info)
        binding.tvVllmInfo.text = getString(R.string.vllm_gemma4_info)
    }

    private fun setupLocalAuthSection() {
        binding.etLocalAuthEmail.setText(authRepository.getStoredEmail())
        binding.switchPasskeyEnabled.isChecked = authRepository.getPasskeyStatus()
        updateLocalAuthStatus()

        binding.btnSaveLocalAuth.setOnClickListener {
            val email = binding.etLocalAuthEmail.text.toString().trim()
            val password = binding.etLocalAuthPassword.text.toString()
            if (email.isBlank() || password.isBlank()) {
                Snackbar.make(binding.root, getString(R.string.local_auth_missing_fields), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            authRepository.saveLocalCredentials(email, password, binding.switchPasskeyEnabled.isChecked)
            binding.etLocalAuthPassword.setText("")
            updateLocalAuthStatus()
            Snackbar.make(binding.root, getString(R.string.local_auth_saved), Snackbar.LENGTH_SHORT).show()
        }

        binding.btnCreateSession.setOnClickListener {
            val email = binding.etLocalAuthEmail.text.toString().trim()
            val password = binding.etLocalAuthPassword.text.toString()
            val session = when {
                password.isNotBlank() -> authRepository.signInWithPassword(email, password)
                else -> authRepository.signInWithPasskey()
            }
            if (session == null) {
                Snackbar.make(binding.root, getString(R.string.local_auth_signin_failed), Snackbar.LENGTH_SHORT).show()
            } else {
                binding.etLocalAuthPassword.setText("")
                updateLocalAuthStatus()
                Snackbar.make(binding.root, getString(R.string.local_auth_session_created), Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnRevokeSession.setOnClickListener {
            authRepository.revokeSession()
            updateLocalAuthStatus()
            Snackbar.make(binding.root, getString(R.string.local_auth_session_revoked), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupAccountLinkingSection() {
        binding.btnLinkClaude.setOnClickListener { promptLinkAccount(ExternalProvider.CLAUDE) }
        binding.btnLinkChatgpt.setOnClickListener { promptLinkAccount(ExternalProvider.CHATGPT) }
        binding.btnRefreshClaude.setOnClickListener { refreshLinkedAccount(ExternalProvider.CLAUDE) }
        binding.btnRefreshChatgpt.setOnClickListener { refreshLinkedAccount(ExternalProvider.CHATGPT) }
        binding.btnRevokeClaude.setOnClickListener { revokeLinkedAccount(ExternalProvider.CLAUDE) }
        binding.btnRevokeChatgpt.setOnClickListener { revokeLinkedAccount(ExternalProvider.CHATGPT) }
        updateLinkedAccountsStatus()
    }

    private fun promptLinkAccount(provider: ExternalProvider) {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val accessInput = EditText(context).apply {
            hint = getString(R.string.link_access_token_hint)
        }
        val refreshInput = EditText(context).apply {
            hint = getString(R.string.link_refresh_token_hint)
        }
        val expiresInput = EditText(context).apply {
            hint = getString(R.string.link_expiry_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(accessInput)
        layout.addView(refreshInput)
        layout.addView(expiresInput)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.link_provider_title, provider.displayName))
            .setView(layout)
            .setPositiveButton(R.string.link_account_action) { _, _ ->
                val access = accessInput.text.toString().trim()
                val refresh = refreshInput.text.toString().trim()
                val expires = expiresInput.text.toString().trim().toLongOrNull()
                if (access.isBlank()) {
                    Snackbar.make(binding.root, getString(R.string.link_access_required), Snackbar.LENGTH_SHORT).show()
                } else {
                    externalAccountRepository.linkAccount(provider, access, refresh, expires)
                    updateLinkedAccountsStatus()
                    Snackbar.make(binding.root, getString(R.string.link_success, provider.displayName), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshLinkedAccount(provider: ExternalProvider) {
        val refreshed = externalAccountRepository.refreshAccessToken(provider)
        updateLinkedAccountsStatus()
        val message = if (refreshed) {
            getString(R.string.link_refresh_success, provider.displayName)
        } else {
            getString(R.string.link_refresh_failed, provider.displayName)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun revokeLinkedAccount(provider: ExternalProvider) {
        externalAccountRepository.revoke(provider)
        updateLinkedAccountsStatus()
        Snackbar.make(binding.root, getString(R.string.link_revoke_success, provider.displayName), Snackbar.LENGTH_SHORT).show()
    }

    private fun updateLocalAuthStatus() {
        val session = authRepository.getSession()
        val message = if (session == null) {
            getString(R.string.local_auth_status_no_session)
        } else {
            val expiry = DateFormat.getDateTimeInstance().format(Date(session.expiresAtEpochMs))
            getString(R.string.local_auth_status_active, session.email, expiry)
        }
        binding.tvLocalAuthStatus.text = message
    }

    private fun updateLinkedAccountsStatus() {
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
        binding.tvLinkedAccountsStatus.text = lines.joinToString(separator = "\n")
    }

    // -- Ktheme theme picker --------------------------------------------------

    private fun setupThemePicker() {
        val allThemes = KthemeManager.getAllThemes().sortedBy { it.metadata.name }
        val activeId = KthemeManager.getActiveThemeId()

        themeAdapter = ThemeAdapter(allThemes, activeId) { theme ->
            KthemeManager.setActiveTheme(theme.metadata.id)
            themeAdapter?.setSelectedId(theme.metadata.id)
            binding.tvActiveTheme.text = getString(R.string.theme_active_label, theme.metadata.name)
            Snackbar.make(
                binding.root,
                getString(R.string.theme_applied, theme.metadata.name),
                Snackbar.LENGTH_SHORT
            ).show()
        }

        binding.rvThemes.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvThemes.adapter = themeAdapter

        // Show current active theme name
        val activeTheme = KthemeManager.getActiveThemeSync()
        binding.tvActiveTheme.text = if (activeTheme != null)
            getString(R.string.theme_active_label, activeTheme.metadata.name)
        else
            getString(R.string.theme_default)

        // Scroll to the active theme card
        if (activeId != null) {
            val idx = allThemes.indexOfFirst { it.metadata.id == activeId }
            if (idx >= 0) binding.rvThemes.scrollToPosition(idx)
        }

        binding.btnResetTheme.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("ktheme_prefs", 0)
            prefs.edit().remove("active_theme_id").apply()
            themeAdapter?.setSelectedId(null)
            binding.tvActiveTheme.text = getString(R.string.theme_default)
            Snackbar.make(binding.root, getString(R.string.theme_reset_done), Snackbar.LENGTH_SHORT).show()
        }
    }

    // -- LLM settings ---------------------------------------------------------

    private fun loadProviderSettings(provider: LlmProvider) {
        val settings = currentSettings.firstOrNull { it.provider == provider }
            ?: LlmSettings(provider)
        binding.etApiKey.setText(settings.apiKey)
        binding.etModel.setText(settings.model)
        binding.etBaseUrl.setText(settings.baseUrl)
        binding.etMaxTokens.setText(settings.maxTokens.toString())
        binding.etTemperature.setText(settings.temperature.toString())
        binding.etLocalModelPath.setText(settings.localModelPath)
        binding.etContextWindow.setText(settings.runtimeControls.contextWindowTokens.toString())
        binding.etQuantizationProfile.setText(settings.runtimeControls.quantizationProfile)
        binding.etMaxRamMb.setText(settings.runtimeControls.maxRamMb.toString())
        binding.etMaxVramMb.setText(settings.runtimeControls.maxVramMb.toString())
        binding.switchEnableWakeUpContext.isChecked = settings.enableWakeUpContext
        binding.etWakeUpTokenBudget.setText(settings.wakeUpTokenBudget.toString())
        binding.switchEnabled.isChecked = settings.enabled
        binding.etTlsPin.setText(settings.tlsPinnedSpkiSha256)
        binding.spinnerClassification.setSelection(DataClassification.entries.indexOf(settings.outboundClassification).coerceAtLeast(0))
        binding.spinnerRetrievalModePreference.setSelection(
            when (settings.retrievalModePreference) {
                RetrievalModePreference.RAW_VERBATIM -> 0
                RetrievalModePreference.SUMMARY -> 1
                RetrievalModePreference.HIERARCHICAL -> 2
            }
        )

        binding.spinnerLocalProfile.setSelection(
            LocalModelProfile.entries.indexOf(settings.localProfile).coerceAtLeast(0)
        )
        binding.spinnerFallbackOrder.setSelection(
            if (settings.fallbackOrder == LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK) 1 else 0
        )
        binding.spinnerComputeBackend.setSelection(
            ComputeBackend.entries.indexOf(settings.runtimeControls.computeBackend).coerceAtLeast(0)
        )

        val isLocalRuntime = provider.runtime == LlmRuntime.LOCAL_ON_DEVICE
        binding.groupLocalRuntime.visibility = if (isLocalRuntime) View.VISIBLE else View.GONE
        binding.groupApiKey.visibility = if (provider == LlmProvider.LOCAL_ON_DEVICE) View.GONE else View.VISIBLE

        binding.tvApiKeyHint.text = when (provider) {
            LlmProvider.ANTHROPIC -> getString(R.string.hint_anthropic_key)
            LlmProvider.OPENAI -> getString(R.string.hint_openai_key)
            LlmProvider.OPENAI_COMPATIBLE_SELF_HOSTED -> getString(R.string.hint_openai_compatible_self_hosted_key)
            LlmProvider.GEMINI -> getString(R.string.hint_gemini_key)
            LlmProvider.VLLM_GEMMA4 -> getString(R.string.hint_vllm_key)
            LlmProvider.LOCAL_ON_DEVICE -> getString(R.string.hint_local_model_path)
        }

        val caps = settings.capabilities
        binding.tvCapabilitySummary.text = getString(
            R.string.capability_summary,
            caps.contextWindowTokens,
            if (caps.supportsToolPlanning) getString(R.string.capability_supported) else getString(R.string.capability_limited),
            if (caps.supportsPacketGeneration) getString(R.string.capability_supported) else getString(R.string.capability_limited)
        )
    }

    private fun saveCurrentProvider() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val model = binding.etModel.text.toString().trim().ifEmpty { currentProvider.defaultModel() }
        val baseUrl = binding.etBaseUrl.text.toString().trim().ifEmpty { currentProvider.baseUrl }
        val maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: 2048
        val temperature = binding.etTemperature.text.toString().toFloatOrNull() ?: 0.7f
        val enabled = binding.switchEnabled.isChecked
        val enableWakeUpContext = binding.switchEnableWakeUpContext.isChecked
        val wakeUpTokenBudget = binding.etWakeUpTokenBudget.text.toString().toIntOrNull() ?: 1024
        val tlsPin = binding.etTlsPin.text.toString().trim()
        if (!isValidTlsPin(tlsPin)) {
            Snackbar.make(
                binding.root,
                getString(R.string.tls_pin_validation_error),
                Snackbar.LENGTH_LONG
            ).show()
            binding.etTlsPin.requestFocus()
            return
        }
        val retrievalModePreference = when (binding.spinnerRetrievalModePreference.selectedItemPosition) {
            0 -> RetrievalModePreference.RAW_VERBATIM
            2 -> RetrievalModePreference.HIERARCHICAL
            else -> RetrievalModePreference.SUMMARY
        }
        val classification = DataClassification.entries.getOrElse(binding.spinnerClassification.selectedItemPosition) { DataClassification.SENSITIVE }
        val localModelPath = binding.etLocalModelPath.text.toString().trim()
        val localProfile = LocalModelProfile.entries.getOrElse(binding.spinnerLocalProfile.selectedItemPosition) {
            LocalModelProfile.BALANCED
        }
        val fallbackOrder = if (binding.spinnerFallbackOrder.selectedItemPosition == 1) {
            LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK
        } else {
            LlmFallbackOrder.REMOTE_ONLY
        }
        val computeBackend = ComputeBackend.entries.getOrElse(binding.spinnerComputeBackend.selectedItemPosition) {
            ComputeBackend.AUTO
        }

        repository.savePrivacyMode(if (binding.spinnerPrivacyMode.selectedItemPosition == 0) PrivacyMode.STRICT_LOCAL_ONLY else PrivacyMode.HYBRID)

        val settings = LlmSettings(
            provider = currentProvider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            enabled = enabled,
            maxTokens = maxTokens,
            temperature = temperature,
            localModelPath = localModelPath,
            localProfile = localProfile,
            fallbackOrder = fallbackOrder,
            runtimeControls = LocalRuntimeControls(
                computeBackend = computeBackend,
                contextWindowTokens = binding.etContextWindow.text?.toString()?.toIntOrNull() ?: localProfile.contextWindowTokens,
                quantizationProfile = binding.etQuantizationProfile.text?.toString()?.trim().orEmpty().ifBlank { "Q4_K_M" },
                maxRamMb = binding.etMaxRamMb.text?.toString()?.toIntOrNull() ?: 4096,
                maxVramMb = binding.etMaxVramMb.text?.toString()?.toIntOrNull() ?: 2048
            ),
            outboundClassification = classification,
            tlsPinnedSpkiSha256 = tlsPin,
            enableWakeUpContext = enableWakeUpContext,
            wakeUpTokenBudget = wakeUpTokenBudget,
            retrievalModePreference = retrievalModePreference
        )

        if (currentProvider.requiresApiKey && settings.apiKey.isBlank()) {
            Snackbar.make(
                binding.root,
                getString(R.string.api_key_required_for_provider, currentProvider.displayName),
                Snackbar.LENGTH_LONG
            ).show()
            binding.etApiKey.requestFocus()
            return
        }
        ProviderSettingsValidator.validate(settings)?.let { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            binding.etBaseUrl.requestFocus()
            return
        }
        repository.saveSettings(settings)

        val idx = currentSettings.indexOfFirst { it.provider == currentProvider }
        if (idx >= 0) currentSettings[idx] = settings else currentSettings.add(settings)

        Snackbar.make(
            binding.root,
            getString(R.string.settings_saved, currentProvider.displayName),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun isValidTlsPin(value: String): Boolean {
        if (value.isBlank()) return true
        if (value.startsWith("sha256/", ignoreCase = true)) return false
        return tlsPinPattern.matches(value)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
