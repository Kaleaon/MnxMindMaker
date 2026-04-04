package com.kaleaon.mnxmindmaker

import com.kaleaon.mnxmindmaker.util.CsvImporter
import com.kaleaon.mnxmindmaker.util.FileImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileImporterDelimitedFormatsTest {

    @Test
    fun `parseCsvRow supports tab delimiter`() {
        val cols = CsvImporter.parseCsvRow("label\ttype\tdescription", '\t')
        assertEquals(listOf("label", "type", "description"), cols)
    }

    @Test
    fun `unknown format auto-detect parses TSV as table`() {
        val tsv = """
            label\ttype\tdescription
            Integrity\tVALUE\tMoral consistency
            Curiosity\tPERSONALITY\tExploration drive
        """.trimIndent()

        val graph = FileImporter.parseText(tsv, FileImporter.Format.UNKNOWN, "TSV Mind")

        // root + 2 rows
        assertEquals(3, graph.nodes.size)
        assertEquals(2, graph.edges.size)
        assertTrue(graph.nodes.any { it.label == "Integrity" })
        assertTrue(graph.nodes.any { it.label == "Curiosity" })
    }
}
