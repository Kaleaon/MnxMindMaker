package com.kaleaon.mnxmindmaker.util.memory.persistence

import android.content.Context
import com.kaleaon.mnxmindmaker.security.EncryptedArtifactStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

class MemoryStoreRepository(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME
    private val baseFileName: String = DEFAULT_FILE_STEM,
    private val remoteSyncLayer: RemoteMemorySyncLayer? = null
) : MemoryStore {

    private val encryptedStore = EncryptedArtifactStore(context)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val storageFile = File(context.filesDir, fileName)
    private val snapshotFile = File(context.filesDir, "$fileName.snapshot")
    private val checksumFile = File(context.filesDir, "$fileName.sha256")
    private var lastIntegrityScanAtMs: Long = 0L
    private val migrations = mutableListOf<MigrationStep>()

    private val graphFile = File(context.filesDir, "$baseFileName.${GRAPH_SUFFIX}.json")
    private val semanticFile = File(context.filesDir, "$baseFileName.${SEMANTIC_SUFFIX}.json")
    private val episodicFile = File(context.filesDir, "$baseFileName.${EPISODIC_SUFFIX}.json")
    private val metadataFile = File(context.filesDir, "$baseFileName.${METADATA_SUFFIX}.json")

    override fun currentSchemaVersion(): Int = SCHEMA_VERSION

    override fun registerMigration(migration: MemoryStoreMigration) {
        synchronized(lock) {
            val nextVersion = migrations.maxOfOrNull { it.toVersion } ?: SCHEMA_VERSION
            migrations += MigrationStep(
                fromVersion = nextVersion,
                toVersion = nextVersion + 1,
                migration = migration
            )
        }
    }

    override fun putSession(record: SessionMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.SESSION || record.metadata.memoryCategory == MemoryCategory.EPISODIC) {
            "Session record metadata category must be SESSION or EPISODIC"
        }
        synchronized(lock) {
            val episodic = loadEpisodicLocked()
            val updated = episodic.episodes.filterNot { it.metadata.id == record.metadata.id } + record
            saveEpisodicLocked(
                episodic.copy(updatedTimestamp = System.currentTimeMillis(), episodes = updated)
            )
            upsertMetadataLocked(record.metadata)
            syncToRemoteLocked()
        }
    }

    override fun putProfile(record: ProfileMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.PROFILE) {
            "Profile record metadata category must be PROFILE"
        }
        synchronized(lock) {
            val graph = loadGraphLocked()
            val updated = graph.profiles.filterNot { it.metadata.id == record.metadata.id } + record
            saveGraphLocked(
                graph.copy(updatedTimestamp = System.currentTimeMillis(), profiles = updated)
            )
            upsertMetadataLocked(record.metadata)
            syncToRemoteLocked()
        }
    }

    override fun putSemantic(record: SemanticMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.SEMANTIC) {
            "Semantic record metadata category must be SEMANTIC"
        }
        synchronized(lock) {
            val semantic = loadSemanticLocked()
            val updated = semantic.semantics.filterNot { it.metadata.id == record.metadata.id } + record
            saveSemanticLocked(
                semantic.copy(updatedTimestamp = System.currentTimeMillis(), semantics = updated)
            )
            upsertMetadataLocked(record.metadata)
            syncToRemoteLocked()
        }
    }

    override fun getSessions(): List<SessionMemoryRecord> = synchronized(lock) {
        loadEpisodicLocked().episodes
    }

    override fun getProfiles(): List<ProfileMemoryRecord> = synchronized(lock) {
        loadGraphLocked().profiles
    }

    override fun getSemantics(): List<SemanticMemoryRecord> = synchronized(lock) {
        loadSemanticLocked().semantics
    }

    override fun deleteRecord(category: MemoryCategory, id: String): Boolean = synchronized(lock) {
        var changed = false
        when (category) {
            MemoryCategory.SESSION, MemoryCategory.EPISODIC -> {
                val episodic = loadEpisodicLocked()
                val updated = episodic.episodes.filterNot { it.metadata.id == id }
                if (updated != episodic.episodes) {
                    saveEpisodicLocked(episodic.copy(updatedTimestamp = System.currentTimeMillis(), episodes = updated))
                    changed = true
                }
            }
            MemoryCategory.PROFILE, MemoryCategory.GRAPH -> {
                val graph = loadGraphLocked()
                val updated = graph.profiles.filterNot { it.metadata.id == id }
                if (updated != graph.profiles) {
                    saveGraphLocked(graph.copy(updatedTimestamp = System.currentTimeMillis(), profiles = updated))
                    changed = true
                }
            }
            MemoryCategory.SEMANTIC -> {
                val semantic = loadSemanticLocked()
                val updated = semantic.semantics.filterNot { it.metadata.id == id }
                if (updated != semantic.semantics) {
                    saveSemanticLocked(semantic.copy(updatedTimestamp = System.currentTimeMillis(), semantics = updated))
                    changed = true
                }
            }
        }

        if (changed) {
            removeMetadataLocked(id)
            syncToRemoteLocked()
        }
        changed
    }

    override fun clearAll() {
        synchronized(lock) {
            saveGraphLocked(defaultGraphStore())
            saveSemanticLocked(defaultSemanticStore())
            saveEpisodicLocked(defaultEpisodicStore())
            saveMetadataLocked(defaultMetadataStore())
            syncToRemoteLocked()
        }
    }

    override fun syncFromRemote(): Boolean = synchronized(lock) {
        val remoteSnapshot = remoteSyncLayer?.pullSnapshot() ?: return@synchronized false
        val localSnapshot = composeLocalSnapshotLocked()
        val merged = mergeRemoteIntoLocal(localSnapshot, remoteSnapshot)
        saveGraphLocked(merged.graphStore)
        saveSemanticLocked(merged.semanticStore)
        saveEpisodicLocked(merged.episodicStore)
        saveMetadataLocked(merged.metadataIndex)
        true
    }

    override fun syncToRemote(): Boolean = synchronized(lock) {
        syncToRemoteLocked()
    }

    private fun syncToRemoteLocked(): Boolean {
        val layer = remoteSyncLayer ?: return false
        layer.pushSnapshot(composeLocalSnapshotLocked())
        return true
    }

    private fun composeLocalSnapshotLocked(): RemoteMemorySnapshot {
        return RemoteMemorySnapshot(
            graphStore = loadGraphLocked(),
            semanticStore = loadSemanticLocked(),
            episodicStore = loadEpisodicLocked(),
            metadataIndex = loadMetadataLocked()
        )
    }

    private fun mergeRemoteIntoLocal(
        local: RemoteMemorySnapshot,
        remote: RemoteMemorySnapshot
    ): RemoteMemorySnapshot {
        val localEntries = local.metadataIndex.entries.associateBy { it.id }
        val remoteEntries = remote.metadataIndex.entries.associateBy { it.id }

        fun preferRemote(id: String): Boolean {
            val localEntry = localEntries[id]
            val remoteEntry = remoteEntries[id]
            if (remoteEntry == null) return false
            if (localEntry == null) return true
            return remoteEntry.lastUpdatedTimestamp > localEntry.lastUpdatedTimestamp
        }

        val mergedProfiles = mergeById(local.graphStore.profiles, remote.graphStore.profiles, preferRemote)
        val mergedSemantics = mergeById(local.semanticStore.semantics, remote.semanticStore.semantics, preferRemote)
        val mergedEpisodes = mergeById(local.episodicStore.episodes, remote.episodicStore.episodes, preferRemote)

        val mergedMetadataEntries = (local.metadataIndex.entries + remote.metadataIndex.entries)
            .groupBy { it.id }
            .map { (_, entries) -> entries.maxBy { it.lastUpdatedTimestamp } }

        return RemoteMemorySnapshot(
            graphStore = local.graphStore.copy(
                updatedTimestamp = maxOf(local.graphStore.updatedTimestamp, remote.graphStore.updatedTimestamp),
                profiles = mergedProfiles
            ),
            semanticStore = local.semanticStore.copy(
                updatedTimestamp = maxOf(local.semanticStore.updatedTimestamp, remote.semanticStore.updatedTimestamp),
                semantics = mergedSemantics
            ),
            episodicStore = local.episodicStore.copy(
                updatedTimestamp = maxOf(local.episodicStore.updatedTimestamp, remote.episodicStore.updatedTimestamp),
                episodes = mergedEpisodes
            ),
            metadataIndex = local.metadataIndex.copy(
                updatedTimestamp = maxOf(local.metadataIndex.updatedTimestamp, remote.metadataIndex.updatedTimestamp),
                entries = mergedMetadataEntries
            )
        )
    }

    private fun <T> mergeById(
        localItems: List<T>,
        remoteItems: List<T>,
        preferRemote: (String) -> Boolean
    ): List<T> where T : Any {
        val localById = localItems.associateBy { itemId(it) }
        val remoteById = remoteItems.associateBy { itemId(it) }
        val allIds = localById.keys + remoteById.keys
        return allIds.mapNotNull { id ->
            if (preferRemote(id)) remoteById[id] else localById[id] ?: remoteById[id]
        }
    }

    override fun runIntegrityScan(): MemoryStoreIntegrityReport = synchronized(lock) {
        runIntegrityScanLocked()
    }

    override fun restoreLastKnownGoodSnapshot(): Boolean = synchronized(lock) {
        if (!snapshotFile.exists()) return@synchronized false
        val snapshotPayload = runCatching { snapshotFile.readText() }.getOrNull() ?: return@synchronized false
        val snapshotChecksum = runCatching { sha256Hex(snapshotPayload) }.getOrNull() ?: return@synchronized false
        val expectedChecksum = runCatching { checksumFile.takeIf(File::exists)?.readText()?.trim() }.getOrNull()
            ?: return@synchronized false
        if (!snapshotChecksum.equals(expectedChecksum, ignoreCase = true)) return@synchronized false
        if (!validatePayloadLocked(snapshotPayload).isHealthy) return@synchronized false
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(snapshotPayload)
        true
    }

    private fun loadStateLocked(): PersistedMemoryStore {
        if (!storageFile.exists()) {
            val initial = defaultState()
            saveStateLocked(initial)
            return initial
    private fun itemId(item: Any): String = when (item) {
        is SessionMemoryRecord -> item.metadata.id
        is ProfileMemoryRecord -> item.metadata.id
        is SemanticMemoryRecord -> item.metadata.id
        else -> throw IllegalArgumentException("Unsupported memory record type: ${item::class.java.simpleName}")
    }

    private fun loadGraphLocked(): GraphMemoryStore {
        return loadStoreLocked(
            file = graphFile,
            default = defaultGraphStore(),
            decode = { payload -> json.decodeFromString<GraphMemoryStore>(payload.toString()) },
            encode = { state -> json.encodeToString(state) }
        )
    }

    private fun loadSemanticLocked(): SemanticMemoryStore {
        return loadStoreLocked(
            file = semanticFile,
            default = defaultSemanticStore(),
            decode = { payload -> json.decodeFromString<SemanticMemoryStore>(payload.toString()) },
            encode = { state -> json.encodeToString(state) }
        )
    }

    private fun loadEpisodicLocked(): EpisodicTimelineStore {
        return loadStoreLocked(
            file = episodicFile,
            default = defaultEpisodicStore(),
            decode = { payload -> json.decodeFromString<EpisodicTimelineStore>(payload.toString()) },
            encode = { state -> json.encodeToString(state) }
        )
    }

    private fun loadMetadataLocked(): MetadataIndexStore {
        return loadStoreLocked(
            file = metadataFile,
            default = defaultMetadataStore(),
            decode = { payload -> json.decodeFromString<MetadataIndexStore>(payload.toString()) },
            encode = { state -> json.encodeToString(state) }
        )
    }

    private fun saveGraphLocked(state: GraphMemoryStore) {
        saveStoreLocked(graphFile, json.encodeToString(state))
    }

    private fun saveSemanticLocked(state: SemanticMemoryStore) {
        saveStoreLocked(semanticFile, json.encodeToString(state))
    }

    private fun saveEpisodicLocked(state: EpisodicTimelineStore) {
        saveStoreLocked(episodicFile, json.encodeToString(state))
    }

    private fun saveMetadataLocked(state: MetadataIndexStore) {
        saveStoreLocked(metadataFile, json.encodeToString(state))
    }

    private fun saveStoreLocked(file: File, payload: String) {
        file.parentFile?.mkdirs()
        file.writeText(payload)
    }

    private fun <T> loadStoreLocked(
        file: File,
        default: T,
        decode: (JsonObject) -> T,
        encode: (T) -> String
    ): T {
        if (!file.exists()) {
            saveStoreLocked(file, encode(default))
            return default
        }

        maybeRunPeriodicIntegrityScanLocked()

        val restoredFromSnapshot = verifyChecksumLocked()
        if (restoredFromSnapshot) {
            return recoverFromSnapshotLocked() ?: recoverFromCorruptionLocked()
        }

        return try {
            val raw = String(encryptedStore.readDecryptedBytes(storageFile, "memory_index"))
            val raw = file.readText()
            val payload = json.parseToJsonElement(raw).jsonObject
            val payloadVersion = payload[SCHEMA_VERSION_FIELD]?.jsonPrimitive?.intOrNull ?: 1
            val migratedPayload = applyMigrationsLocked(payload, payloadVersion)
            decode(migratedPayload)
        } catch (_: SerializationException) {
            saveStoreLocked(file, encode(default))
            default
        } catch (_: IllegalArgumentException) {
            saveStoreLocked(file, encode(default))
            default
        }
    }

    private fun saveStateLocked(state: PersistedMemoryStore) {
        storageFile.parentFile?.mkdirs()
        val payload = json.encodeToString(state)
        storageFile.writeText(payload)
        snapshotFile.writeText(payload)
        checksumFile.writeText(sha256Hex(payload))
        encryptedStore.writeEncryptedBytes(storageFile, json.encodeToString(state).toByteArray(), "memory_index")
    private fun defaultGraphStore(now: Long = System.currentTimeMillis()): GraphMemoryStore {
        return GraphMemoryStore(
            schemaVersion = SCHEMA_VERSION,
            createdTimestamp = now,
            updatedTimestamp = now
        )
    }

    private fun defaultSemanticStore(now: Long = System.currentTimeMillis()): SemanticMemoryStore {
        return SemanticMemoryStore(
            schemaVersion = SCHEMA_VERSION,
            createdTimestamp = now,
            updatedTimestamp = now
        )
    }

    private fun defaultEpisodicStore(now: Long = System.currentTimeMillis()): EpisodicTimelineStore {
        return EpisodicTimelineStore(
            schemaVersion = SCHEMA_VERSION,
            createdTimestamp = now,
            updatedTimestamp = now
        )
    }

    private fun defaultMetadataStore(now: Long = System.currentTimeMillis()): MetadataIndexStore {
        return MetadataIndexStore(
            schemaVersion = SCHEMA_VERSION,
            createdTimestamp = now,
            updatedTimestamp = now
        )
    }

    private fun upsertMetadataLocked(metadata: MemoryRecordMetadata) {
        val store = loadMetadataLocked()
        val entry = MetadataIndexEntry(
            id = metadata.id,
            category = metadata.memoryCategory,
            lastUpdatedTimestamp = metadata.timestamp,
            sensitivity = metadata.sensitivity,
            routingTags = metadata.routingTags,
            localRevision = (store.entries.firstOrNull { it.id == metadata.id }?.localRevision ?: 0) + 1
        )
        val updatedEntries = store.entries.filterNot { it.id == metadata.id } + entry
        saveMetadataLocked(store.copy(updatedTimestamp = System.currentTimeMillis(), entries = updatedEntries))
    }

    private fun removeMetadataLocked(id: String) {
        val store = loadMetadataLocked()
        val updatedEntries = store.entries.filterNot { it.id == id }
        if (updatedEntries != store.entries) {
            saveMetadataLocked(store.copy(updatedTimestamp = System.currentTimeMillis(), entries = updatedEntries))
        }
    }

    private fun applyMigrationsLocked(payload: JsonObject, payloadVersion: Int): JsonObject {
        var currentVersion = payloadVersion
        var currentPayload = payload

        while (currentVersion < SCHEMA_VERSION) {
            val migrationStep = migrations.firstOrNull { it.fromVersion == currentVersion }
                ?: return currentPayload
            currentPayload = migrationStep.migration.migrate(currentPayload)
            currentVersion = migrationStep.toVersion
        }

        return currentPayload
    }

    private fun recoverFromCorruptionLocked(): PersistedMemoryStore {
        return recoverFromSnapshotLocked() ?: defaultState().also { saveStateLocked(it) }
    }

    private fun verifyChecksumLocked(): Boolean {
        if (!checksumFile.exists() || !storageFile.exists()) return false
        val expectedChecksum = runCatching { checksumFile.readText().trim() }.getOrNull() ?: return false
        if (expectedChecksum.isBlank()) return true
        val currentPayload = runCatching { storageFile.readText() }.getOrNull() ?: return true
        val currentChecksum = runCatching { sha256Hex(currentPayload) }.getOrNull() ?: return true
        return !currentChecksum.equals(expectedChecksum, ignoreCase = true)
    }

    private fun maybeRunPeriodicIntegrityScanLocked() {
        val now = System.currentTimeMillis()
        if (now - lastIntegrityScanAtMs < INTEGRITY_SCAN_INTERVAL_MS) return
        val report = runIntegrityScanLocked()
        lastIntegrityScanAtMs = now
        if (!report.isHealthy) {
            recoverFromSnapshotLocked()
        }
    }

    private fun runIntegrityScanLocked(): MemoryStoreIntegrityReport {
        val issues = mutableListOf<String>()
        if (!storageFile.exists()) {
            issues += "Primary store file is missing."
            return MemoryStoreIntegrityReport(
                isHealthy = false,
                issues = issues
            )
        }
        if (!checksumFile.exists()) {
            issues += "Checksum file is missing."
        } else {
            val payload = runCatching { storageFile.readText() }.getOrElse {
                issues += "Primary store cannot be read."
                ""
            }
            if (payload.isNotEmpty()) {
                val expectedChecksum = runCatching { checksumFile.readText().trim() }.getOrElse {
                    issues += "Checksum cannot be read."
                    ""
                }
                if (expectedChecksum.isNotBlank()) {
                    val actualChecksum = runCatching { sha256Hex(payload) }.getOrElse {
                        issues += "Checksum cannot be computed."
                        ""
                    }
                    if (actualChecksum.isNotBlank() && !actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                        issues += "Checksum mismatch detected."
                    }
                }
                val payloadValidation = validatePayloadLocked(payload)
                issues += payloadValidation.issues
            }
        }

        if (!snapshotFile.exists()) {
            issues += "Last known-good snapshot file is missing."
        }
        return MemoryStoreIntegrityReport(
            isHealthy = issues.isEmpty(),
            issues = issues
        )
    }

    private fun validatePayloadLocked(raw: String): MemoryStoreIntegrityReport {
        val issues = mutableListOf<String>()
        val payloadObject = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            issues += "Serialized state is not valid JSON."
            return MemoryStoreIntegrityReport(isHealthy = false, issues = issues)
        }
        val payloadVersion = payloadObject[SCHEMA_VERSION_FIELD]?.jsonPrimitive?.intOrNull ?: 1
        if (payloadVersion > SCHEMA_VERSION) {
            issues += "Serialized state schemaVersion=$payloadVersion is newer than supported $SCHEMA_VERSION."
        }
        val migratedPayload = applyMigrationsLocked(payloadObject, payloadVersion)
        val decodedState = runCatching { json.decodeFromString<PersistedMemoryStore>(migratedPayload.toString()) }
            .getOrElse {
                issues += "Serialized state cannot be decoded to schema model."
                return MemoryStoreIntegrityReport(isHealthy = false, issues = issues)
            }
        if (decodedState.schemaVersion != SCHEMA_VERSION) {
            issues += "Decoded state schemaVersion=${decodedState.schemaVersion} does not match expected $SCHEMA_VERSION."
        }
        issues += validateReferenceConsistency(decodedState)
        return MemoryStoreIntegrityReport(
            isHealthy = issues.isEmpty(),
            issues = issues
        )
    }

    private fun validateReferenceConsistency(state: PersistedMemoryStore): List<String> {
        val issues = mutableListOf<String>()
        val duplicateSessionIds = state.records.sessions
            .groupBy { it.metadata.id }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateSessionIds.isNotEmpty()) {
            issues += "Duplicate session record IDs: ${duplicateSessionIds.joinToString(",")}."
        }
        val duplicateProfileIds = state.records.profiles
            .groupBy { it.metadata.id }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateProfileIds.isNotEmpty()) {
            issues += "Duplicate profile record IDs: ${duplicateProfileIds.joinToString(",")}."
        }
        val duplicateSemanticIds = state.records.semantics
            .groupBy { it.metadata.id }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateSemanticIds.isNotEmpty()) {
            issues += "Duplicate semantic record IDs: ${duplicateSemanticIds.joinToString(",")}."
        }
        if (state.records.sessions.any { it.metadata.memoryCategory != MemoryCategory.SESSION }) {
            issues += "Reference consistency failure: SESSION collection contains non-session metadata."
        }
        if (state.records.profiles.any { it.metadata.memoryCategory != MemoryCategory.PROFILE }) {
            issues += "Reference consistency failure: PROFILE collection contains non-profile metadata."
        }
        if (state.records.semantics.any { it.metadata.memoryCategory != MemoryCategory.SEMANTIC }) {
            issues += "Reference consistency failure: SEMANTIC collection contains non-semantic metadata."
        }
        if (state.records.sessions.any { it.metadata.id.isBlank() } ||
            state.records.profiles.any { it.metadata.id.isBlank() } ||
            state.records.semantics.any { it.metadata.id.isBlank() }
        ) {
            issues += "Reference consistency failure: found blank record IDs."
        }
        return issues
    }

    private fun recoverFromSnapshotLocked(): PersistedMemoryStore? {
        if (!snapshotFile.exists()) return null
        val snapshotPayload = runCatching { snapshotFile.readText() }.getOrNull() ?: return null
        val snapshotChecksum = runCatching { sha256Hex(snapshotPayload) }.getOrNull() ?: return null
        val expectedChecksum = runCatching { checksumFile.takeIf(File::exists)?.readText()?.trim() }.getOrNull()
            ?: return null
        if (!snapshotChecksum.equals(expectedChecksum, ignoreCase = true)) return null
        val validation = validatePayloadLocked(snapshotPayload)
        if (!validation.isHealthy) return null
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(snapshotPayload)
        return runCatching { json.decodeFromString<PersistedMemoryStore>(snapshotPayload) }.getOrNull()
    }

    private fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DEFAULT_FILE_STEM = "memory_store"
        private const val GRAPH_SUFFIX = "graph"
        private const val SEMANTIC_SUFFIX = "semantic"
        private const val EPISODIC_SUFFIX = "episodic"
        private const val METADATA_SUFFIX = "metadata_index"
        private const val SCHEMA_VERSION_FIELD = "schemaVersion"
        private const val SCHEMA_VERSION = 1
        private const val INTEGRITY_SCAN_INTERVAL_MS = 15 * 60 * 1000L
        private const val SCHEMA_VERSION = 2
    }
}
