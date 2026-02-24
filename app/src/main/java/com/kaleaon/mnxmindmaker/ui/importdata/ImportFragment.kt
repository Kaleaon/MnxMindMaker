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

class ImportFragment : Fragment() {

    private var _binding: FragmentImportBinding? = null
    private val binding get() = _binding!!

    private val openTextFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
        if (text != null) {
            binding.etImportText.setText(text)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLoadFile.setOnClickListener {
            openTextFile.launch(arrayOf("text/plain", "application/json", "*/*"))
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
                if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
                    DataMapper.fromJson(text, graphName)
                } else {
                    DataMapper.fromPlainText(text, graphName)
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, getString(R.string.import_parse_error, e.message), Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Show section mapping preview
            val sections = DataMapper.suggestMnxSections(graph)
            val preview = sections.entries
                .joinToString("\n") { (_, section) -> "→ $section" }
                .let { if (it.length > 500) it.take(500) + "…" else it }
            binding.tvMappingPreview.text = getString(R.string.section_mapping_preview, graph.nodes.size, preview)
            binding.tvMappingPreview.visibility = View.VISIBLE

            // Store graph for navigation back to mind map
            ImportDataHolder.pendingGraph = graph

            Snackbar.make(binding.root,
                getString(R.string.import_ready, graph.nodes.size),
                Snackbar.LENGTH_LONG)
                .setAction(R.string.open_in_mind_map) {
                    findNavController().navigate(R.id.action_importFragment_to_mindMapFragment)
                }
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Simple holder to pass the imported graph to the MindMap screen. */
object ImportDataHolder {
    var pendingGraph: MindGraph? = null
}
