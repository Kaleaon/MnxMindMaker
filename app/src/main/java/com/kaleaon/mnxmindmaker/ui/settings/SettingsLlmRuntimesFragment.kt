package com.kaleaon.mnxmindmaker.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsLlmRuntimesBinding
import com.kaleaon.mnxmindmaker.model.ComputeBackend
import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalModelProfile
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.model.LocalRuntimeEngine
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.model.RetrievalModePreference
import com.kaleaon.mnxmindmaker.util.provider.ValidationIssue
import com.kaleaon.mnxmindmaker.util.provider.validate

class SettingsLlmRuntimesFragment : Fragment() {

    private var _binding: FragmentSettingsLlmRuntimesBinding? = null
    private val binding get() = requireNotNull(_binding) { "Binding accessed outside of view lifecycle." }

    private val viewModel: SettingsViewModel by viewModels({ requireParentFragment() })

    private var isUpdatingUiFromViewModel = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsLlmRuntimesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSpinners()
        setupListeners()
        setupVendorInfo()
        observeViewModel()

        loadProviderSettings(viewModel.currentProvider)
    }

    private fun setupSpinners() {
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

        binding.spinnerLocalRuntimeEngine.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            LocalRuntimeEngine.entries.map { it.displayName }
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

        binding.spinnerPrivacyMode.setSelection(if (viewModel.privacyMode == PrivacyMode.STRICT_LOCAL_ONLY) 0 else 1)
        binding.spinnerProvider.setSelection(LlmProvider.entries.indexOf(viewModel.currentProvider).coerceAtLeast(0))
    }

    private fun setupListeners() {
        val autoSyncWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUiFromViewModel) {
                    syncDraftToViewModel()
                }
            }
        }

        binding.etApiKey.addTextChangedListener(autoSyncWatcher)
        binding.etModel.addTextChangedListener(autoSyncWatcher)
        binding.etBaseUrl.addTextChangedListener(autoSyncWatcher)
        binding.etMaxTokens.addTextChangedListener(autoSyncWatcher)
        binding.etTemperature.addTextChangedListener(autoSyncWatcher)
        binding.etLocalModelPath.addTextChangedListener(autoSyncWatcher)
        binding.etContextWindow.addTextChangedListener(autoSyncWatcher)
        binding.etQuantizationProfile.addTextChangedListener(autoSyncWatcher)
        binding.etMaxRamMb.addTextChangedListener(autoSyncWatcher)
        binding.etMaxVramMb.addTextChangedListener(autoSyncWatcher)
        binding.etTlsPin.addTextChangedListener(autoSyncWatcher)
        binding.etWakeUpTokenBudget.addTextChangedListener(autoSyncWatcher)

        binding.switchEnableWakeUpContext.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUiFromViewModel) syncDraftToViewModel()
        }
        binding.switchEnabled.setOnCheckedChangeListener { _, _ ->
            if (!isUpdatingUiFromViewModel) syncDraftToViewModel()
        }

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (isUpdatingUiFromViewModel) return
                syncDraftToViewModel()
                val newProvider = LlmProvider.entries[pos]
                viewModel.currentProvider = newProvider
                loadProviderSettings(newProvider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val spinnerSyncListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!isUpdatingUiFromViewModel) {
                    syncDraftToViewModel()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerPrivacyMode.onItemSelectedListener = spinnerSyncListener
        binding.spinnerClassification.onItemSelectedListener = spinnerSyncListener
        binding.spinnerLocalProfile.onItemSelectedListener = spinnerSyncListener
        binding.spinnerFallbackOrder.onItemSelectedListener = spinnerSyncListener
        binding.spinnerComputeBackend.onItemSelectedListener = spinnerSyncListener
        binding.spinnerLocalRuntimeEngine.onItemSelectedListener = spinnerSyncListener
        binding.spinnerRetrievalModePreference.onItemSelectedListener = spinnerSyncListener

        binding.btnSaveSettings.setOnClickListener {
            val draft = buildDraftSettings()
            val privacyMode = getSelectedPrivacyMode()
            val issues = validate(draft, privacyMode)
            showValidationErrors(issues)
            viewModel.saveProviderSettings(draft, privacyMode)
        }
    }

    private fun setupVendorInfo() {
        binding.tvAnthropicInfo.text = getString(R.string.anthropic_api_info)
        binding.tvOpenAiInfo.text = getString(R.string.openai_api_info)
        binding.tvGeminiInfo.text = getString(R.string.gemini_api_info)
        binding.tvVllmInfo.text = getString(R.string.vllm_gemma4_info)
    }

    private fun observeViewModel() {
        viewModel.snackbarMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }

        viewModel.modelInstallSuccessEvent.observe(viewLifecycleOwner) { settings ->
            if (settings != null && viewModel.currentProvider == LlmProvider.LOCAL_ON_DEVICE) {
                loadProviderSettings(LlmProvider.LOCAL_ON_DEVICE)
            }
        }
    }

    private fun getSelectedPrivacyMode(): PrivacyMode {
        return if (binding.spinnerPrivacyMode.selectedItemPosition == 0) {
            PrivacyMode.STRICT_LOCAL_ONLY
        } else {
            PrivacyMode.HYBRID
        }
    }

    private fun loadProviderSettings(provider: LlmProvider) {
        isUpdatingUiFromViewModel = true
        showValidationErrors(emptyList())

        val settings = viewModel.getDraftSettings(provider)
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
        binding.spinnerLocalRuntimeEngine.setSelection(
            LocalRuntimeEngine.entries.indexOf(settings.runtimeControls.engine).coerceAtLeast(0)
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
            LlmProvider.LOCAL_ON_DEVICE -> {
                if (settings.runtimeControls.engine == LocalRuntimeEngine.LITERT_LM) {
                    getString(R.string.hint_local_litert_model_path)
                } else {
                    getString(R.string.hint_local_model_path)
                }
            }
        }

        val caps = settings.capabilities
        binding.tvCapabilitySummary.text = getString(
            R.string.capability_summary,
            caps.contextWindowTokens,
            if (caps.supportsToolPlanning) getString(R.string.capability_supported) else getString(R.string.capability_limited),
            if (caps.supportsPacketGeneration) getString(R.string.capability_supported) else getString(R.string.capability_limited)
        )

        isUpdatingUiFromViewModel = false
    }

    private fun syncDraftToViewModel() {
        val draft = buildDraftSettings()
        viewModel.updateDraftSettings(draft)
        viewModel.privacyMode = getSelectedPrivacyMode()
    }

    private fun buildDraftSettings(): LlmSettings {
        val currentProvider = viewModel.currentProvider
        val model = binding.etModel.text.toString().trim()
        val localProfile = LocalModelProfile.entries.getOrElse(binding.spinnerLocalProfile.selectedItemPosition) {
            LocalModelProfile.BALANCED
        }
        return LlmSettings(
            provider = currentProvider,
            apiKey = binding.etApiKey.text.toString().trim(),
            model = model,
            baseUrl = binding.etBaseUrl.text.toString().trim().ifEmpty { currentProvider.baseUrl },
            enabled = binding.switchEnabled.isChecked,
            maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: 2048,
            temperature = binding.etTemperature.text.toString().toFloatOrNull() ?: 0.7f,
            localModelPath = binding.etLocalModelPath.text.toString().trim(),
            localProfile = localProfile,
            fallbackOrder = if (binding.spinnerFallbackOrder.selectedItemPosition == 1) {
                LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK
            } else {
                LlmFallbackOrder.REMOTE_ONLY
            },
            runtimeControls = LocalRuntimeControls(
                computeBackend = ComputeBackend.entries.getOrElse(binding.spinnerComputeBackend.selectedItemPosition) { ComputeBackend.AUTO },
                engine = LocalRuntimeEngine.entries.getOrElse(binding.spinnerLocalRuntimeEngine.selectedItemPosition) {
                    LocalRuntimeEngine.LLMEDGE
                },
                contextWindowTokens = binding.etContextWindow.text?.toString()?.toIntOrNull() ?: localProfile.contextWindowTokens,
                quantizationProfile = binding.etQuantizationProfile.text?.toString()?.trim().orEmpty().ifBlank { "Q4_K_M" },
                maxRamMb = binding.etMaxRamMb.text?.toString()?.toIntOrNull() ?: 4096,
                maxVramMb = binding.etMaxVramMb.text?.toString()?.toIntOrNull() ?: 2048
            ),
            outboundClassification = DataClassification.entries.getOrElse(binding.spinnerClassification.selectedItemPosition) { DataClassification.SENSITIVE },
            tlsPinnedSpkiSha256 = binding.etTlsPin.text.toString().trim(),
            enableWakeUpContext = binding.switchEnableWakeUpContext.isChecked,
            wakeUpTokenBudget = binding.etWakeUpTokenBudget.text.toString().toIntOrNull() ?: 1024,
            retrievalModePreference = when (binding.spinnerRetrievalModePreference.selectedItemPosition) {
                0 -> RetrievalModePreference.RAW_VERBATIM
                2 -> RetrievalModePreference.HIERARCHICAL
                else -> RetrievalModePreference.SUMMARY
            }
        )
    }

    private fun showValidationErrors(issues: List<ValidationIssue>) {
        binding.tilModel.error = issues.firstOrNull { it.field == "model" }?.message
        binding.tilBaseUrl.error = issues.firstOrNull { it.field == "baseUrl" }?.message
        binding.tilMaxTokens.error = issues.firstOrNull { it.field == "maxTokens" }?.message
        binding.tilContextWindow.error = issues.firstOrNull { it.field == "contextWindowTokens" }?.message
        binding.tilTlsPin.error = issues.firstOrNull { it.field == "tlsPinnedSpkiSha256" }?.message
        binding.tilWakeUpTokenBudget.error = issues.firstOrNull { it.field == "wakeUpTokenBudget" }?.message

        val providerIssue = issues.firstOrNull { it.field == "provider" }?.message
        if (providerIssue != null) {
            binding.tvCapabilitySummary.text = providerIssue
        } else {
            val settings = viewModel.getDraftSettings(viewModel.currentProvider)
            val caps = settings.capabilities
            binding.tvCapabilitySummary.text = getString(
                R.string.capability_summary,
                caps.contextWindowTokens,
                if (caps.supportsToolPlanning) getString(R.string.capability_supported) else getString(R.string.capability_limited),
                if (caps.supportsPacketGeneration) getString(R.string.capability_supported) else getString(R.string.capability_limited)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
