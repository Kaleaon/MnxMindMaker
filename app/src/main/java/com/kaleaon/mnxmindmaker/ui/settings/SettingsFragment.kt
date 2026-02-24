package com.kaleaon.mnxmindmaker.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsBinding
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.defaultModel
import com.kaleaon.mnxmindmaker.repository.LlmSettingsRepository

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: LlmSettingsRepository
    private var currentProvider: LlmProvider = LlmProvider.ANTHROPIC
    private var currentSettings: MutableList<LlmSettings> = mutableListOf()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LlmSettingsRepository(requireContext())
        currentSettings = repository.loadAllSettings().toMutableList()

        // Provider picker
        val providers = LlmProvider.entries.map { it.displayName }
        binding.spinnerProvider.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, providers)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

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
    }

    private fun loadProviderSettings(provider: LlmProvider) {
        val settings = currentSettings.firstOrNull { it.provider == provider }
            ?: LlmSettings(provider)
        binding.etApiKey.setText(settings.apiKey)
        binding.etModel.setText(settings.model)
        binding.etMaxTokens.setText(settings.maxTokens.toString())
        binding.etTemperature.setText(settings.temperature.toString())
        binding.switchEnabled.isChecked = settings.enabled

        // Show provider-specific hint
        binding.tvApiKeyHint.text = when (provider) {
            LlmProvider.ANTHROPIC -> getString(R.string.hint_anthropic_key)
            LlmProvider.OPENAI -> getString(R.string.hint_openai_key)
            LlmProvider.GEMINI -> getString(R.string.hint_gemini_key)
        }
    }

    private fun saveCurrentProvider() {
        val apiKey = binding.etApiKey.text.toString().trim()
        val model = binding.etModel.text.toString().trim().ifEmpty { currentProvider.defaultModel() }
        val maxTokens = binding.etMaxTokens.text.toString().toIntOrNull() ?: 2048
        val temperature = binding.etTemperature.text.toString().toFloatOrNull() ?: 0.7f
        val enabled = binding.switchEnabled.isChecked

        val settings = LlmSettings(currentProvider, apiKey, model, enabled, maxTokens, temperature)
        repository.saveSettings(settings)

        val idx = currentSettings.indexOfFirst { it.provider == currentProvider }
        if (idx >= 0) currentSettings[idx] = settings else currentSettings.add(settings)

        Snackbar.make(binding.root, getString(R.string.settings_saved, currentProvider.displayName),
            Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
