package com.kaleaon.mnxmindmaker.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppManagedKeyHierarchyTest {

    private class FakeStore : KeyHierarchyStore {
        private val values = mutableMapOf<String, String>()
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getString(key: String): String? = values[key]
    }

    @Test
    fun `rotation increments active version while preserving old keys`() {
        val hierarchy = AppManagedKeyHierarchy(FakeStore())
        val first = hierarchy.activeKey()

        val rotatedVersion = hierarchy.rotate()
        val second = hierarchy.activeKey()

        assertEquals(2, rotatedVersion)
        assertEquals(2, second.version)
        assertNotEquals(first.key.toList(), second.key.toList())
        assertArrayEquals(first.key, hierarchy.keyForVersion(1))
        assertArrayEquals(second.key, hierarchy.keyForVersion(2))
    }

    @Test
    fun `encrypted snapshot can recover key hierarchy with passphrase`() {
        val source = AppManagedKeyHierarchy(FakeStore())
        val sourceKey = source.activeKey()
        source.rotate()
        val payload = source.exportEncryptedSnapshot("correct horse battery staple")

        val restored = AppManagedKeyHierarchy(FakeStore())
        restored.importEncryptedSnapshot(payload, "correct horse battery staple")

        val recoveredV1 = restored.keyForVersion(1)
        val recoveredV2 = restored.keyForVersion(2)
        assertNotNull(recoveredV1)
        assertNotNull(recoveredV2)
        assertArrayEquals(sourceKey.key, recoveredV1)
        assertEquals(2, restored.activeKey().version)
    }
}
