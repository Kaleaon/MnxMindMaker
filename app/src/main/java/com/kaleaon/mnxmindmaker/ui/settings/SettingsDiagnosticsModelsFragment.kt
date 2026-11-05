package com.kaleaon.mnxmindmaker.ui.settings

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsDiagnosticsModelsBinding
import com.kaleaon.mnxmindmaker.util.provider.PreflightDiagnosticsResult

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

        viewModel.preflightDiagnosticsDialogEvent.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                showPreflightDiagnosticsDialog(result)
                viewModel.clearPreflightDiagnosticsDialogEvent()
            }
        }
    }

    private fun showPreflightDiagnosticsDialog(result: PreflightDiagnosticsResult) {
        val rc = result.rootCause
        val title = rc?.title ?: getString(R.string.preflight_err_generic_title, result.statusCode?.toString() ?: "Error")
        val explanation = rc?.description ?: result.detail
        val tip = rc?.troubleshootingTip ?: getString(R.string.preflight_err_generic_tip)
        val rawTrace = rc?.rawTrace ?: "Endpoint: ${result.endpoint}\nPath: ${result.probePath}\nStatus: ${result.statusCode}\nDetail: ${result.detail}"

        val message = "$explanation\n\n$tip\n\nProbe: ${result.endpoint.trimEnd('/')}${result.probePath} (${result.statusCode ?: "n/a"}, ${result.latencyMs}ms)"

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.preflight_retry_button) { _, _ ->
                val draft = viewModel.getDraftSettings(viewModel.currentProvider)
                viewModel.runPreflightDiagnostics(draft, viewModel.privacyMode)
            }
            .setNeutralButton(R.string.preflight_copy_raw_logs) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Preflight Diagnostics Log", rawTrace)
                clipboard.setPrimaryClip(clip)
                Snackbar.make(binding.root, R.string.preflight_logs_copied, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
