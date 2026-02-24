package com.kaleaon.mnxmindmaker.ktheme

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ktheme Engine - Core theme management system.
 *
 * Ported from [Kaleaon/Ktheme](https://github.com/Kaleaon/Ktheme) kotlin-plugin
 * for use in Android applications.
 */
class ThemeEngine {
    private val themes = mutableMapOf<String, Theme>()
    private var activeTheme: Theme? = null

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun registerTheme(theme: Theme) {
        val validation = validateTheme(theme)
        if (!validation.valid) {
            throw IllegalArgumentException("Invalid theme: ${validation.errors.joinToString(", ")}")
        }
        themes[theme.metadata.id] = theme
    }

    fun getTheme(id: String): Theme? = themes[id]

    fun getAllThemes(): List<Theme> = themes.values.toList()

    fun setActiveTheme(id: String) {
        val theme = themes[id] ?: throw IllegalArgumentException("Theme not found: $id")
        activeTheme = theme
    }

    fun getActiveTheme(): Theme? = activeTheme

    fun removeTheme(id: String): Boolean {
        if (activeTheme?.metadata?.id == id) activeTheme = null
        return themes.remove(id) != null
    }

    fun validateTheme(theme: Theme): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        with(theme.metadata) {
            if (id.isEmpty()) errors.add("Theme ID is required")
            if (name.isEmpty()) errors.add("Theme name is required")
            if (version.isEmpty()) errors.add("Theme version is required")
        }
        theme.effects?.metallic?.let {
            if (it.enabled && it.intensity > 1) {
                warnings.add("Metallic intensity should be between 0 and 1")
            }
        }
        return ValidationResult(valid = errors.isEmpty(), errors = errors, warnings = warnings)
    }

    fun exportTheme(id: String): String {
        val theme = themes[id] ?: throw IllegalArgumentException("Theme not found: $id")
        return json.encodeToString(theme)
    }

    fun importTheme(jsonString: String): Theme {
        return try {
            val theme = json.decodeFromString<Theme>(jsonString)
            registerTheme(theme)
            theme
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to import theme: ${e.message}", e)
        }
    }

    fun searchByTags(tags: List<String>): List<Theme> {
        return getAllThemes().filter { theme ->
            tags.any { tag -> theme.metadata.tags.contains(tag) }
        }
    }

    fun searchByName(query: String): List<Theme> {
        val lowerQuery = query.lowercase()
        return getAllThemes().filter { theme ->
            theme.metadata.name.lowercase().contains(lowerQuery) ||
                    theme.metadata.description.lowercase().contains(lowerQuery)
        }
    }
}

data class ValidationResult(
    val valid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)
