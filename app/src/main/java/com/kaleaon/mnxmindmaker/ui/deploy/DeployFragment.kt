package com.kaleaon.mnxmindmaker.ui.deploy

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.databinding.FragmentDeployBinding

class DeployFragment : Fragment() {

    private var _binding: FragmentDeployBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeployViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeployBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnRefreshDeploySources.setOnClickListener { viewModel.refreshFromSources() }
        binding.btnDeployNext.setOnClickListener { viewModel.goToNextStep() }
        binding.btnDeployBack.setOnClickListener { viewModel.goToPreviousStep() }
        binding.btnDeployConfirm.setOnClickListener {
            viewModel.updateRuntimeConfig(
                environment = binding.etDeployEnvironment.text?.toString().orEmpty().trim(),
                endpoint = binding.etDeployEndpoint.text?.toString().orEmpty().trim(),
                publishChannel = binding.etDeployReleaseChannel.text?.toString().orEmpty().trim(),
                notes = binding.etDeployNotes.text?.toString().orEmpty().trim()
            )
            viewModel.confirmDeployment()
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            renderState(state)

            state.errorMessage?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearMessages()
            }
            state.confirmationMessage?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                viewModel.clearMessages()
            }
        }
    }

    private fun renderState(state: DeployUiState) {
        binding.stepFlipper.displayedChild = state.currentStep.ordinal

        binding.tvDeployGraphSummary.text = state.graph?.let {
            "${it.name} (${it.nodes.size} nodes, ${it.edges.size} edges)"
        } ?: "No graph available. Open Mind Map and build/import a graph first."

        binding.tvDeployValidationSummary.text = state.audit?.summary?.let { summary ->
            "Findings: ${summary.totalFindings} (critical ${summary.criticalCount}, high ${summary.highCount}, medium ${summary.mediumCount}, low ${summary.lowCount})"
        } ?: "No validator output available yet."

        if (binding.etDeployEnvironment.text.isNullOrBlank()) {
            binding.etDeployEnvironment.setText(state.runtimeConfig.environment)
            binding.etDeployEndpoint.setText(state.runtimeConfig.endpoint)
            binding.etDeployReleaseChannel.setText(state.runtimeConfig.publishChannel)
            binding.etDeployNotes.setText(state.runtimeConfig.notes)
        }

        binding.tvDeploySummary.text = state.manifestPreview?.let { manifest ->
                "Deployment ${manifest.deploymentId.take(8)}\n" +
                "Graph: ${manifest.graphName}\n" +
                "Runtime: ${manifest.runtimeConfig.environment} / ${manifest.runtimeConfig.publishChannel}\n" +
                "Promotion approval: ${if (manifest.runtimeConfig.requiresPromotionApproval) "required" else "not required"}\n" +
                "Rollback channel: ${manifest.runtimeConfig.rollbackChannel}\n" +
                "Endpoint: ${manifest.runtimeConfig.endpoint.ifBlank { "(none)" }}\n" +
                "Compatibility constraints: ${manifest.runtimeConfig.compatibilityConstraints.joinToString()}\n" +
                "History events: ${manifest.deploymentHistory.size}\n" +
                "Findings: ${manifest.findingCount} total, ${manifest.criticalFindingCount} critical\n" +
                manifest.summary
        } ?: "Summary unavailable until graph sources are loaded."

        binding.btnDeployBack.isEnabled = state.currentStep != DeployWizardStep.GRAPH
        binding.btnDeployNext.isEnabled = state.currentStep != DeployWizardStep.SUMMARY
        binding.btnDeployConfirm.visibility = if (state.currentStep == DeployWizardStep.SUMMARY) View.VISIBLE else View.GONE
        binding.btnDeployConfirm.isEnabled = !state.isSaving
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
