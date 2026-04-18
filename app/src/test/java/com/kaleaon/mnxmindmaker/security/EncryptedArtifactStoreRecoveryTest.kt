package com.kaleaon.mnxmindmaker.security

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `recoverHierarchyFromBackup imports valid recovery bundle`() {
        val passphrase = "correct horse battery staple"
        val sourceHierarchy = AppManagedKeyHierarchy(FakeStore())
        sourceHierarchy.rotate()
        val wrappedHierarchy = sourceHierarchy.exportEncryptedSnapshot(passphrase)
        val bundle = JSONObject()
            .put("magic", "MMK-BUNDLE-1")
            .put("recovery", JSONObject(wrappedHierarchy))
            .toString()

        val targetHierarchy = AppManagedKeyHierarchy(FakeStore())
        val store = EncryptedArtifactStore(targetHierarchy)

        store.recoverHierarchyFromBackup(bundle, passphrase)

        assertNotNull(targetHierarchy.keyForVersion(1))
        assertNotNull(targetHierarchy.keyForVersion(2))
        assertEquals(2, targetHierarchy.activeKey().version)
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
    }
}
