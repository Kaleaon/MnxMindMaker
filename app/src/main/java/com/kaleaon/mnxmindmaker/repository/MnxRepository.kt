package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxFormat
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.mnx.MnxIdentity
import com.kaleaon.mnxmindmaker.mnx.MnxMeta
import com.kaleaon.mnxmindmaker.model.MindEdge
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import java.io.File
import java.io.InputStream

/**
 * Repository for reading and writing .mnx files.
 * Bridges the MindGraph model to the MNX binary format.
 */
class MnxRepository(private val context: Context) {

    /**
     * Export a [MindGraph] to a .mnx file in the app's files directory.
     * Returns the output [File].
     */
    fun exportToMnx(graph: MindGraph): File {
        val sections = mutableMapOf<MnxFormat.MnxSectionType, ByteArray>()

        // Build IDENTITY section from the root identity node (if present)
        val identityNode = graph.nodes.firstOrNull { it.type == NodeType.IDENTITY }
        val identity = MnxIdentity(
            name = identityNode?.label ?: graph.name,
            createdAt = graph.createdAt,
            biography = identityNode?.description ?: "",
            coreTraits = graph.nodes
                .filter { it.type == NodeType.PERSONALITY }
                .map { it.label },
            attributes = buildMap {
                put("mindmaker_version", "1.0")
                put("graph_id", graph.id)
                graph.nodes.forEach { node ->
                    put("node_${node.id}_type", node.type.name)
                    put("node_${node.id}_label", node.label)
                }
            }
        )
        sections[MnxFormat.MnxSectionType.IDENTITY] = MnxCodec.serializeIdentity(identity)

        // Build META section
        val meta = MnxMeta(buildMap {
            put("app", "MnxMindMaker")
            put("graph_name", graph.name)
            put("node_count", graph.nodes.size.toString())
            put("edge_count", graph.edges.size.toString())
            put("created_at", graph.createdAt.toString())
            put("modified_at", System.currentTimeMillis().toString())
        })
        sections[MnxFormat.MnxSectionType.META] = MnxCodec.serializeMeta(meta)

        // Build DIMENSIONAL_REFS section — N-dimensional coordinates for every node.
        // Each node contributes one ref per named dimension (e.g. VALUES nodes get
        // ethical_weight, social_impact, personal_relevance, priority, universality,
        // actionability, intrinsic_worth — 7 dims — all preserved here even though
        // only x/y are visible on the canvas).
        val dimRefs = DimensionMapper.buildDimensionalRefs(graph.nodes)
        sections[MnxFormat.MnxSectionType.DIMENSIONAL_REFS] =
            MnxCodec.serializeDimensionalRefs(dimRefs)

        val mnxFile = MnxFile(header = MnxHeader(), sections = sections)

        val outDir = File(context.filesDir, "mnx_exports")
        outDir.mkdirs()
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.mnx")
        MnxCodec.encodeToFile(mnxFile, outFile)
        return outFile
    }

    /**
     * Import a [MindGraph] from a .mnx [InputStream].
     * Reconstructs the graph from the IDENTITY and META sections.
     */
    fun importFromMnx(stream: InputStream): MindGraph {
        val mnxFile = MnxCodec.decode(stream)
        var graphName = "Untitled Mind"
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<com.kaleaon.mnxmindmaker.model.MindEdge>()

        // Restore identity
        if (mnxFile.hasSection(MnxFormat.MnxSectionType.IDENTITY)) {
            val identity = MnxCodec.deserializeIdentity(
                mnxFile.sections[MnxFormat.MnxSectionType.IDENTITY]!!
            )
            graphName = identity.name
            val identityNodeId = identity.name + "_id"
            nodes.add(MindNode(
                id = identityNodeId,
                label = identity.name,
                type = NodeType.IDENTITY,
                description = identity.biography,
                x = 400f, y = 300f
            ))
            // Restore personality traits as child nodes
            identity.coreTraits.forEachIndexed { i, trait ->
                nodes.add(MindNode(
                    label = trait,
                    type = NodeType.PERSONALITY,
                    x = 150f + i * 120f, y = 500f,
                    parentId = identityNodeId
                ))
            }
        }

        // Restore meta
        if (mnxFile.hasSection(MnxFormat.MnxSectionType.META)) {
            val meta = MnxCodec.deserializeMeta(
                mnxFile.sections[MnxFormat.MnxSectionType.META]!!
            )
            if (graphName == "Untitled Mind") {
                graphName = meta.entries["graph_name"] ?: graphName
            }
        }

        // Restore N-dimensional coordinates from DIMENSIONAL_REFS section.
        // Each node's dimension map is rebuilt so callers can inspect or
        // re-export the full multi-dimensional structure.
        val restoredDims: Map<String, Map<String, Float>> =
            if (mnxFile.hasSection(MnxFormat.MnxSectionType.DIMENSIONAL_REFS)) {
                val dimRefs = MnxCodec.deserializeDimensionalRefs(
                    mnxFile.sections[MnxFormat.MnxSectionType.DIMENSIONAL_REFS]!!
                )
                DimensionMapper.restoreDimensions(dimRefs)
            } else emptyMap()

        // Patch restored dimensions back onto nodes
        val nodesWithDims = nodes.map { node ->
            val dims = restoredDims[node.id]
            if (dims != null) node.copy(dimensions = dims) else node
        }.toMutableList()

        return MindGraph(name = graphName, nodes = nodesWithDims, edges = edges)
    }

    fun getMnxExportsDir(): File = File(context.filesDir, "mnx_exports").also { it.mkdirs() }

    fun listExportedFiles(): List<File> =
        getMnxExportsDir().listFiles { f -> f.extension == "mnx" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
}
