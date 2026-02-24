package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType

/**
 * Parses Markdown (.md) content into a [MindGraph] with structure-aware mapping.
 *
 * Mapping rules:
 * - YAML frontmatter (`---` block)  → attributes on the root IDENTITY node;
 *   `name`/`title` overrides root label, `bio`/`biography` sets description
 * - H1 (`# `)                      → overrides the root IDENTITY node label
 * - H2 (`## `)                      → section group node; type inferred from heading text
 * - H3 (`### `)                     → child node under the nearest H2 group
 * - Blockquotes (`> `)              → MEMORY node with elevated importance (0.85) and distinctiveness (0.8)
 * - Fenced code blocks (` ``` `)    → KNOWLEDGE node; first line of code becomes label
 * - Bullet/numbered list items      → typed by the nearest enclosing H2 section (or MEMORY if none)
 * - Regular paragraphs              → typed by the nearest enclosing H2 section (or MEMORY if none)
 *
 * Canvas layout:
 * - Root IDENTITY node at (400, 50)
 * - H2 section nodes at y=200, spaced horizontally
 * - All children at y≥380, laid out in a 4-column grid relative to their parent
 */
object MarkdownImporter {

    fun fromMarkdown(text: String, graphName: String = "Imported Mind"): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        var rootLabel = graphName
        var rootBio = ""
        val frontmatterAttrs = mutableMapOf<String, String>()

        val lines = text.lines()
        var i = 0

        // Parse optional YAML frontmatter (--- ... ---)
        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            i = 1
            while (i < lines.size && lines[i].trim() != "---") {
                val fmLine = lines[i]
                val colonIdx = fmLine.indexOf(':')
                if (colonIdx > 0) {
                    val key = fmLine.substring(0, colonIdx).trim()
                    val value = fmLine.substring(colonIdx + 1).trim()
                    frontmatterAttrs[key] = value
                    when (key.lowercase()) {
                        "name", "title", "identity" -> rootLabel = value
                        "bio", "biography", "description" -> rootBio = value
                    }
                }
                i++
            }
            if (i < lines.size) i++ // skip closing ---
        }

        val rootNode = MindNode(
            label = rootLabel,
            type = NodeType.IDENTITY,
            description = rootBio,
            x = 400f, y = 50f,
            attributes = frontmatterAttrs,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        var currentSectionNode: MindNode? = null
        var currentSectionIdx = 0
        var nodeRow = 0
        var inCodeBlock = false
        val codeBlockLines = mutableListOf<String>()

        while (i < lines.size) {
            val rawLine = lines[i]
            val line = rawLine.trim()

            // Toggle fenced code block
            if (line.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeBlockLines.clear()
                } else {
                    // End of code block → KNOWLEDGE node
                    inCodeBlock = false
                    val codeContent = codeBlockLines.joinToString("\n")
                    if (codeContent.isNotEmpty()) {
                        val parent = currentSectionNode ?: rootNode
                        val col = nodeRow % 4
                        val row = nodeRow / 4
                        val knowledgeNode = MindNode(
                            label = "Code: ${codeContent.lines().first().take(40)}",
                            type = NodeType.KNOWLEDGE,
                            description = codeContent.take(500),
                            x = parentChildX(parent, col),
                            y = 380f + row * 160f,
                            parentId = parent.id,
                            dimensions = DimensionMapper.defaultDimensions(NodeType.KNOWLEDGE)
                        )
                        nodes.add(knowledgeNode)
                        edges.add(MindEdge(fromNodeId = parent.id, toNodeId = knowledgeNode.id))
                        nodeRow++
                    }
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeBlockLines.add(rawLine)
                i++
                continue
            }

            when {
                // H1 → update root identity label
                line.startsWith("# ") -> {
                    val heading = line.removePrefix("# ").trim()
                    if (heading.isNotEmpty()) nodes[0] = nodes[0].copy(label = heading)
                }

                // H2 → new section group node
                line.startsWith("## ") -> {
                    val heading = line.removePrefix("## ").trim()
                    val sectionType = DataMapper.inferSectionType(heading)
                    val col = currentSectionIdx % 5
                    currentSectionNode = MindNode(
                        label = heading,
                        type = sectionType,
                        x = 80f + col * 200f,
                        y = 200f,
                        parentId = rootNode.id,
                        dimensions = DimensionMapper.defaultDimensions(sectionType)
                    )
                    nodes.add(currentSectionNode)
                    edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = currentSectionNode!!.id))
                    currentSectionIdx++
                    nodeRow = 0
                }

                // H3 → child under current section (or root)
                line.startsWith("### ") -> {
                    val heading = line.removePrefix("### ").trim()
                    val parent = currentSectionNode ?: rootNode
                    val inferredType = currentSectionNode?.type ?: DataMapper.inferSectionType(heading)
                    val col = nodeRow % 4
                    val row = nodeRow / 4
                    val childNode = MindNode(
                        label = heading,
                        type = inferredType,
                        x = parentChildX(parent, col),
                        y = 380f + row * 160f,
                        parentId = parent.id,
                        dimensions = DimensionMapper.defaultDimensions(inferredType)
                    )
                    nodes.add(childNode)
                    edges.add(MindEdge(fromNodeId = parent.id, toNodeId = childNode.id))
                    nodeRow++
                }

                // Blockquote → MEMORY with elevated importance and distinctiveness
                line.startsWith("> ") -> {
                    val quote = line.removePrefix("> ").trim()
                    if (quote.isEmpty()) { i++; continue }
                    val parent = currentSectionNode ?: rootNode
                    val col = nodeRow % 4
                    val row = nodeRow / 4
                    val dims = DimensionMapper.defaultDimensions(NodeType.MEMORY).toMutableMap()
                    dims["importance"] = 0.85f
                    dims["distinctiveness"] = 0.8f
                    val quoteNode = MindNode(
                        label = quote.take(60) + if (quote.length > 60) "…" else "",
                        type = NodeType.MEMORY,
                        description = quote,
                        x = parentChildX(parent, col),
                        y = 380f + row * 160f,
                        parentId = parent.id,
                        dimensions = dims
                    )
                    nodes.add(quoteNode)
                    edges.add(MindEdge(fromNodeId = parent.id, toNodeId = quoteNode.id))
                    nodeRow++
                }

                // Bullet or numbered list item
                line.matches(Regex("^[-*+]\\s+.*")) || line.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    val content = line
                        .replace(Regex("^[-*+]\\s+"), "")
                        .replace(Regex("^\\d+\\.\\s+"), "")
                        .trim()
                    if (content.isEmpty()) { i++; continue }
                    val parent = currentSectionNode ?: rootNode
                    val inferredType = currentSectionNode?.type ?: NodeType.MEMORY
                    val col = nodeRow % 4
                    val row = nodeRow / 4
                    val listNode = MindNode(
                        label = content.take(60) + if (content.length > 60) "…" else "",
                        type = inferredType,
                        description = content,
                        x = parentChildX(parent, col),
                        y = 380f + row * 160f,
                        parentId = parent.id,
                        dimensions = DimensionMapper.defaultDimensions(inferredType)
                    )
                    nodes.add(listNode)
                    edges.add(MindEdge(fromNodeId = parent.id, toNodeId = listNode.id))
                    nodeRow++
                }

                // Regular non-empty paragraph (skip horizontal rules)
                line.isNotEmpty() && !line.startsWith("---") && !line.startsWith("===") -> {
                    val parent = currentSectionNode ?: rootNode
                    val inferredType = currentSectionNode?.type ?: NodeType.MEMORY
                    val col = nodeRow % 4
                    val row = nodeRow / 4
                    val paraNode = MindNode(
                        label = line.take(60) + if (line.length > 60) "…" else "",
                        type = inferredType,
                        description = line,
                        x = parentChildX(parent, col),
                        y = 380f + row * 160f,
                        parentId = parent.id,
                        dimensions = DimensionMapper.defaultDimensions(inferredType)
                    )
                    nodes.add(paraNode)
                    edges.add(MindEdge(fromNodeId = parent.id, toNodeId = paraNode.id))
                    nodeRow++
                }
            }
            i++
        }

        return MindGraph(name = nodes.firstOrNull()?.label ?: graphName, nodes = nodes, edges = edges)
    }

    /** Compute X position for the nth child (col) of a parent node. */
    private fun parentChildX(parent: MindNode, col: Int): Float =
        maxOf(80f, parent.x - 330f) + col * 220f
}
