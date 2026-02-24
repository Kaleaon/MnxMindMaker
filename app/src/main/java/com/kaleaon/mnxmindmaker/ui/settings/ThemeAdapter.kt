package com.kaleaon.mnxmindmaker.ui.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.ItemThemeCardBinding
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.ktheme.Theme

class ThemeAdapter(
    private val themes: List<Theme>,
    private var selectedId: String?,
    private val onThemeSelected: (Theme) -> Unit
) : RecyclerView.Adapter<ThemeAdapter.ThemeViewHolder>() {

    fun setSelectedId(id: String?) {
        val old = selectedId
        selectedId = id
        // Refresh only affected items
        themes.forEachIndexed { i, t ->
            if (t.metadata.id == old || t.metadata.id == id) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThemeViewHolder {
        val binding = ItemThemeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ThemeViewHolder(binding)
    }

    override fun getItemCount() = themes.size

    override fun onBindViewHolder(holder: ThemeViewHolder, position: Int) {
        holder.bind(themes[position])
    }

    inner class ThemeViewHolder(private val binding: ItemThemeCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(theme: Theme) {
            val cs = theme.colorScheme
            binding.swatchPrimary.setBackgroundColor(KthemeManager.parseColor(cs.primary))
            binding.swatchSecondary.setBackgroundColor(KthemeManager.parseColor(cs.secondary))
            binding.swatchBackground.setBackgroundColor(KthemeManager.parseColor(cs.background))
            binding.swatchSurface.setBackgroundColor(KthemeManager.parseColor(cs.surface))

            binding.tvThemeName.text = theme.metadata.name
            binding.tvThemeDescription.text = theme.metadata.description

            val isSelected = theme.metadata.id == selectedId
            binding.cardTheme.strokeColor = if (isSelected)
                KthemeManager.parseColor(cs.primary)
            else
                0x00000000 // transparent
            binding.cardTheme.strokeWidth = if (isSelected) 4 else 0

            binding.root.setOnClickListener { onThemeSelected(theme) }
        }
    }
}
