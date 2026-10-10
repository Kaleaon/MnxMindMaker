package com.kaleaon.mnxmindmaker.security

import org.json.JSONObject
<<<<<<< HEAD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
=======
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
>>>>>>> a3d2a5d (fix(ci): resolve Kotlin compilation errors across debug, test, and androidTest source sets)
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
<<<<<<< HEAD
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
=======
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
>>>>>>> a3d2a5d (fix(ci): resolve Kotlin compilation errors across debug, test, and androidTest source sets)
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

<<<<<<< HEAD
        val nonObjectRecovery = JSONObject()
=======
        val nonObjectRecoveryBundle = JSONObject()
>>>>>>> a3d2a5d (fix(ci): resolve Kotlin compilation errors across debug, test, and androidTest source sets)
            .put("magic", "MMK-BUNDLE-1")
            .put("recovery", "not-an-object")
            .toString()
        assertThrows(IllegalArgumentException::class.java) {
<<<<<<< HEAD
            store.recoverHierarchyFromBackup(nonObjectRecovery, "passphrase")
=======
            store.recoverHierarchyFromBackup(nonObjectRecoveryBundle, "passphrase")
>>>>>>> a3d2a5d (fix(ci): resolve Kotlin compilation errors across debug, test, and androidTest source sets)
        }
    }
}
