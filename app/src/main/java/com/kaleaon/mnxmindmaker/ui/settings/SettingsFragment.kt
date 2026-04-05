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
import com.kaleaon.mnxmindmaker.model.DataClassification
import com.kaleaon.mnxmindmaker.model.LlmFallbackOrder
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmRuntime
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.LocalModelProfile
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import com.kaleaon.mnxmindmaker.model.defaultModel
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: LlmSettingsRepository
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

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentProvider = LlmProvider.entries[pos]
                loadProviderSettings(currentProvider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadProviderSettings(currentProvider)

        binding.btnSaveSettings.setOnClickListener { saveCurrentProvider() }

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
        binding.switchEnabled.isChecked = settings.enabled
        binding.etTlsPin.setText(settings.tlsPinnedSpkiSha256)
        binding.spinnerClassification.setSelection(DataClassification.entries.indexOf(settings.outboundClassification).coerceAtLeast(0))

        binding.spinnerLocalProfile.setSelection(
            LocalModelProfile.entries.indexOf(settings.localProfile).coerceAtLeast(0)
        )
        binding.spinnerFallbackOrder.setSelection(
            if (settings.fallbackOrder == LlmFallbackOrder.LOCAL_FIRST_REMOTE_FALLBACK) 1 else 0
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
        val tlsPin = binding.etTlsPin.text.toString().trim()
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
            outboundClassification = classification,
            tlsPinnedSpkiSha256 = tlsPin
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
