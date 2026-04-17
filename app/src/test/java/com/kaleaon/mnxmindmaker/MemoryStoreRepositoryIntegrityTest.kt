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

        repository.putSession(
            SessionMemoryRecord(
                metadata = MemoryRecordMetadata(
                    id = "s1",
                    timestamp = 1L,
                    sensitivity = "low",
                    memoryCategory = MemoryCategory.SESSION
                ),
                role = "user",
                content = "hello"
            )
        )

        File(dir, "memory.json").appendText("\n{ tamper }")

        val report = repository.runIntegrityScan()
        assertFalse(report.isHealthy)
        assertTrue(report.issues.any { it.contains("Checksum mismatch") })
    }

    @Test
    fun restoreLastKnownGoodSnapshot_restoresTamperedPrimaryFile() {
        val dir = Files.createTempDirectory("memory-store-restore").toFile()
        val repository = MemoryStoreRepository(context = tempContext(dir), fileName = "memory.json")

        repository.putSession(
            SessionMemoryRecord(
                metadata = MemoryRecordMetadata(
                    id = "session-a",
                    timestamp = 10L,
                    sensitivity = "low",
                    memoryCategory = MemoryCategory.SESSION
                ),
                role = "assistant",
                content = "stable"
            )
        )

        File(dir, "memory.json").writeText("corrupted payload")

        val restored = repository.restoreLastKnownGoodSnapshot()
        assertTrue(restored)
        assertTrue(repository.getSessions().any { it.metadata.id == "session-a" })
    }

    private fun tempContext(filesDir: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = filesDir
    }
}
