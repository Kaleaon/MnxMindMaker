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
import com.kaleaon.mnxmindmaker.persona.runtime.ClassificationPrivacyConstraints
import com.kaleaon.mnxmindmaker.persona.runtime.FallbackStrategy
import com.kaleaon.mnxmindmaker.persona.runtime.InferenceParams
import com.kaleaon.mnxmindmaker.persona.runtime.PersonaDeploymentManifest
import com.kaleaon.mnxmindmaker.persona.runtime.RuntimeTarget
import com.kaleaon.mnxmindmaker.persona.runtime.ToolPolicy
import com.kaleaon.mnxmindmaker.util.BootPacketGenerator
import com.kaleaon.mnxmindmaker.util.DimensionMapper
import java.io.File
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for reading and writing .mnx files.
 * Bridges the MindGraph model to the MNX binary format.
 */
class MnxRepository(private val context: Context) {

    companion object {
        internal const val GRAPH_PAYLOAD_SECTION_TYPE: Short = (-1).toShort()
        internal const val WORKSPACE_PACK_SECTION_TYPE: Short = (-2).toShort()
        internal const val META_PERSONA_DEPLOYMENT_KEY = "persona.deployment.v1"

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

        internal fun serializeWorkspacePack(pack: MindWorkspacePack): ByteArray = mnxSerialize {
            writeInt(pack.version)
            writeLong(pack.exportedAt)
            writeList(pack.workspaces) { workspace ->
                writeString(workspace.id)
                writeString(workspace.name)
                writeString(workspace.ownership.name)
                writeStringMap(workspace.attributes)
                writeList(workspace.permissions) { permission ->
                    writeString(permission.principalId)
                    writeStringList(permission.scopes.map { it.name })
                }
                writeStringList(workspace.mindIds)
            }
            writeList(pack.minds) { namedMind ->
                writeString(namedMind.id)
                writeString(namedMind.name)
                writeString(namedMind.workspaceId)
                val graphPayload = serializeGraphPayload(namedMind.graph)
                writeInt(graphPayload.size)
                writeBytes(graphPayload)
            }
        }

        internal fun deserializeWorkspacePack(data: ByteArray): MindWorkspacePack = mnxDeserialize(data) {
            val version = readInt()
            val exportedAt = readLong()
            val workspaces = readList {
                MindWorkspace(
                    id = readString(),
                    name = readString(),
                    ownership = OwnershipBoundary.valueOf(readString()),
                    attributes = readStringMap(),
                    permissions = readList {
                        WorkspacePermission(
                            principalId = readString(),
                            scopes = readStringList().mapNotNull {
                                runCatching { WorkspaceScope.valueOf(it) }.getOrNull()
                            }.toSet()
                        )
                    },
                    mindIds = readStringList()
                )
            }
            val minds = readList {
                val mindId = readString()
                val mindName = readString()
                val workspaceId = readString()
                val graph = deserializeGraphPayload(readBytes(readInt()))
                NamedMind(
                    id = mindId,
                    name = mindName,
                    workspaceId = workspaceId,
                    graph = graph.copy(id = mindId, name = mindName)
                )
            }
            MindWorkspacePack(version = version, exportedAt = exportedAt, workspaces = workspaces, minds = minds)
        }

        internal fun encodePersonaDeploymentManifest(
            manifest: PersonaDeploymentManifest
        ): String = JSONObject().apply {
            put(
                "target",
                JSONObject().put("provider", manifest.target.provider)
                    .put("model", manifest.target.model)
                    .put("runtime", manifest.target.runtime)
            )
            put(
                "inference",
                JSONObject().put("temperature", manifest.inference.temperature)
                    .put("max_tokens", manifest.inference.maxTokens)
                    .put("context_window", manifest.inference.contextWindow)
            )
            put(
                "tools",
                JSONObject().put("allowlist", JSONArray(manifest.toolPolicy.allowlist))
                    .put("policy", manifest.toolPolicy.policy)
            )
            put(
                "constraints",
                JSONObject().put("classification", manifest.classification.classification)
                    .put("privacy", manifest.classification.privacy)
            )
            put(
                "fallback",
                JSONObject().put("mode", manifest.fallback.mode)
                    .put("target", manifest.fallback.target)
                    .put("max_retries", manifest.fallback.maxRetries)
            )
        }.toString()

        internal fun decodePersonaDeploymentManifest(raw: String?): PersonaDeploymentManifest {
            if (raw.isNullOrBlank()) return PersonaDeploymentManifest.defaults()
            return runCatching {
                val root = JSONObject(raw)
                val target = root.optJSONObject("target")
                val inference = root.optJSONObject("inference")
                val tools = root.optJSONObject("tools")
                val constraints = root.optJSONObject("constraints")
                val fallback = root.optJSONObject("fallback")
                val allowlist = mutableListOf<String>()
                val allowlistJson = tools?.optJSONArray("allowlist")
                if (allowlistJson != null) {
                    for (i in 0 until allowlistJson.length()) {
                        allowlist.add(allowlistJson.optString(i))
                    }
                }
                PersonaDeploymentManifest(
                    target = RuntimeTarget(
                        provider = target?.takeIf { it.has("provider") }?.optString("provider")
                            ?: RuntimeTarget().provider,
                        model = target?.takeIf { it.has("model") }?.optString("model")
                            ?: RuntimeTarget().model,
                        runtime = target?.takeIf { it.has("runtime") }?.optString("runtime")
                            ?: RuntimeTarget().runtime
                    ),
                    inference = InferenceParams(
                        temperature = inference?.takeIf { it.has("temperature") }
                            ?.optDouble("temperature")
                            ?: InferenceParams().temperature,
                        maxTokens = inference?.takeIf { it.has("max_tokens") }?.optInt("max_tokens")
                            ?: InferenceParams().maxTokens,
                        contextWindow = inference?.takeIf { it.has("context_window") }
                            ?.optInt("context_window")
                            ?: InferenceParams().contextWindow
                    ),
                    toolPolicy = ToolPolicy(
                        allowlist = allowlist.filter { it.isNotBlank() },
                        policy = tools?.takeIf { it.has("policy") }?.optString("policy")
                            ?: ToolPolicy().policy
                    ),
                    classification = ClassificationPrivacyConstraints(
                        classification = constraints?.takeIf { it.has("classification") }
                            ?.optString("classification")
                            ?: ClassificationPrivacyConstraints().classification,
                        privacy = constraints?.takeIf { it.has("privacy") }?.optString("privacy")
                            ?: ClassificationPrivacyConstraints().privacy
                    ),
                    fallback = FallbackStrategy(
                        mode = fallback?.takeIf { it.has("mode") }?.optString("mode")
                            ?: FallbackStrategy().mode,
                        target = fallback?.takeIf { it.has("target") }?.optString("target")
                            ?: FallbackStrategy().target,
                        maxRetries = fallback?.takeIf { it.has("max_retries") }?.optInt("max_retries")
                            ?: FallbackStrategy().maxRetries
                    )
                )
            }.getOrElse { PersonaDeploymentManifest.defaults() }
        }

        internal fun manifestFromMeta(meta: MnxMeta): PersonaDeploymentManifest =
            decodePersonaDeploymentManifest(meta.entries[META_PERSONA_DEPLOYMENT_KEY])
    }

    enum class OwnershipBoundary {
        PERSONAL,
        TEAM,
        DEVICE
    }

    enum class WorkspaceScope {
        READ_MIND,
        WRITE_MIND,
        EXPORT_PACK,
        IMPORT_PACK,
        ADMIN
    }

    data class WorkspacePermission(
        val principalId: String,
        val scopes: Set<WorkspaceScope>
    )

    data class MindWorkspace(
        val id: String,
        val name: String,
        val ownership: OwnershipBoundary,
        val permissions: List<WorkspacePermission> = emptyList(),
        val mindIds: List<String> = emptyList(),
        val attributes: Map<String, String> = emptyMap()
    )

    data class NamedMind(
        val id: String,
        val name: String,
        val workspaceId: String,
        val graph: MindGraph
    )

    data class MindWorkspacePack(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val workspaces: List<MindWorkspace>,
        val minds: List<NamedMind>
    )

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

    fun writeGraphToFile(
        graph: MindGraph,
        outFile: File,
        continuityMetadata: ContinuityMetadata? = null,
        personaManifest: PersonaDeploymentManifest = PersonaDeploymentManifest.defaults()
    ) {
        MnxCodec.encodeToFile(buildMnxFile(graph, continuityMetadata, personaManifest), outFile)
    }

    fun buildMnxFile(
        graph: MindGraph,
        continuityMetadata: ContinuityMetadata? = null,
        personaManifest: PersonaDeploymentManifest = PersonaDeploymentManifest.defaults()
    ): MnxFile {
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
            put(META_PERSONA_DEPLOYMENT_KEY, encodePersonaDeploymentManifest(personaManifest))
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
        return mnxFile
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

    fun readPersonaDeploymentManifest(stream: InputStream): PersonaDeploymentManifest {
        val mnxFile = MnxCodec.decode(stream)
        if (!mnxFile.hasSection(MnxFormat.MnxSectionType.META)) {
            return PersonaDeploymentManifest.defaults()
        }
        val meta = MnxCodec.deserializeMeta(mnxFile.sections[MnxFormat.MnxSectionType.META]!!)
        return manifestFromMeta(meta)
    }

    fun getMnxExportsDir(): File = File(context.filesDir, "mnx_exports").also { it.mkdirs() }

    fun listExportedFiles(): List<File> =
        getMnxExportsDir().listFiles { f -> f.extension == "mnx" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun exportWorkspacePack(pack: MindWorkspacePack, fileName: String? = null): File {
        val outDir = File(context.filesDir, "workspace_packs").also { it.mkdirs() }
        val output = if (fileName.isNullOrBlank()) {
            File(outDir, "workspace_pack_${System.currentTimeMillis()}.mnx")
        } else {
            File(outDir, if (fileName.endsWith(".mnx")) fileName else "$fileName.mnx")
        }
        val sections = mapOf(
            MnxFormat.MnxSectionType.META to MnxCodec.serializeMeta(
                MnxMeta(
                    mapOf(
                        "app" to "MnxMindMaker",
                        "pack_type" to "workspace_pack",
                        "pack_version" to pack.version.toString(),
                        "workspace_count" to pack.workspaces.size.toString(),
                        "mind_count" to pack.minds.size.toString()
                    )
                )
            )
        )
        val rawSections = mapOf(WORKSPACE_PACK_SECTION_TYPE to serializeWorkspacePack(pack))
        MnxCodec.encodeToFile(MnxFile(header = MnxHeader(), sections = sections, rawSections = rawSections), output)
        return output
    }

    fun importWorkspacePack(stream: InputStream): MindWorkspacePack {
        val mnxFile = MnxCodec.decode(stream)
        if (mnxFile.hasRawSection(WORKSPACE_PACK_SECTION_TYPE)) {
            return deserializeWorkspacePack(mnxFile.rawSections[WORKSPACE_PACK_SECTION_TYPE]!!)
        }

        throw IllegalArgumentException(
            "MNX file does not contain a workspace pack raw section (type=$WORKSPACE_PACK_SECTION_TYPE)"
        )
    }

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
