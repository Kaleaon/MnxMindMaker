package com.kaleaon.mnxmindmaker.util

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Structured exception carrying root-cause diagnostics, line numbers,
 * context snippets, and troubleshooting tips for file import failures.
 */
class StructuredImportException(
    val lineNumber: Int?,
    val columnNumber: Int?,
    val lineContext: String?,
    val causeExplanation: String,
    val fixTip: String,
    val rawTrace: String,
    cause: Throwable? = null
) : Exception(causeExplanation, cause) {

    companion object {
        fun create(
            message: String,
            rawText: String,
            cause: Throwable? = null,
            fallbackFixTip: String = "Review file formatting and syntax."
        ): StructuredImportException {
            val sw = StringWriter()
            cause?.printStackTrace(PrintWriter(sw))
            val trace = if (cause != null) sw.toString() else message

            val (lineNum, colNum) = extractLineAndColumn(cause?.message ?: message)
            val snippet = if (lineNum != null && lineNum > 0) {
                extractLineSnippet(rawText, lineNum)
            } else null

            return StructuredImportException(
                lineNumber = lineNum,
                columnNumber = colNum,
                lineContext = snippet,
                causeExplanation = message,
                fixTip = fallbackFixTip,
                rawTrace = trace,
                cause = cause
            )
        }

        private fun extractLineAndColumn(msg: String): Pair<Int?, Int?> {
            val lineMatch = Regex("line (\\d+)").find(msg.lowercase())
            val colMatch = Regex("column (\\d+)").find(msg.lowercase())
            val charMatch = Regex("at character (\\d+)").find(msg.lowercase())

            val line = lineMatch?.groupValues?.get(1)?.toIntOrNull()
            val col = colMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: charMatch?.groupValues?.get(1)?.toIntOrNull()

            return Pair(line, col)
        }

        fun extractLineSnippet(rawText: String, lineNum: Int): String? {
            val lines = rawText.lines()
            if (lineNum <= 0 || lineNum > lines.size) return null
            val target = lines[lineNum - 1]
            return target.trim().take(120)
        }
    }
}
