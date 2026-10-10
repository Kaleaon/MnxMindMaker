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

    private fun tempContext(filesDir: File): Context {
        val prefsMap = mutableMapOf<String, MutableMap<String, Any?>>()
        return object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val store = prefsMap.getOrPut(name) { mutableMapOf() }
                return FakeSharedPreferences(store)
            }
            override fun getPackageName(): String = "com.kaleaon.mnxmindmaker"
        }
    }

    private class FakeSharedPreferences(
        private val data: MutableMap<String, Any?> = mutableMapOf()
    ) : android.content.SharedPreferences {
        override fun getAll(): Map<String, *> = data
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): android.content.SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class Editor : android.content.SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor { changes[key] = values; return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { changes[key] = value; return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { changes[key] = null; return this }
            override fun clear(): android.content.SharedPreferences.Editor { clearAll = true; return this }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (clearAll) data.clear()
                changes.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                return true
            }
        }
    }
}
