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
import com.kaleaon.mnxmindmaker.interchange.MindInterchangeFormat
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
import java.util.UUID
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
        internal const val META_LEGACY_PERSONA_DEPLOYMENT_KEY = "persona_deployment"
        internal const val META_SCHEMA_VERSION_KEY = "mindmaker_schema_version"
        internal const val LATEST_SCHEMA_VERSION = 4

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

    data class MigrationConflict(
        val code: String,
        val message: String,
        val severity: Severity
    ) {
        enum class Severity { INFO, WARNING, ERROR }
    }

    data class MigrationChange(
        val fromVersion: Int,
        val toVersion: Int,
        val description: String
    )

    data class ArtifactMigrationReport(
        val initialVersion: Int,
        val targetVersion: Int,
        val appliedChanges: List<MigrationChange>,
        val conflicts: List<MigrationConflict>,
        val rollbackToken: String?,
        val migratedFile: MnxFile
    ) {
        val hasConflicts: Boolean = conflicts.isNotEmpty()
        val hasErrorConflicts: Boolean = conflicts.any { it.severity == MigrationConflict.Severity.ERROR }
        val changed: Boolean = appliedChanges.isNotEmpty()
    }

    private val rollbackSnapshots = mutableMapOf<String, MnxFile>()

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
            put(META_SCHEMA_VERSION_KEY, LATEST_SCHEMA_VERSION.toString())
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

        return MnxFile(
            header = MnxHeader(
                createdTimestamp = graph.createdAt,
                modifiedTimestamp = graph.modifiedAt
            ),
            sections = sections,
            rawSections = rawSections
        )
    }

    fun previewArtifactMigration(stream: InputStream): ArtifactMigrationReport {
        val original = MnxCodec.decode(stream)
        return migrateArtifactInternal(original, dryRun = true)
    }

    fun migrateArtifact(stream: InputStream, dryRun: Boolean = false): ArtifactMigrationReport {
        val original = MnxCodec.decode(stream)
        return migrateArtifactInternal(original, dryRun)
    }

    fun rollbackArtifact(token: String): MnxFile? = synchronized(rollbackSnapshots) {
        rollbackSnapshots.remove(token)
        val outDir = File(context.filesDir, "mnx_exports")
        outDir.mkdirs()
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.mnx")
        MnxCodec.encodeToFile(mnxFile, outFile)
        return mnxFile
    }

    /**
     * Import a [MindGraph] from a .mnx [InputStream].
     * Reconstructs the graph from the latest schema, migrating older artifacts on-the-fly.
     */
    fun importFromMnx(stream: InputStream): MindGraph {
        val report = migrateArtifact(stream, dryRun = false)
        val mnxFile = report.migratedFile
        return if (mnxFile.hasRawSection(GRAPH_PAYLOAD_SECTION_TYPE)) {
            deserializeGraphPayload(mnxFile.rawSections[GRAPH_PAYLOAD_SECTION_TYPE]!!)
        } else {
            reconstructGraphFromSections(mnxFile)
        }
    }

    fun readPersonaDeploymentManifest(stream: InputStream): PersonaDeploymentManifest {
        val report = migrateArtifact(stream, dryRun = false)
        val mnxFile = report.migratedFile
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

    private fun migrateArtifactInternal(original: MnxFile, dryRun: Boolean): ArtifactMigrationReport {
        val changes = mutableListOf<MigrationChange>()
        val conflicts = mutableListOf<MigrationConflict>()
        val originalVersion = schemaVersion(original)

        var working = original
        var currentVersion = originalVersion
        while (currentVersion < LATEST_SCHEMA_VERSION) {
            when (currentVersion) {
                1 -> {
                    val result = ensureRawPayloadMigration(working)
                    working = result.first
                    conflicts += result.second
                    changes += MigrationChange(1, 2, "Synthesized raw graph payload from legacy sections")
                    currentVersion = 2
                }
                2 -> {
                    val result = migratePersonaMetaKey(working)
                    working = result.first
                    conflicts += result.second
                    changes += MigrationChange(2, 3, "Migrated legacy persona meta key to namespaced schema key")
                    currentVersion = 3
                }
                3 -> {
                    val result = normalizeGraphPayload(working)
                    working = result.first
                    conflicts += result.second
                    changes += MigrationChange(3, 4, "Normalized graph payload (dedupe ids and prune dangling edges)")
                    currentVersion = 4
                }
                else -> {
                    conflicts += MigrationConflict(
                        code = "unsupported_schema_version",
                        message = "Artifact schema version $currentVersion is unsupported",
                        severity = MigrationConflict.Severity.ERROR
                    )
                    break
                }
            }
        }

        val withVersion = setSchemaVersion(working, LATEST_SCHEMA_VERSION)
        val rollbackToken = if (!dryRun && changes.isNotEmpty()) {
            UUID.randomUUID().toString().also { token ->
                synchronized(rollbackSnapshots) {
                    rollbackSnapshots[token] = original
                }
            }
        } else {
            null
        }

        return ArtifactMigrationReport(
            initialVersion = originalVersion,
            targetVersion = LATEST_SCHEMA_VERSION,
            appliedChanges = changes,
            conflicts = conflicts,
            rollbackToken = rollbackToken,
            migratedFile = if (dryRun) original else withVersion
        )
    }

    private fun schemaVersion(file: MnxFile): Int {
        if (!file.hasSection(MnxFormat.MnxSectionType.META)) {
            return if (file.hasRawSection(GRAPH_PAYLOAD_SECTION_TYPE)) 2 else 1
        }
        val meta = MnxCodec.deserializeMeta(file.sections[MnxFormat.MnxSectionType.META]!!)
        val explicit = meta.entries[META_SCHEMA_VERSION_KEY]?.toIntOrNull()
        if (explicit != null) return explicit
        if (meta.entries.containsKey(META_LEGACY_PERSONA_DEPLOYMENT_KEY)) return 2
        if (file.hasRawSection(GRAPH_PAYLOAD_SECTION_TYPE)) return 3
        return 1
    }

    private fun ensureRawPayloadMigration(file: MnxFile): Pair<MnxFile, List<MigrationConflict>> {
        if (file.hasRawSection(GRAPH_PAYLOAD_SECTION_TYPE)) return file to emptyList()
        val reconstructed = reconstructGraphFromSections(file)
        val raw = file.rawSections.toMutableMap()
        raw[GRAPH_PAYLOAD_SECTION_TYPE] = serializeGraphPayload(reconstructed)
        val conflicts = listOf(
            MigrationConflict(
                code = "legacy_sections_only",
                message = "Artifact had no raw graph payload; reconstructed from sections (edge fidelity may be reduced)",
                severity = MigrationConflict.Severity.WARNING
            )
        )
        return file.copy(rawSections = raw) to conflicts
    }

    private fun migratePersonaMetaKey(file: MnxFile): Pair<MnxFile, List<MigrationConflict>> {
        if (!file.hasSection(MnxFormat.MnxSectionType.META)) return file to emptyList()
        val meta = MnxCodec.deserializeMeta(file.sections[MnxFormat.MnxSectionType.META]!!)
        val entries = meta.entries.toMutableMap()
        val conflicts = mutableListOf<MigrationConflict>()
        val legacy = entries[META_LEGACY_PERSONA_DEPLOYMENT_KEY]
        val modern = entries[META_PERSONA_DEPLOYMENT_KEY]

        if (modern == null && !legacy.isNullOrBlank()) {
            entries[META_PERSONA_DEPLOYMENT_KEY] = legacy
        } else if (!legacy.isNullOrBlank() && !modern.isNullOrBlank() && legacy != modern) {
            conflicts += MigrationConflict(
                code = "persona_meta_conflict",
                message = "Both legacy and namespaced persona manifest keys exist with different values; preserved namespaced key",
                severity = MigrationConflict.Severity.WARNING
            )
        }
        entries.remove(META_LEGACY_PERSONA_DEPLOYMENT_KEY)
        val sections = file.sections.toMutableMap()
        sections[MnxFormat.MnxSectionType.META] = MnxCodec.serializeMeta(MnxMeta(entries))
        return file.copy(sections = sections) to conflicts
    }

    private fun normalizeGraphPayload(file: MnxFile): Pair<MnxFile, List<MigrationConflict>> {
        if (!file.hasRawSection(GRAPH_PAYLOAD_SECTION_TYPE)) return file to emptyList()
        val graph = deserializeGraphPayload(file.rawSections[GRAPH_PAYLOAD_SECTION_TYPE]!!)
        val conflicts = mutableListOf<MigrationConflict>()

        val idCounts = mutableMapOf<String, Int>()
        val remap = mutableMapOf<String, String>()
        val normalizedNodes = graph.nodes.map { node ->
            val count = idCounts.getOrDefault(node.id, 0)
            if (count == 0) {
                idCounts[node.id] = 1
                node
            } else {
                val newId = "${node.id}#${count + 1}"
                idCounts[node.id] = count + 1
                remap[node.id] = newId
                conflicts += MigrationConflict(
                    code = "duplicate_node_id",
                    message = "Node id '${node.id}' duplicated; remapped one instance to '$newId'",
                    severity = MigrationConflict.Severity.WARNING
                )
                node.copy(id = newId)
            }
        }.map { node ->
            val parent = node.parentId?.let { remap[it] ?: it }
            if (parent != node.parentId) node.copy(parentId = parent) else node
        }

        val validNodeIds = normalizedNodes.map { it.id }.toSet()
        val normalizedEdges = graph.edges.mapNotNull { edge ->
            val from = remap[edge.fromNodeId] ?: edge.fromNodeId
            val to = remap[edge.toNodeId] ?: edge.toNodeId
            if (from !in validNodeIds || to !in validNodeIds) {
                conflicts += MigrationConflict(
                    code = "dangling_edge",
                    message = "Removed edge '${edge.id}' because it references missing nodes",
                    severity = MigrationConflict.Severity.WARNING
                )
                null
            } else if (from != edge.fromNodeId || to != edge.toNodeId) {
                edge.copy(fromNodeId = from, toNodeId = to)
            } else {
                edge
            }
        }

        val normalizedGraph = graph.copy(
            nodes = normalizedNodes.toMutableList(),
            edges = normalizedEdges.toMutableList(),
            modifiedAt = maxOf(graph.modifiedAt, System.currentTimeMillis())
        )

        val raw = file.rawSections.toMutableMap()
        raw[GRAPH_PAYLOAD_SECTION_TYPE] = serializeGraphPayload(normalizedGraph)
        return file.copy(rawSections = raw) to conflicts
    }

    private fun setSchemaVersion(file: MnxFile, version: Int): MnxFile {
        val sections = file.sections.toMutableMap()
        val existingEntries = if (file.hasSection(MnxFormat.MnxSectionType.META)) {
            MnxCodec.deserializeMeta(file.sections[MnxFormat.MnxSectionType.META]!!).entries
        } else {
            emptyMap()
        }
        val entries = existingEntries.toMutableMap().apply {
            put("app", get("app") ?: "MnxMindMaker")
            put(META_SCHEMA_VERSION_KEY, version.toString())
        }
        sections[MnxFormat.MnxSectionType.META] = MnxCodec.serializeMeta(MnxMeta(entries))
        return file.copy(sections = sections)
    }

    private fun reconstructGraphFromSections(mnxFile: MnxFile): MindGraph {
        var graphName = "Untitled Mind"
        val nodes = mutableListOf<MindNode>()
        val edges = mutableListOf<MindEdge>()

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

        if (mnxFile.hasSection(MnxFormat.MnxSectionType.META)) {
            val meta = MnxCodec.deserializeMeta(
                mnxFile.sections[MnxFormat.MnxSectionType.META]!!
            )
            if (graphName == "Untitled Mind") {
                graphName = meta.entries["graph_name"] ?: graphName
            }
        }

        val restoredDims: Map<String, Map<String, Float>> =
            if (mnxFile.hasSection(MnxFormat.MnxSectionType.DIMENSIONAL_REFS)) {
                val dimRefs = MnxCodec.deserializeDimensionalRefs(
                    mnxFile.sections[MnxFormat.MnxSectionType.DIMENSIONAL_REFS]!!
                )
                DimensionMapper.restoreDimensions(dimRefs)
            } else {
                emptyMap()
            }

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

    fun exportToInterchangeJson(
        graph: MindGraph,
        metadata: Map<String, String> = emptyMap(),
        compatibilityHooks: MindInterchangeFormat.CompatibilityHooks =
            MindInterchangeFormat.CompatibilityHooks()
    ): File {
        val outDir = File(context.filesDir, "interchange_exports").also { it.mkdirs() }
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.mnxj")
        val json = MindInterchangeFormat.exportJson(graph, compatibilityHooks, metadata)
        outFile.writeText(json)
        return outFile
    }

    fun importFromInterchangeJson(stream: InputStream): MindGraph {
        val json = stream.bufferedReader().use { it.readText() }
        return MindInterchangeFormat.importJson(json)
    }

    fun exportToInterchangeBundle(
        graph: MindGraph,
        blobs: Map<String, ByteArray> = emptyMap(),
        metadata: Map<String, String> = emptyMap(),
        compatibilityHooks: MindInterchangeFormat.CompatibilityHooks =
            MindInterchangeFormat.CompatibilityHooks()
    ): File {
        val outDir = File(context.filesDir, "interchange_exports").also { it.mkdirs() }
        val safeName = graph.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.mnxb")
        val bytes = MindInterchangeFormat.exportBundle(
            MindInterchangeFormat.BundlePayload(graph = graph, blobs = blobs, metadata = metadata),
            compatibilityHooks
        )
        outFile.writeBytes(bytes)
        return outFile
    }

    fun importFromInterchangeBundle(stream: InputStream): MindInterchangeFormat.BundlePayload {
        return MindInterchangeFormat.importBundle(stream.readBytes())
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
