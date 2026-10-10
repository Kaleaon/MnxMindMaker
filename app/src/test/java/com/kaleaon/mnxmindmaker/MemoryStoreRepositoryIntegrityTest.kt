package com.kaleaon.mnxmindmaker

import android.content.Context
import android.content.ContextWrapper
import com.kaleaon.mnxmindmaker.util.memory.persistence.MemoryCategory
import com.kaleaon.mnxmindmaker.util.memory.persistence.MemoryRecordMetadata
import com.kaleaon.mnxmindmaker.util.memory.persistence.MemoryStoreRepository
import com.kaleaon.mnxmindmaker.util.memory.persistence.SessionMemoryRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MemoryStoreRepositoryIntegrityTest {

    @Test
    fun runIntegrityScan_detectsChecksumMismatch() {
        val dir = Files.createTempDirectory("memory-store-integrity").toFile()
        val repository = MemoryStoreRepository(context = tempContext(dir), fileName = "memory.json")

        val payload = """{"schemaVersion":1,"createdTimestamp":1,"updatedTimestamp":1,"records":{"sessions":[],"profiles":[],"semantics":[]}}"""
        val primaryFile = File(dir, "memory.json")
        val snapshotFile = File(dir, "memory.json.snapshot")
        val checksumFile = File(dir, "memory.json.sha256")

        primaryFile.writeText(payload)
        snapshotFile.writeText(payload)
        checksumFile.writeText(com.kaleaon.mnxmindmaker.util.HashUtils.sha256Hex(payload))

        primaryFile.appendText("\n{ tamper }")

        val report = repository.runIntegrityScan()
        assertFalse(report.isHealthy)
        assertTrue(report.issues.any { it.contains("Checksum mismatch") })
    }

    @Test
    fun restoreLastKnownGoodSnapshot_restoresTamperedPrimaryFile() {
        val dir = Files.createTempDirectory("memory-store-restore").toFile()
        val repository = MemoryStoreRepository(context = tempContext(dir), fileName = "memory.json")

        val validPayload = """{"schemaVersion":1,"createdTimestamp":10,"updatedTimestamp":10,"records":{"sessions":[{"metadata":{"id":"session-a","timestamp":10,"sensitivity":"low","memoryCategory":"SESSION"},"role":"assistant","content":"stable"}],"profiles":[],"semantics":[]}}"""
        val primaryFile = File(dir, "memory.json")
        val snapshotFile = File(dir, "memory.json.snapshot")
        val checksumFile = File(dir, "memory.json.sha256")

        primaryFile.writeText("corrupted payload")
        snapshotFile.writeText(validPayload)
        checksumFile.writeText(com.kaleaon.mnxmindmaker.util.HashUtils.sha256Hex(validPayload))

        val restored = repository.restoreLastKnownGoodSnapshot()
        assertTrue(restored)
        assertTrue(primaryFile.readText() == validPayload)
    }

    private fun tempContext(filesDir: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = filesDir
    }
}
