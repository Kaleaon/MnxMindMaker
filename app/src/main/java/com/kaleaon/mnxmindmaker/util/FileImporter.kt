package com.kaleaon.mnxmindmaker.util

import android.content.Context
import android.net.Uri
import com.kaleaon.mnxmindmaker.model.MindGraph

/**
 * Unified file import dispatcher.
 *
 * Detects the file format from a URI (using MIME type from the content resolver,
 * falling back to file extension), then routes parsing to the appropriate importer.
 *
 * | Format / Extension                          | Importer                       |
 * |---------------------------------------------|--------------------------------|
 * | `.md` / `text/markdown`                     | [MarkdownImporter]             |
 * | `.docx` / `application/vnd.openxmlformats…` | [DocxImporter]                 |
 * | `.csv` / `text/csv`                         | [CsvImporter]                  |
 * | `.json` / `application/json`                | [DataMapper.fromJson]          |
 * | `.txt` / `text/plain`                       | [DataMapper.fromPlainText]     |
 * | unknown                                     | content-based auto-detection   |
 *
 * Text-based formats (.md, .json, .txt, .csv) can also be parsed from a raw
 * string via [parseText] when the text has already been loaded by the caller.
 */
object FileImporter {

    /** Detected or declared file format. */
    enum class Format {
        MARKDOWN,
        DOCX,
        CSV,
        TSV,
        JSON,
        PLAIN_TEXT,
        /** Format could not be determined from URI metadata; use content-based detection. */
        UNKNOWN
    }

    /**
     * Detect the [Format] of the file at [uri] using the content resolver MIME type,
     * falling back to the URI path file extension.
     */
    fun detectFormat(uri: Uri, context: Context): Format {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        return when {
            mimeType == "text/markdown" || ext == "md" -> Format.MARKDOWN
            mimeType.contains("wordprocessingml") ||
                    mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                    ext == "docx" -> Format.DOCX
            mimeType == "text/csv" || mimeType == "application/csv" ||
                    mimeType == "text/comma-separated-values" || ext == "csv" -> Format.CSV
            mimeType == "text/tab-separated-values" || ext == "tsv" -> Format.TSV
            mimeType == "application/json" || ext == "json" -> Format.JSON
            mimeType == "text/plain" || ext == "txt" -> Format.PLAIN_TEXT
            else -> Format.UNKNOWN
        }
    }

    /**
     * Return true when [format] requires a binary (non-text) stream.
     * For these formats, the caller must open an [InputStream]; the content
     * cannot be displayed in a text field before parsing.
     */
    fun isBinaryFormat(format: Format): Boolean = format == Format.DOCX

    /**
     * Parse a [MindGraph] directly from the URI's byte stream.
     * This is the primary entry point for binary formats like [Format.DOCX].
     *
     * For text formats it reads the stream as UTF-8 and delegates to [parseText].
     */
    fun importFromUri(
        uri: Uri,
        context: Context,
        graphName: String = "Imported Mind"
    ): Result<MindGraph> = runCatching {
        val format = detectFormat(uri, context)
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open input stream for $uri")

        if (format == Format.DOCX) {
            return@runCatching DocxImporter.fromDocx(stream, graphName)
        }

        val text = stream.bufferedReader(Charsets.UTF_8).readText()
        parseText(text, format, graphName)
    }

    /**
     * Parse a [MindGraph] from a raw [text] string with the given [format].
     *
     * When [format] is [Format.UNKNOWN], the content is inspected:
     * - Starts with `{` or `[`   → JSON
     * - Contains a markdown heading (`# ` at line start) → Markdown
     * - Otherwise                → plain text
     */
    fun parseText(text: String, format: Format, graphName: String = "Imported Mind"): MindGraph {
        return when (format) {
            Format.MARKDOWN -> MarkdownImporter.fromMarkdown(text, graphName)
            Format.CSV -> CsvImporter.fromCsv(text, graphName)
            Format.TSV -> CsvImporter.fromTsv(text, graphName)
            Format.JSON -> DataMapper.fromJson(text, graphName)
            Format.PLAIN_TEXT -> DataMapper.fromPlainText(text, graphName)
            Format.UNKNOWN, Format.DOCX -> autoDetectAndParse(text, graphName)
        }
    }

    /** Content-based format detection and parsing fallback. */
    private fun autoDetectAndParse(text: String, graphName: String): MindGraph {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") ->
                DataMapper.fromJson(text, graphName)
            text.contains(Regex("(?m)^#{1,3} ")) ->
                MarkdownImporter.fromMarkdown(text, graphName)
            looksLikeDelimitedTable(text, '\t') ->
                CsvImporter.fromTsv(text, graphName)
            looksLikeDelimitedTable(text, ',') ->
                CsvImporter.fromCsv(text, graphName)
            else ->
                DataMapper.fromPlainText(text, graphName)
        }
    }

    private fun looksLikeDelimitedTable(text: String, delimiter: Char): Boolean {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return false
        val firstCols = CsvImporter.parseCsvRow(lines[0], delimiter).size
        val secondCols = CsvImporter.parseCsvRow(lines[1], delimiter).size
        return firstCols > 1 && secondCols > 1
    }
}
