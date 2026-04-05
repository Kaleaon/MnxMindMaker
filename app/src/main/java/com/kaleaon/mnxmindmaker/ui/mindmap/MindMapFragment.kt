package com.kaleaon.mnxmindmaker.ui.mindmap

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentMindMapBinding
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.ContinuityAuditResult

class MindMapFragment : Fragment() {

    private var _binding: FragmentMindMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MindMapViewModel by viewModels()

    private val openMnxFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val stream = requireContext().contentResolver.openInputStream(uri) ?: return@registerForActivityResult
        viewModel.importFromMnx(stream)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentMindMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Wire the canvas
        binding.mindMapCanvas.onNodeSelected = { node ->
            viewModel.selectNode(node)
            binding.btnRemoveNode.isEnabled = node != null
        }
        binding.mindMapCanvas.onNodeMoved = { id, x, y ->
            viewModel.updateNodePosition(id, x, y)
        }

        // Observe graph changes
        viewModel.graph.observe(viewLifecycleOwner) { graph ->
            binding.mindMapCanvas.graph = graph
            binding.tvGraphName.text = graph?.name ?: ""
        }

        viewModel.selectedNode.observe(viewLifecycleOwner) { node ->
            binding.tvSelectedNode.text = if (node != null)
                "${node.type.displayName}: ${node.label}"
            else
                getString(R.string.no_node_selected)
        }

        viewModel.exportedFile.observe(viewLifecycleOwner) { file ->
            file ?: return@observe
            Snackbar.make(binding.root, getString(R.string.export_success, file.name), Snackbar.LENGTH_LONG)
                .setAction(R.string.ok) {}
                .show()
            viewModel.clearExportedFile()
        }

        viewModel.llmResponse.observe(viewLifecycleOwner) { response ->
            response ?: return@observe
            showLlmResponseDialog(response)
            viewModel.clearLlmResponse()
        }

        viewModel.error.observe(viewLifecycleOwner) { msg ->
            msg ?: return@observe
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.auditResult.observe(viewLifecycleOwner) { audit ->
            val total = audit?.summary?.totalFindings ?: 0
            binding.btnReviewAudit.text = getString(R.string.review_audit_with_count, total)
        }

        // Apply active Ktheme to the canvas (and react to future changes)
        KthemeManager.activeTheme.observe(viewLifecycleOwner) { theme ->
            binding.mindMapCanvas.applyTheme(theme)
            // Also tint the status bar and action toolbar
            theme?.colorScheme?.let { cs ->
                binding.statusBar.setBackgroundColor(KthemeManager.parseColor(cs.surfaceVariant))
                binding.toolbarActions.setBackgroundColor(KthemeManager.parseColor(cs.surface))
                binding.tvGraphName.setTextColor(KthemeManager.parseColor(cs.onSurface))
                binding.tvSelectedNode.setTextColor(KthemeManager.parseColor(cs.onSurfaceVariant))
            }
        }

        // Button handlers
        binding.btnAddNode.setOnClickListener { showAddNodeDialog() }
        binding.btnRemoveNode.setOnClickListener {
            viewModel.selectedNode.value?.id?.let { viewModel.removeNode(it) }
        }
        binding.btnExportMnx.setOnClickListener { viewModel.exportToMnx() }
        binding.btnImportMnx.setOnClickListener { openMnxFile.launch(arrayOf("*/*")) }
        binding.btnAskAi.setOnClickListener { showAskAiDialog() }
        binding.btnReviewAudit.setOnClickListener {
            viewModel.runContinuityAudit()
            showContinuityAuditDialog(viewModel.auditResult.value)
        }
        binding.btnResetView.setOnClickListener { binding.mindMapCanvas.resetView() }
    }

    private fun showAddNodeDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_node, null)
        val etLabel = dialogView.findViewById<EditText>(R.id.et_node_label)
        val etDesc = dialogView.findViewById<EditText>(R.id.et_node_description)
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinner_node_type)

        val types = NodeType.entries.map { it.displayName }
        spinnerType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, types)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_node)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val label = etLabel.text.toString().trim()
                if (label.isEmpty()) { Toast.makeText(requireContext(), R.string.label_required, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val type = NodeType.entries[spinnerType.selectedItemPosition]
                viewModel.addNode(label, type, etDesc.text.toString().trim())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAskAiDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.ai_prompt_hint)
            minLines = 3
            maxLines = 6
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ask_ai_title)
            .setView(input)
            .setPositiveButton(R.string.ask) { _, _ ->
                val prompt = input.text.toString().trim()
                if (prompt.isNotEmpty()) viewModel.askLlmForMindDesign(prompt)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLlmResponseDialog(response: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_response_title)
            .setMessage(response)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showContinuityAuditDialog(audit: ContinuityAuditResult?) {
        if (audit == null) {
            Snackbar.make(binding.root, R.string.audit_not_available, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (audit.findings.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.review_audit)
                .setMessage(getString(R.string.audit_no_findings))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val labels = audit.findings.mapIndexed { index, finding ->
            val status = if (finding.accepted) "✓" else "!"
            "${index + 1}. [$status ${finding.severity}] ${finding.title}"
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.review_audit_title, audit.summary.totalFindings))
            .setItems(labels) { _, which ->
                val finding = audit.findings[which]
                AlertDialog.Builder(requireContext())
                    .setTitle(finding.title)
                    .setMessage(
                        buildString {
                            appendLine("Category: ${finding.category}")
                            appendLine("Severity: ${finding.severity} (${String.format("%.2f", finding.severityScore)})")
                            appendLine("Confidence: ${String.format("%.2f", finding.confidenceScore)}")
                            appendLine("Node IDs: ${finding.nodeIds.joinToString().ifBlank { "none" }}")
                            appendLine("Rule IDs: ${finding.ruleIds.joinToString().ifBlank { "none" }}")
                            appendLine()
                            appendLine(finding.description)
                            appendLine()
                            append("Action: ${finding.suggestedAction}")
                        }
                    )
                    .setPositiveButton(R.string.accept_warning) { _, _ ->
                        viewModel.acceptAuditFinding(finding.id)
                        Snackbar.make(binding.root, R.string.audit_warning_accepted, Snackbar.LENGTH_SHORT).show()
                    }
                    .setNeutralButton(R.string.create_corrective_node) { _, _ ->
                        viewModel.convertAuditFindingToCorrectiveNode(finding.id)
                        Snackbar.make(binding.root, R.string.audit_corrective_created, Snackbar.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
