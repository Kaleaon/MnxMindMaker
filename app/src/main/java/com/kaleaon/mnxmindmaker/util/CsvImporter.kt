package com.kaleaon.mnxmindmaker.util

import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType

/**
 * Parses CSV content into a [MindGraph].
 *
 * The first row must be a header row. Column name conventions:
 *
 * | Column name              | Mapped to                                        |
 * |--------------------------|--------------------------------------------------|
 * | `label` or `name`        | [MindNode.label]                                 |
 * | `type`                   | [NodeType] (matched by name, case-insensitive)   |
 * | `description` or `desc`  | [MindNode.description]                           |
 * | Any known dimension name | [MindNode.dimensions] entry (float value)        |
 * | Any other column         | [MindNode.attributes] key/value entry            |
 *
 * If no `type` column is present, the type is inferred from the label text via
 * [DataMapper.inferNodeTypeFromText]. If no `label`/`name` column is present,
 * the first column is used as the label.
 *
 * All nodes are children of a root IDENTITY node representing the mind graph.
 * Supports RFC 4180 quoting (double-quoted fields, escaped `""` inside quotes).
 */
object CsvImporter {

    /** All known dimension names across every NodeType, for automatic float assignment. */
    private val ALL_DIMENSION_NAMES: Set<String> by lazy {
        NodeType.values().flatMap { DimensionMapper.defaultDimensionNames(it) }.toSet()
    }

    fun fromCsv(csvText: String, graphName: String = "Imported Mind"): MindGraph {
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

        val rootNode = MindNode(
            label = graphName,
            type = NodeType.IDENTITY,
            x = 400f, y = 50f,
            dimensions = DimensionMapper.defaultDimensions(NodeType.IDENTITY)
        )
        nodes.add(rootNode)

        val lines = csvText.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return MindGraph(name = graphName, nodes = nodes, edges = edges)

        val headers = parseCsvRow(lines[0]).map { it.trim().lowercase() }
        val labelIdx = headers.indexOfFirst { it == "label" || it == "name" }
        val typeIdx = headers.indexOfFirst { it == "type" }
        val descIdx = headers.indexOfFirst { it == "description" || it == "desc" }

        var colPos = 0
        var rowPos = 0

        for (lineIdx in 1 until lines.size) {
            val values = parseCsvRow(lines[lineIdx])
            if (values.isEmpty()) continue

            val label = (if (labelIdx >= 0 && labelIdx < values.size) values[labelIdx]
            else values[0]).trim()
            if (label.isEmpty()) continue

            val typeStr = if (typeIdx >= 0 && typeIdx < values.size) values[typeIdx].trim() else ""
            val nodeType = if (typeStr.isNotEmpty()) {
                NodeType.values().firstOrNull { it.name.equals(typeStr, ignoreCase = true) }
                    ?: DataMapper.inferNodeTypeFromText(label)
            } else {
                DataMapper.inferNodeTypeFromText(label)
            }

            val description = if (descIdx >= 0 && descIdx < values.size) values[descIdx].trim() else ""

            // Split remaining columns into dimension overrides vs free-form attributes
            val dimOverrides = mutableMapOf<String, Float>()
            val attributes = mutableMapOf<String, String>()
            headers.forEachIndexed { hIdx, header ->
                if (hIdx == labelIdx || hIdx == typeIdx || hIdx == descIdx) return@forEachIndexed
                val cellValue = if (hIdx < values.size) values[hIdx].trim() else return@forEachIndexed
                if (cellValue.isEmpty()) return@forEachIndexed
                if (header in ALL_DIMENSION_NAMES) {
                    cellValue.toFloatOrNull()?.let { dimOverrides[header] = it }
                } else {
                    attributes[header] = cellValue
                }
            }

            val dims = DimensionMapper.defaultDimensions(nodeType).toMutableMap()
            dims.putAll(dimOverrides)

            val node = MindNode(
                label = label.take(80),
                type = nodeType,
                description = description,
                x = 80f + colPos * 220f,
                y = 200f + rowPos * 160f,
                parentId = rootNode.id,
                attributes = attributes,
                dimensions = dims
            )
            nodes.add(node)
            edges.add(MindEdge(fromNodeId = rootNode.id, toNodeId = node.id))

            colPos++
            if (colPos >= 4) {
                colPos = 0
                rowPos++
            }
        }

        return MindGraph(name = graphName, nodes = nodes, edges = edges)
    }

    /**
     * Parse one CSV row following RFC 4180:
     * - Fields may be enclosed in double quotes.
     * - A double quote inside a quoted field is escaped as `""`.
     * - Unquoted fields are terminated by `,` or end-of-line.
     */
    fun parseCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> inQuotes = true
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++ // skip second quote of escaped pair
                }
                ch == '"' && inQuotes -> inQuotes = false
                ch == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result.add(current.toString())
        return result
    }
}
