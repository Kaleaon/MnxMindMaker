package com.kaleaon.mnxmindmaker.repository

import android.content.Context
import com.kaleaon.mnxmindmaker.mnx.MnxCodec
import com.kaleaon.mnxmindmaker.mnx.MnxFile
import com.kaleaon.mnxmindmaker.mnx.MnxFormat
import com.kaleaon.mnxmindmaker.mnx.MnxHeader
import com.kaleaon.mnxmindmaker.mnx.MnxIdentity
import com.kaleaon.mnxmindmaker.mnx.MnxMeta
import com.kaleaon.mnxmindmaker.mnx.mnxDeserialize
import com.kaleaon.mnxmindmaker.mnx.mnxSerialize
import com.kaleaon.mnxmindmaker.model.MindEdge
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

    companion object {
        internal const val GRAPH_PAYLOAD_SECTION_TYPE: Short = (-1).toShort()

        internal fun serializeGraphPayload(graph: MindGraph): ByteArray = mnxSerialize {
            writeString(graph.id)
            writeString(graph.name)
            writeLong(graph.createdAt)
            writeLong(graph.modifiedAt)

            writeList(graph.nodes) { node ->
                writeString(node.id)
                writeString(node.label)
                writeString(node.type.name)
                writeString(node.description)
                writeFloat(node.x)
                writeFloat(node.y)
                writeBoolean(node.parentId != null)
                if (node.parentId != null) writeString(node.parentId)
                writeStringMap(node.attributes)
                writeBoolean(node.isExpanded)
                writeStringFloatMap(node.dimensions)
            }

            writeList(graph.edges) { edge ->
                writeString(edge.id)
                writeString(edge.fromNodeId)
                writeString(edge.toNodeId)
                writeString(edge.label)
                writeFloat(edge.strength)
            }
        }

        internal fun deserializeGraphPayload(data: ByteArray): MindGraph = mnxDeserialize(data) {
            val graphId = readString()
            val graphName = readString()
            val createdAt = readLong()
            val modifiedAt = readLong()

            val nodes = readList {
                val nodeId = readString()
                val nodeLabel = readString()
                val typeName = readString()
                val nodeType = NodeType.values().find { it.name == typeName } ?: NodeType.CUSTOM
                val nodeDescription = readString()
                val nodeX = readFloat()
                val nodeY = readFloat()
                val parentNodeExists = readBoolean()
                MindNode(
                    id = nodeId,
                    label = nodeLabel,
                    type = nodeType,
                    description = nodeDescription,
                    x = nodeX,
                    y = nodeY,
                    parentId = if (parentNodeExists) readString() else null,
                    attributes = readStringMap().toMutableMap(),
                    isExpanded = readBoolean(),
                    dimensions = readStringFloatMap()
                )
            }.toMutableList()

            val edges = readList {
                MindEdge(
                    id = readString(),
                    fromNodeId = readString(),
                    toNodeId = readString(),
                    label = readString(),
                    strength = readFloat()
                )
            }.toMutableList()

            MindGraph(
                id = graphId,
                name = graphName,
                nodes = nodes,
                edges = edges,
                createdAt = createdAt,
                modifiedAt = modifiedAt
            )
        }
    }
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
        val rawSections = mutableMapOf<Short, ByteArray>()

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
            put("graph_id", graph.id)
            put("graph_name", graph.name)
            put("node_count", graph.nodes.size.toString())
            put("edge_count", graph.edges.size.toString())
            put("created_at", graph.createdAt.toString())
            put("modified_at", graph.modifiedAt.toString())
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

        rawSections[GRAPH_PAYLOAD_SECTION_TYPE] = serializeGraphPayload(graph)

        val mnxFile = MnxFile(
            header = MnxHeader(
                createdTimestamp = graph.createdAt,
                modifiedTimestamp = graph.modifiedAt
            ),
            sections = sections,
            rawSections = rawSections
        )

        val outDir = File(context.filesDir, "mnx_exports")
        outDir.mkdirs()
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.mnx")
        MnxCodec.encodeToFile(mnxFile, outFile)
        return outFile
        return MnxFile(header = MnxHeader(), sections = sections)
    }

    /**
     * Import a [MindGraph] from a .mnx [InputStream].
     * Reconstructs the graph from the IDENTITY and META sections.
     */
    fun importFromMnx(stream: InputStream): MindGraph {
        val mnxFile = MnxCodec.decode(stream)

        // Preferred modern import path: fully serialized graph payload.
        if (mnxFile.hasRawSection(GRAPH_PAYLOAD_SECTION_TYPE)) {
            return deserializeGraphPayload(mnxFile.rawSections[GRAPH_PAYLOAD_SECTION_TYPE]!!)
        }

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
