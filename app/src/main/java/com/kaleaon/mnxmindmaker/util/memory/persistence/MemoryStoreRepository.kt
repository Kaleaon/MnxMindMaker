package com.kaleaon.mnxmindmaker.util.memory.persistence

import android.content.Context
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
) : MemoryStore {

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
        require(record.metadata.memoryCategory == MemoryCategory.SESSION) {
            "Session record metadata category must be SESSION"
        }
        synchronized(lock) {
            val state = loadStateLocked()
            val updated = state.records.sessions
                .filterNot { it.metadata.id == record.metadata.id } + record
            saveStateLocked(
                state.copy(
                    updatedTimestamp = System.currentTimeMillis(),
                    records = state.records.copy(sessions = updated)
                )
            )
        }
    }

    override fun putProfile(record: ProfileMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.PROFILE) {
            "Profile record metadata category must be PROFILE"
        }
        synchronized(lock) {
            val state = loadStateLocked()
            val updated = state.records.profiles
                .filterNot { it.metadata.id == record.metadata.id } + record
            saveStateLocked(
                state.copy(
                    updatedTimestamp = System.currentTimeMillis(),
                    records = state.records.copy(profiles = updated)
                )
            )
        }
    }

    override fun putSemantic(record: SemanticMemoryRecord) {
        require(record.metadata.memoryCategory == MemoryCategory.SEMANTIC) {
            "Semantic record metadata category must be SEMANTIC"
        }
        synchronized(lock) {
            val state = loadStateLocked()
            val updated = state.records.semantics
                .filterNot { it.metadata.id == record.metadata.id } + record
            saveStateLocked(
                state.copy(
                    updatedTimestamp = System.currentTimeMillis(),
                    records = state.records.copy(semantics = updated)
                )
            )
        }
    }

    override fun getSessions(): List<SessionMemoryRecord> = synchronized(lock) {
        loadStateLocked().records.sessions
    }

    override fun getProfiles(): List<ProfileMemoryRecord> = synchronized(lock) {
        loadStateLocked().records.profiles
    }

    override fun getSemantics(): List<SemanticMemoryRecord> = synchronized(lock) {
        loadStateLocked().records.semantics
    }

    override fun deleteRecord(category: MemoryCategory, id: String): Boolean = synchronized(lock) {
        val state = loadStateLocked()
        val updatedRecords = when (category) {
            MemoryCategory.SESSION -> state.records.copy(
                sessions = state.records.sessions.filterNot { it.metadata.id == id }
            )
            MemoryCategory.PROFILE -> state.records.copy(
                profiles = state.records.profiles.filterNot { it.metadata.id == id }
            )
            MemoryCategory.SEMANTIC -> state.records.copy(
                semantics = state.records.semantics.filterNot { it.metadata.id == id }
            )
        }

        if (updatedRecords == state.records) return@synchronized false
        saveStateLocked(
            state.copy(
                updatedTimestamp = System.currentTimeMillis(),
                records = updatedRecords
            )
        )
        true
    }

    override fun clearAll() {
        synchronized(lock) {
            saveStateLocked(defaultState())
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
        }

        maybeRunPeriodicIntegrityScanLocked()

        val restoredFromSnapshot = verifyChecksumLocked()
        if (restoredFromSnapshot) {
            return recoverFromSnapshotLocked() ?: recoverFromCorruptionLocked()
        }

        return try {
            val raw = storageFile.readText()
            val payload = json.parseToJsonElement(raw).jsonObject
            val payloadVersion = payload[SCHEMA_VERSION_FIELD]?.jsonPrimitive?.intOrNull ?: 1
            val migratedPayload = applyMigrationsLocked(payload, payloadVersion)
            val decoded = json.decodeFromString<PersistedMemoryStore>(migratedPayload.toString())
            if (decoded.schemaVersion < SCHEMA_VERSION) {
                val upgraded = decoded.copy(
                    schemaVersion = SCHEMA_VERSION,
                    updatedTimestamp = System.currentTimeMillis()
                )
                saveStateLocked(upgraded)
                upgraded
            } else {
                decoded
            }
        } catch (_: SerializationException) {
            recoverFromCorruptionLocked()
        } catch (_: IllegalArgumentException) {
            recoverFromCorruptionLocked()
        }
    }

    private fun saveStateLocked(state: PersistedMemoryStore) {
        storageFile.parentFile?.mkdirs()
        val payload = json.encodeToString(state)
        storageFile.writeText(payload)
        snapshotFile.writeText(payload)
        checksumFile.writeText(sha256Hex(payload))
    }

    private fun defaultState(now: Long = System.currentTimeMillis()): PersistedMemoryStore {
        return PersistedMemoryStore(
            schemaVersion = SCHEMA_VERSION,
            createdTimestamp = now,
            updatedTimestamp = now,
            records = MemoryRecordCollections()
        )
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
        private const val DEFAULT_FILE_NAME = "memory_store.json"
        private const val SCHEMA_VERSION_FIELD = "schemaVersion"
        private const val SCHEMA_VERSION = 1
        private const val INTEGRITY_SCAN_INTERVAL_MS = 15 * 60 * 1000L
    }
}
