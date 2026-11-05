package com.kaleaon.mnxmindmaker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredImportExceptionTest {

    @Test
    fun create_extractsLineAndColumnFromMessage() {
        val rawJson = """
            {
                "name": "Test Graph",
                "nodes": [
                    { "id": "1", "label": "Node 1" }
                    INVALID_TOKEN
                ]
            }
        """.trimIndent()

        val cause = IllegalArgumentException("Error on line 5 column 9: Unexpected token")
        val exception = StructuredImportException.create(
            message = "JSON Syntax Error",
            rawText = rawJson,
            cause = cause,
            fallbackFixTip = "Check commas and brackets."
        )

        assertEquals(5, exception.lineNumber)
        assertEquals(9, exception.columnNumber)
        assertNotNull(exception.lineContext)
        assertTrue(exception.lineContext!!.contains("INVALID_TOKEN"))
        assertEquals("JSON Syntax Error", exception.causeExplanation)
        assertEquals("Check commas and brackets.", exception.fixTip)
    }

    @Test
    fun extractLineSnippet_returnsCorrectLineContent() {
        val multilineText = "Line 1\nLine 2\nLine 3 with context\nLine 4"
        val snippet = StructuredImportException.extractLineSnippet(multilineText, 3)
        assertEquals("Line 3 with context", snippet)

        val outOfBounds = StructuredImportException.extractLineSnippet(multilineText, 10)
        assertNull(outOfBounds)
    }
}
