package com.kaleaon.mnxmindmaker.persona.runtime

import com.kaleaon.mnxmindmaker.util.observability.InMemoryTraceStore
import com.kaleaon.mnxmindmaker.util.observability.TraceEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaRuntimeManagerTest {

    @Test
    fun `manager records deployment activation and invocation with correlation id`() {
        val store = InMemoryTraceStore()
        val manager = PersonaRuntimeManager(store)

        val context = manager.deployPersona(
            requestId = "deploy-1",
            personaVersion = "v1.2.3",
            deploymentManifestHash = "manifest-abc",
            deployDurationMs = 425,
            shouldFail = false
        )
        manager.activatePersona(
            requestId = "activate-1",
            context = context,
            provider = "OPENAI",
            success = true
        )
        manager.recordInvocation(
            requestId = "invoke-1",
            context = context,
            provider = "OPENAI",
            latencyMs = 250,
            success = true,
            errorRate = 0.0
        )

        assertEquals("v1.2.3:manifest-abc", context.correlationId)

        val traces = store.list()
        assertEquals(3, traces.size)

        val allEvents = traces.flatMap { it.events }
        assertTrue(allEvents.any { it.type == TraceEventType.DEPLOY_INITIATED })
        assertTrue(allEvents.any { it.type == TraceEventType.DEPLOY_COMPLETED })
        assertTrue(allEvents.any { it.type == TraceEventType.ACTIVATION_SUCCESS })
        val invocation = allEvents.first { it.type == TraceEventType.INVOCATION_METRIC }
        assertEquals("v1.2.3", invocation.payload["persona_version"])
        assertEquals("manifest-abc", invocation.payload["deployment_manifest_hash"])
        assertEquals("v1.2.3:manifest-abc", invocation.payload["correlation_id"])
    }

    @Test
    fun `manager records deploy and activation failure events`() {
        val store = InMemoryTraceStore()
        val manager = PersonaRuntimeManager(store)

        val context = manager.deployPersona(
            requestId = "deploy-err",
            personaVersion = "v2",
            deploymentManifestHash = "hash2",
            deployDurationMs = 50,
            shouldFail = true,
            failureReason = "upload timeout"
        )
        manager.activatePersona(
            requestId = "activate-err",
            context = context,
            provider = "LOCAL",
            success = false,
            failureReason = "warmup failure"
        )

        val allEvents = store.list().flatMap { it.events }
        assertTrue(allEvents.any { it.type == TraceEventType.DEPLOY_FAILED })
        assertTrue(allEvents.any { it.type == TraceEventType.ACTIVATION_FAILURE })
    }
}
