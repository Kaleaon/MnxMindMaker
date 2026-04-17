package com.kaleaon.mnxmindmaker.ui

import android.content.Intent

/**
 * Normalizes and validates external VIEW intents that target an importable file.
 *
 * This keeps intent parsing unit-testable and independent from Android framework state.
 */
object ImportIntentResolver {

    sealed class Resolution {
        data object Ignore : Resolution()
        data object Unsupported : Resolution()
        data object InvalidUri : Resolution()
        data class Import(val uriString: String) : Resolution()
    }

    private val supportedMimeTypes = setOf(
        "application/x-mind-nexus",
        "text/plain",
        "text/markdown",
        "application/json",
        "text/csv",
        "application/csv",
        "text/tab-separated-values",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )

    private val supportedExtensions = setOf("mnx", "md", "json", "csv", "tsv", "txt", "docx")
    private val supportedSchemes = setOf("content", "file")

    fun resolve(action: String?, type: String?, dataString: String?): Resolution {
        if (action != Intent.ACTION_VIEW) return Resolution.Ignore

        if (dataString.isNullOrBlank()) return Resolution.Unsupported

        val parsed = runCatching { java.net.URI(dataString) }.getOrNull() ?: return Resolution.InvalidUri
        val scheme = parsed.scheme?.lowercase() ?: return Resolution.InvalidUri
        if (scheme !in supportedSchemes) return Resolution.Unsupported

        val mimeType = type?.lowercase()
        val extension = parsed.path
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

        val mimeSupported = mimeType != null && mimeType in supportedMimeTypes
        val extensionSupported = extension in supportedExtensions

        return if (mimeSupported || extensionSupported) {
            Resolution.Import(dataString)
        } else {
            Resolution.Unsupported
        }
    }
}
