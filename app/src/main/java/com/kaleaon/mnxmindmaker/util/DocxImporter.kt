package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses .docx (Office Open XML) files into a [MindGraph].
 *
 * .docx files are ZIP archives. This importer extracts `word/document.xml`
 * and parses it using the standard XmlPullParser — **no external libraries needed**.
 *
 * Paragraph style → node type mapping:
 * | Word style          | NodeType                             |
 * |---------------------|--------------------------------------|
 * | Title / Heading1    | Updates root IDENTITY label          |
 * | Heading2            | Section group (type from text)       |
 * | Heading3            | Child under current section          |
 * | Quote / IntenseQuote| MEMORY with elevated importance      |
 * | Code / CodeBlock    | KNOWLEDGE node                       |
 * | Normal / (default)  | MEMORY or current section's type    |
 *
 * Bold runs within Normal paragraphs elevate the `importance` dimension to 0.8.
 */
object DocxImporter {

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun fromDocx(inputStream: InputStream, graphName: String = "Imported Mind"): MindGraph {
        val documentXml = extractDocumentXml(inputStream)
            ?: return MindGraph(name = graphName)
        return parseDocumentXml(documentXml, graphName)
    }

    /** Extract `word/document.xml` text from the .docx ZIP archive. */
    private fun extractDocumentXml(inputStream: InputStream): String? {
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return zip.bufferedReader(Charsets.UTF_8).readText()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * Parse the OOXML document XML.
     *
     * Relevant OOXML structure:
     * ```xml
     * <w:body>
     *   <w:p>
     *     <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
     *     <w:r>
     *       <w:rPr><w:b/></w:rPr>
     *       <w:t>Some heading text</w:t>
     *     </w:r>
     *   </w:p>
     * </w:body>
     * ```
     */
    private fun parseDocumentXml(xml: String, graphName: String): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        val state = ParseState()

        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        // Per-paragraph state
        var inParagraph = false
        var paragraphStyle = ""
        val paragraphText = StringBuilder()
        var paragraphHasBold = false
        var runHasBold = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val ns = if (eventType == XmlPullParser.START_TAG || eventType == XmlPullParser.END_TAG)
                parser.namespace else ""
            val local = if (eventType == XmlPullParser.START_TAG || eventType == XmlPullParser.END_TAG)
                parser.name.substringAfterLast(':') else ""

            when (eventType) {
                XmlPullParser.START_TAG -> when {
                    // <w:p> — begin paragraph
                    local == "p" && ns == W_NS -> {
                        inParagraph = true
                        paragraphStyle = ""
                        paragraphText.clear()
                        paragraphHasBold = false
                        runHasBold = false
                    }
                    // <w:pStyle w:val="…"> — capture paragraph style
                    local == "pStyle" && ns == W_NS -> {
                        paragraphStyle = parser.getAttributeValue(W_NS, "val")
                            ?: parser.getAttributeValue(null, "w:val")
                            ?: ""
                    }
                    // <w:b/> — bold flag for current run
                    local == "b" && ns == W_NS -> runHasBold = true
                    // <w:t> — collect text content
                    local == "t" && ns == W_NS && inParagraph -> {
                        // Use TEXT event to read content safely
                        val spaceAttr = parser.getAttributeValue(
                            "http://www.w3.org/XML/1998/namespace", "space"
                        )
                        val nextEvent = parser.next()
                        if (nextEvent == XmlPullParser.TEXT) {
                            val chunk = parser.text ?: ""
                            paragraphText.append(chunk)
                            if (runHasBold) paragraphHasBold = true
                        }
                        // Don't advance again; the loop's next iteration handles END_TAG for <w:t>
                        eventType = parser.eventType
                        continue
                    }
                }

                XmlPullParser.END_TAG -> when {
                    // </w:r> — end of run, reset bold flag
                    local == "r" && ns == W_NS -> runHasBold = false
                    // </w:p> — process the completed paragraph
                    local == "p" && ns == W_NS && inParagraph -> {
                        val text = paragraphText.toString().trim()
                        if (text.isNotEmpty()) {
                            processParagraph(
                                text, paragraphStyle, paragraphHasBold,
                                rootNode, nodes, edges, state
                            )
                        }
                        inParagraph = false
                    }
                }
            }

            eventType = parser.next()
        }

        return MindGraph(name = nodes.firstOrNull()?.label ?: graphName, nodes = nodes, edges = edges)
    }

    /** Mutable parse state shared across paragraph processing calls. */
    private class ParseState {
        var currentSectionNode: MindNode? = null
        var currentSectionIdx: Int = 0
        var nodeRow: Int = 0
    }

    private fun processParagraph(
        text: String,
        style: String,
        isBold: Boolean,
        rootNode: MindNode,
        nodes: MutableList<MindNode>,
        edges: MutableList<MindEdge>,
        state: ParseState
    ) {
        val normalStyle = style.replace(" ", "").lowercase()

        when {
            normalStyle == "heading1" || normalStyle == "title" -> {
                // Update root IDENTITY label in-place
                nodes[0] = nodes[0].copy(label = text)
            }

            normalStyle == "heading2" -> {
                val sectionType = DataMapper.inferSectionType(text)
                val col = state.currentSectionIdx % 5
                val newSection = MindNode(
                    label = text,
                    type = sectionType,
                    x = 80f + col * 200f,
                    y = 200f,
                    parentId = rootNode.id,
                    dimensions = DimensionMapper.defaultDimensions(sectionType)
                )
                nodes.add(newSection)
                edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = newSection.id))
                state.currentSectionNode = newSection
                state.currentSectionIdx++
                state.nodeRow = 0
            }

            normalStyle == "heading3" -> {
                val parent = state.currentSectionNode ?: rootNode
                val inferredType = state.currentSectionNode?.type ?: NodeType.KNOWLEDGE
                val col = state.nodeRow % 4
                val row = state.nodeRow / 4
                val childNode = MindNode(
                    label = text.take(60),
                    type = inferredType,
                    x = parentChildX(parent, col),
                    y = 380f + row * 160f,
                    parentId = parent.id,
                    dimensions = DimensionMapper.defaultDimensions(inferredType)
                )
                nodes.add(childNode)
                edges.add(MindEdge(fromNodeId = parent.id, toNodeId = childNode.id))
                state.nodeRow++
            }

            normalStyle.contains("quote") -> {
                val parent = state.currentSectionNode ?: rootNode
                val col = state.nodeRow % 4
                val row = state.nodeRow / 4
                val dims = DimensionMapper.defaultDimensions(NodeType.MEMORY).toMutableMap()
                dims["importance"] = 0.85f
                dims["distinctiveness"] = 0.8f
                val quoteNode = MindNode(
                    label = text.take(60) + if (text.length > 60) "…" else "",
                    type = NodeType.MEMORY,
                    description = text,
                    x = parentChildX(parent, col),
                    y = 380f + row * 160f,
                    parentId = parent.id,
                    dimensions = dims
                )
                nodes.add(quoteNode)
                edges.add(MindEdge(fromNodeId = parent.id, toNodeId = quoteNode.id))
                state.nodeRow++
            }

            normalStyle.contains("code") -> {
                val parent = state.currentSectionNode ?: rootNode
                val col = state.nodeRow % 4
                val row = state.nodeRow / 4
                val codeNode = MindNode(
                    label = "Code: ${text.take(40)}",
                    type = NodeType.KNOWLEDGE,
                    description = text.take(500),
                    x = parentChildX(parent, col),
                    y = 380f + row * 160f,
                    parentId = parent.id,
                    dimensions = DimensionMapper.defaultDimensions(NodeType.KNOWLEDGE)
                )
                nodes.add(codeNode)
                edges.add(MindEdge(fromNodeId = parent.id, toNodeId = codeNode.id))
                state.nodeRow++
            }

            else -> {
                // Normal body text — typed by section context
                val parent = state.currentSectionNode ?: rootNode
                val inferredType = state.currentSectionNode?.type ?: NodeType.MEMORY
                val col = state.nodeRow % 4
                val row = state.nodeRow / 4
                val dims = DimensionMapper.defaultDimensions(inferredType).toMutableMap()
                if (isBold) dims["importance"] = 0.8f
                val bodyNode = MindNode(
                    label = text.take(60) + if (text.length > 60) "…" else "",
                    type = inferredType,
                    description = text,
                    x = parentChildX(parent, col),
                    y = 380f + row * 160f,
                    parentId = parent.id,
                    dimensions = dims
                )
                nodes.add(bodyNode)
                edges.add(MindEdge(fromNodeId = parent.id, toNodeId = bodyNode.id))
                state.nodeRow++
            }
        }
    }

    private fun parentChildX(parent: MindNode, col: Int): Float =
        maxOf(80f, parent.x - 330f) + col * 220f
}
