package com.kaleaon.mnxmindmaker.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsAppearanceBinding
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager

class SettingsAppearanceFragment : Fragment() {

    private var _binding: FragmentSettingsAppearanceBinding? = null
    private val binding get() = requireNotNull(_binding) { "Binding accessed outside of view lifecycle." }

    private val viewModel: SettingsViewModel by viewModels({ requireParentFragment() })

    private var themeAdapter: ThemeAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsAppearanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupThemePicker()
        observeViewModel()
    }

    private fun setupThemePicker() {
        val allThemes = KthemeManager.getAllThemes().sortedBy { it.metadata.name }
        val activeId = KthemeManager.getActiveThemeId()

        themeAdapter = ThemeAdapter(allThemes, activeId) { theme ->
            viewModel.setActiveTheme(theme.metadata.id, theme.metadata.name)
            themeAdapter?.setSelectedId(theme.metadata.id)
        }

        binding.rvThemes.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvThemes.adapter = themeAdapter

        if (activeId != null) {
            val idx = allThemes.indexOfFirst { it.metadata.id == activeId }
            if (idx >= 0) binding.rvThemes.scrollToPosition(idx)
        }

        binding.btnResetTheme.setOnClickListener {
            viewModel.resetTheme()
            themeAdapter?.setSelectedId(null)
        }
    }

    private fun observeViewModel() {
        viewModel.activeThemeText.observe(viewLifecycleOwner) { text ->
            binding.tvActiveTheme.text = text
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
