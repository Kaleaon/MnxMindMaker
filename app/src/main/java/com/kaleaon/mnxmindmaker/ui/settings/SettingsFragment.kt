package com.kaleaon.mnxmindmaker.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsBinding
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.model.ComputeBackend
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalModelProfile
import com.kaleaon.mnxmindmaker.model.LocalRuntimeControls
import com.kaleaon.mnxmindmaker.model.ModelManager
import com.kaleaon.mnxmindmaker.model.ModelInstallState
import com.kaleaon.mnxmindmaker.model.defaultModel
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: LlmSettingsRepository
    private lateinit var modelManager: ModelManager
    private var currentProvider: LlmProvider = LlmProvider.ANTHROPIC
    private var currentSettings: MutableList<LlmSettings> = mutableListOf()

    private var themeAdapter: ThemeAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LlmSettingsRepository(requireContext())
        modelManager = ModelManager(requireContext())
        currentSettings = repository.loadAllSettings().toMutableList()

        // ---- Ktheme picker --------------------------------------------------
        setupThemePicker()

        // ---- LLM provider picker --------------------------------------------
        val providers = LlmProvider.entries.map { it.displayName }
        binding.spinnerProvider.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            providers
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

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentProvider = LlmProvider.entries[pos]
                loadProviderSettings(currentProvider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadProviderSettings(currentProvider)

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
        binding.switchEnabled.isChecked = settings.enabled

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
            )
        )
        repository.saveSettings(settings)

        val idx = currentSettings.indexOfFirst { it.provider == currentProvider }
        if (idx >= 0) currentSettings[idx] = settings else currentSettings.add(settings)

        Snackbar.make(
            binding.root,
            getString(R.string.settings_saved, currentProvider.displayName),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
