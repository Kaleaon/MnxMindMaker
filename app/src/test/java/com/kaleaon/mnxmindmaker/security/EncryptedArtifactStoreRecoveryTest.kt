package com.kaleaon.mnxmindmaker.security

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedArtifactStoreRecoveryTest {

    private class FakeStore : KeyHierarchyStore {
        private val values = mutableMapOf<String, String>()

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getString(key: String): String? = values[key]
    }

    @Test
    fun `recoverHierarchyFromBackup imports recovery payload for valid bundle`() {
        val passphrase = "correct horse battery staple"
        val sourceHierarchy = AppManagedKeyHierarchy(FakeStore())
        val expectedV1 = sourceHierarchy.activeKey().key
        sourceHierarchy.rotate()

        val bundle = JSONObject()
            .put("magic", "MMK-BUNDLE-1")
            .put("recovery", JSONObject(sourceHierarchy.exportEncryptedSnapshot(passphrase)))
            .toString()

        val restoredHierarchy = AppManagedKeyHierarchy(FakeStore())
        val store = EncryptedArtifactStore(restoredHierarchy)

        store.recoverHierarchyFromBackup(bundle, passphrase)

        assertArrayEquals(expectedV1, restoredHierarchy.keyForVersion(1))
        assertTrue(restoredHierarchy.keyForVersion(2) != null)
    }

    @Test
    fun `recoverHierarchyFromBackup rejects wrong or missing magic`() {
        val passphrase = "passphrase"
        val validRecovery = AppManagedKeyHierarchy(FakeStore()).exportEncryptedSnapshot(passphrase)
        val store = EncryptedArtifactStore(AppManagedKeyHierarchy(FakeStore()))

        val wrongMagicBundle = JSONObject()
            .put("magic", "WRONG")
            .put("recovery", JSONObject(validRecovery))
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            store.recoverHierarchyFromBackup(wrongMagicBundle, passphrase)
        }

        val missingMagicBundle = JSONObject()
            .put("recovery", JSONObject(validRecovery))
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            store.recoverHierarchyFromBackup(missingMagicBundle, passphrase)
        }
    }

    @Test
    fun `recoverHierarchyFromBackup rejects missing recovery object`() {
        val store = EncryptedArtifactStore(AppManagedKeyHierarchy(FakeStore()))

        val missingRecoveryBundle = JSONObject()
            .put("magic", "MMK-BUNDLE-1")
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            store.recoverHierarchyFromBackup(missingRecoveryBundle, "passphrase")
        }

        val nonObjectRecovery = JSONObject()
            .put("magic", "MMK-BUNDLE-1")
            .put("recovery", "not-an-object")
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
            store.recoverHierarchyFromBackup(nonObjectRecovery, "passphrase")
        }
    }
}
