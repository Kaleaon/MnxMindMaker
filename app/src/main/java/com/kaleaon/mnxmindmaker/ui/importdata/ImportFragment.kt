package com.kaleaon.mnxmindmaker.ui.importdata

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentImportBinding
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.util.DataMapper
import com.kaleaon.mnxmindmaker.util.FileImporter

class ImportFragment : Fragment() {

    private var _binding: FragmentImportBinding? = null
    private val binding get() = _binding!!

    /**
     * The format detected when the user loaded a file via the picker.
     * Remembered so the parse button can use the correct importer.
     * Resets to UNKNOWN whenever the user edits the text manually.
     */
    private var loadedFormat: FileImporter.Format = FileImporter.Format.UNKNOWN

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult

        val format = FileImporter.detectFormat(uri, requireContext())
        val graphName = binding.etGraphName.text.toString().trim()
            .ifEmpty { getString(R.string.default_import_name) }

        if (FileImporter.isBinaryFormat(format)) {
            // Binary format (e.g. .docx): parse immediately — cannot display raw bytes in a text field
            val result = FileImporter.importFromUri(uri, requireContext(), graphName)
            result.onSuccess { graph ->
                ImportDataHolder.pendingGraph = graph
                showMappingPreview(graph)
                Snackbar.make(
                    binding.root,
                    getString(R.string.import_ready, graph.nodes.size),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.open_in_mind_map) {
                    findNavController().navigate(R.id.action_importFragment_to_mindMapFragment)
                }.show()
            }.onFailure { e ->
                Snackbar.make(
                    binding.root,
                    getString(R.string.import_parse_error, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        } else {
            // Text-based format: load into the editor so the user can review / edit before parsing
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.readText()
            if (text != null) {
                binding.etImportText.setText(text)
                loadedFormat = format
                val formatLabel = formatDisplayName(format)
                Snackbar.make(
                    binding.root,
                    getString(R.string.import_file_loaded, formatLabel),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Reset format if user manually edits the text field
        binding.etImportText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) loadedFormat = FileImporter.Format.UNKNOWN
        }

        binding.btnLoadFile.setOnClickListener {
            // Accept all supported formats; system file picker shows matching files
            openFile.launch(arrayOf(
                "text/plain",
                "text/markdown",
                "application/json",
                "text/csv",
                "application/csv",
                "text/tab-separated-values",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "*/*"
            ))
        }

        binding.btnParseData.setOnClickListener {
            val text = binding.etImportText.text.toString().trim()
            if (text.isEmpty()) {
                Snackbar.make(binding.root, R.string.import_empty_error, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val graphName = binding.etGraphName.text.toString().trim()
                .ifEmpty { getString(R.string.default_import_name) }

            val graph: MindGraph = try {
                FileImporter.parseText(text, loadedFormat, graphName)
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.import_parse_error, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            ImportDataHolder.pendingGraph = graph
            showMappingPreview(graph)

            Snackbar.make(
                binding.root,
                getString(R.string.import_ready, graph.nodes.size),
                Snackbar.LENGTH_LONG
            ).setAction(R.string.open_in_mind_map) {
                findNavController().navigate(R.id.action_importFragment_to_mindMapFragment)
            }.show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showMappingPreview(graph: MindGraph) {
        val sections = DataMapper.suggestMnxSections(graph)

        // Count nodes per section type for a compact summary
        val counts = sections.values
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString("\n") { (section, count) -> "  $section × $count" }

        val preview = "${graph.nodes.size} nodes mapped:\n$counts"
            .let { if (it.length > 500) it.take(500) + "…" else it }

        binding.tvMappingPreview.text = getString(R.string.section_mapping_preview, graph.nodes.size, preview)
        binding.tvMappingPreview.visibility = View.VISIBLE
    }

    private fun formatDisplayName(format: FileImporter.Format): String = when (format) {
        FileImporter.Format.MARKDOWN -> "Markdown (.md)"
        FileImporter.Format.DOCX -> "Word document (.docx)"
        FileImporter.Format.CSV -> "CSV (.csv)"
        FileImporter.Format.TSV -> "TSV (.tsv)"
        FileImporter.Format.JSON -> "JSON (.json)"
        FileImporter.Format.PLAIN_TEXT -> "Plain text (.txt)"
        FileImporter.Format.UNKNOWN -> "text"
    }
}

/** Simple holder to pass the imported graph to the MindMap screen. */
object ImportDataHolder {
    var pendingGraph: MindGraph? = null
}
