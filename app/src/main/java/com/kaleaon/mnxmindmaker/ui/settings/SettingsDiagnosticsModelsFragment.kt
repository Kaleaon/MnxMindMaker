package com.kaleaon.mnxmindmaker.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsDiagnosticsModelsBinding

class SettingsDiagnosticsModelsFragment : Fragment() {

    private var _binding: FragmentSettingsDiagnosticsModelsBinding? = null
    private val binding get() = requireNotNull(_binding) { "Binding accessed outside of view lifecycle." }

    private val viewModel: SettingsViewModel by viewModels({ requireParentFragment() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsDiagnosticsModelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRunPreflight.setOnClickListener {
            val draft = viewModel.getDraftSettings(viewModel.currentProvider)
            viewModel.runPreflightDiagnostics(draft, viewModel.privacyMode)
        }

        binding.btnDiscoverModels.setOnClickListener {
            viewModel.discoverModels()
        }

        binding.btnInstallRecommended.setOnClickListener {
            viewModel.installRecommendedModel()
        }

        viewModel.loadSkillPackDiagnostics(requireContext().assets)

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.preflightResultText.observe(viewLifecycleOwner) { text ->
            binding.tvPreflightResult.text = text
        }

        viewModel.skillPackDiagnosticsText.observe(viewLifecycleOwner) { text ->
            binding.tvSkillPackDiagnostics.text = text
        }

        viewModel.modelManagerSummaryText.observe(viewLifecycleOwner) { text ->
            binding.tvModelManagerSummary.text = text
        }

        viewModel.snackbarMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
