package com.kaleaon.mnxmindmaker.util.drive

import com.kaleaon.mnxmindmaker.model.MindGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriveSyncClientTest {

    private lateinit var driveSyncClient: DriveSyncClient
    private lateinit var sampleGraph: MindGraph

    @Before
    fun setUp() {
        driveSyncClient = DriveSyncClient()
        sampleGraph = MindGraph(name = "Drive Sync Test Mind")
    }

    @Test
    fun syncGraph_successfulSync() {
        driveSyncClient.setSimulatedState(sessionValid = true, networkHealthy = true, conflictState = false, remoteToken = "v1.0")
        val result = driveSyncClient.syncGraph(sampleGraph, clientVersionToken = "v1.0")

        assertTrue(result is DriveSyncResult.Success)
        val success = result as DriveSyncResult.Success
        assertTrue(success.remoteVersionToken.startsWith("v"))
        assertEquals("Drive Sync Test Mind", success.syncedGraph.name)
    }

    @Test
    fun syncGraph_detectsConflict412() {
        driveSyncClient.setSimulatedState(sessionValid = true, networkHealthy = true, conflictState = false, remoteToken = "v2.0")
        val result = driveSyncClient.syncGraph(sampleGraph, clientVersionToken = "v1.0")

        assertTrue(result is DriveSyncResult.Error)
        val errorResult = result as DriveSyncResult.Error
        assertEquals(DriveSyncErrorKind.CONFLICT, errorResult.syncError.kind)
        assertEquals(412, errorResult.syncError.statusCode)
        assertTrue(errorResult.syncError.title.contains("412"))
    }

    @Test
    fun syncGraph_detectsSessionExpired401() {
        driveSyncClient.setSimulatedState(sessionValid = false)
        val result = driveSyncClient.syncGraph(sampleGraph, clientVersionToken = "v1.0")

        assertTrue(result is DriveSyncResult.Error)
        val errorResult = result as DriveSyncResult.Error
        assertEquals(DriveSyncErrorKind.SESSION_EXPIRED, errorResult.syncError.kind)
        assertEquals(401, errorResult.syncError.statusCode)
    }

    @Test
    fun syncGraph_detectsNetworkError() {
        driveSyncClient.setSimulatedState(sessionValid = true, networkHealthy = false)
        val result = driveSyncClient.syncGraph(sampleGraph, clientVersionToken = "v1.0")

        assertTrue(result is DriveSyncResult.Error)
        val errorResult = result as DriveSyncResult.Error
        assertEquals(DriveSyncErrorKind.NETWORK_ERROR, errorResult.syncError.kind)
    }

    @Test
    fun conflictRecovery_forceOverwrite() {
        driveSyncClient.setSimulatedState(sessionValid = true, conflictState = true)
        val overwriteResult = driveSyncClient.forceOverwrite(sampleGraph)

        assertTrue(overwriteResult is DriveSyncResult.Success)
        val success = overwriteResult as DriveSyncResult.Success
        assertTrue(success.remoteVersionToken.startsWith("v"))
    }

    @Test
    fun conflictRecovery_reloadRemote() {
        val remoteGraph = MindGraph(name = "Remote Version")
        driveSyncClient.setSimulatedState(sessionValid = true, conflictState = true, remoteToken = "v3.0", remoteGraph = remoteGraph)

        val reloadResult = driveSyncClient.reloadRemote()
        assertTrue(reloadResult is DriveSyncResult.Success)
        val success = reloadResult as DriveSyncResult.Success
        assertEquals("v3.0", success.remoteVersionToken)
        assertEquals("Remote Version", success.syncedGraph.name)
    }

    @Test
    fun sessionRecovery_reauthenticate() {
        driveSyncClient.setSimulatedState(sessionValid = false)
        assertTrue(driveSyncClient.reauthenticate())

        val result = driveSyncClient.syncGraph(sampleGraph, clientVersionToken = "v1.0")
        assertTrue(result is DriveSyncResult.Success)
    }
}
