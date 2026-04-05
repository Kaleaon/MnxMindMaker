package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxFormat
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.mnx.MnxIdentity
import com.kaleaon.mnxmindmaker.mnx.MnxMeta
import com.kaleaon.mnxmindmaker.model.MindGraph
import com.kaleaon.mnxmindmaker.model.MindNode
import com.kaleaon.mnxmindmaker.model.NodeType
import com.kaleaon.mnxmindmaker.util.BootPacketGenerator
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import java.io.File
import java.io.InputStream

/**
 * Repository for reading and writing .mnx files.
 * Bridges the MindGraph model to the MNX binary format.
 */
class MnxRepository(private val context: Context) {

    data class ContinuityMetadata(
        val snapshotId: String,
        val parentSnapshotId: String?,
        val integrityHash: String,
        val reason: String? = null,
        val driftNotes: String? = null
    )

    /**
     * Export a [MindGraph] to a .mnx file in the app's files directory.
     * Returns the output [File].
     */
    fun exportToMnx(graph: MindGraph, continuityMetadata: ContinuityMetadata? = null): File {
        val outDir = File(context.filesDir, "mnx_exports")
        outDir.mkdirs()
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.mnx")
        writeGraphToFile(graph, outFile, continuityMetadata)
        return outFile
    }

    fun writeGraphToFile(graph: MindGraph, outFile: File, continuityMetadata: ContinuityMetadata? = null) {
        MnxCodec.encodeToFile(buildMnxFile(graph, continuityMetadata), outFile)
    }

    fun buildMnxFile(graph: MindGraph, continuityMetadata: ContinuityMetadata? = null): MnxFile {
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
            continuityMetadata?.let {
                put("snapshot_id", it.snapshotId)
                put("parent_snapshot_id", it.parentSnapshotId ?: "")
                put("integrity_hash", it.integrityHash)
                it.reason?.let { reason -> put("snapshot_reason", reason) }
                it.driftNotes?.let { notes -> put("snapshot_drift_notes", notes) }
            }
        })
        sections[MnxFormat.MnxSectionType.META] = MnxCodec.serializeMeta(meta)

        // Build DIMENSIONAL_REFS section — N-dimensional coordinates for every node.
        val dimRefs = DimensionMapper.buildDimensionalRefs(graph.nodes)
        sections[MnxFormat.MnxSectionType.DIMENSIONAL_REFS] =
            MnxCodec.serializeDimensionalRefs(dimRefs)

        return MnxFile(header = MnxHeader(), sections = sections)
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
            nodes.add(
                MindNode(
                    id = identityNodeId,
                    label = identity.name,
                    type = NodeType.IDENTITY,
                    description = identity.biography,
                    x = 400f,
                    y = 300f
                )
            )
            // Restore personality traits as child nodes
            identity.coreTraits.forEachIndexed { i, trait ->
                nodes.add(
                    MindNode(
                        label = trait,
                        type = NodeType.PERSONALITY,
                        x = 150f + i * 120f,
                        y = 500f,
                        parentId = identityNodeId
                    )
                )
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
        val restoredDims: Map<String, Map<String, Float>> =
            if (mnxFile.hasSection(MnxFormat.MnxSectionType.DIMENSIONAL_REFS)) {
                val dimRefs = MnxCodec.deserializeDimensionalRefs(
                    mnxFile.sections[MnxFormat.MnxSectionType.DIMENSIONAL_REFS]!!
                )
                DimensionMapper.restoreDimensions(dimRefs)
            } else {
                emptyMap()
            }

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

    fun exportBootPacketJson(
        graph: MindGraph,
        mode: BootPacketGenerator.Mode = BootPacketGenerator.Mode.FULL
    ): File {
        val packetJson = BootPacketGenerator.generate(graph, mode).toJson()
        val outDir = File(context.filesDir, "boot_packets").also { it.mkdirs() }
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(outDir, "${safeName}_${mode.name.lowercase()}_${System.currentTimeMillis()}.json")
            .also { it.writeText(packetJson) }
    }

    fun exportBootPacketMarkdown(
        graph: MindGraph,
        mode: BootPacketGenerator.Mode = BootPacketGenerator.Mode.FULL
    ): File {
        val packetMd = BootPacketGenerator.generate(graph, mode).toMarkdown()
        val outDir = File(context.filesDir, "boot_packets").also { it.mkdirs() }
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(outDir, "${safeName}_${mode.name.lowercase()}_${System.currentTimeMillis()}.md")
            .also { it.writeText(packetMd) }
    }
}
