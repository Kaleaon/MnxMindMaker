package com.kaleaon.mnxmindmaker.ktheme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton

/**
 * Android-specific singleton that wraps [ThemeEngine] to manage
 * Ktheme integration for MnxMindMaker.
 *
 * Loads bundled themes from `assets/ktheme/`, persists the active
 * theme selection in [SharedPreferences], and exposes a [LiveData]
 * stream for reactive UI updates.
 */
object KthemeManager {

    private const val PREFS_NAME = "ktheme_prefs"
    private const val KEY_ACTIVE_THEME = "active_theme_id"
    private const val ASSETS_DIR = "ktheme"

    private val engine = ThemeEngine()
    private lateinit var prefs: SharedPreferences

    private val _activeTheme = MutableLiveData<Theme?>()
    val activeTheme: LiveData<Theme?> get() = _activeTheme

    private var initialised = false

    /**
     * Initialise the manager. Safe to call multiple times; only the first
     * call performs work.
     */
    fun init(context: Context) {
        if (initialised) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadBundledThemes(context)
        restoreActiveTheme()
        initialised = true
    }

    // -- Theme catalogue -----------------------------------------------------

    fun getAllThemes(): List<Theme> = engine.getAllThemes()

    fun getTheme(id: String): Theme? = engine.getTheme(id)

    fun searchByName(query: String): List<Theme> = engine.searchByName(query)

    fun searchByTags(tags: List<String>): List<Theme> = engine.searchByTags(tags)

    // -- Active theme --------------------------------------------------------

    fun setActiveTheme(id: String) {
        engine.setActiveTheme(id)
        val theme = engine.getActiveTheme()
        _activeTheme.value = theme
        prefs.edit().putString(KEY_ACTIVE_THEME, id).apply()
    }

    fun getActiveThemeId(): String? = prefs.getString(KEY_ACTIVE_THEME, null)

    fun getActiveThemeSync(): Theme? = engine.getActiveTheme()

    // -- Import / Export -----------------------------------------------------

    fun importThemeJson(json: String): Theme = engine.importTheme(json)

    fun exportThemeJson(id: String): String = engine.exportTheme(id)

    // -- UI application helpers ----------------------------------------------

    /**
     * Apply the given (or current active) theme's colour scheme to common
     * Android view hierarchy elements: root background, AppBarLayout,
     * BottomNavigationView, MaterialButtons, and TextViews.
     */
    fun applyToViewHierarchy(root: View, theme: Theme? = _activeTheme.value) {
        theme ?: return
        val cs = theme.colorScheme
        root.setBackgroundColor(parseColor(cs.background))
        applyRecursive(root, cs)
    }

    private fun applyRecursive(view: View, cs: ColorScheme) {
        when (view) {
            is AppBarLayout -> view.setBackgroundColor(parseColor(cs.surface))
            is BottomNavigationView -> {
                view.setBackgroundColor(parseColor(cs.surface))
                view.itemIconTintList = android.content.res.ColorStateList.valueOf(parseColor(cs.onSurface))
                view.itemTextColor = android.content.res.ColorStateList.valueOf(parseColor(cs.onSurface))
            }
            is MaterialButton -> {
                view.setTextColor(parseColor(cs.onSurface))
                view.strokeColor = android.content.res.ColorStateList.valueOf(parseColor(cs.outline))
            }
            is TextView -> view.setTextColor(parseColor(cs.onBackground))
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyRecursive(view.getChildAt(i), cs)
        }
    }

    // -- Internal helpers ----------------------------------------------------

    private fun loadBundledThemes(context: Context) {
        val assetManager = context.assets
        try {
            val files = assetManager.list(ASSETS_DIR) ?: return
            for (filename in files) {
                if (!filename.endsWith(".json")) continue
                try {
                    val json = assetManager.open("$ASSETS_DIR/$filename")
                        .bufferedReader().use { it.readText() }
                    engine.importTheme(json)
                } catch (e: Exception) {
                    android.util.Log.w("KthemeManager", "Failed to load $filename: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("KthemeManager", "Failed to list bundled themes", e)
        }
    }

    private fun restoreActiveTheme() {
        val savedId = prefs.getString(KEY_ACTIVE_THEME, null)
        if (savedId != null && engine.getTheme(savedId) != null) {
            engine.setActiveTheme(savedId)
            _activeTheme.value = engine.getActiveTheme()
        }
        // If nothing saved yet, leave activeTheme null (app uses default colours)
    }

    fun parseColor(hex: String): Int {
        return try {
            ColorUtils.hexToColorInt(hex)
        } catch (_: Exception) {
            Color.MAGENTA // visible fallback for debugging
        }
    }
}
