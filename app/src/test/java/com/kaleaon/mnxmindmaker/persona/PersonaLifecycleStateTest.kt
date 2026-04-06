package com.kaleaon.mnxmindmaker.persona

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaLifecycleStateTest {

    @Test
    fun `allows forward lifecycle transitions`() {
        assertTrue(PersonaLifecycleState.DRAFT.canTransitionTo(PersonaLifecycleState.REVIEWED))
        assertTrue(PersonaLifecycleState.REVIEWED.canTransitionTo(PersonaLifecycleState.READY_TO_DEPLOY))
        assertTrue(PersonaLifecycleState.READY_TO_DEPLOY.canTransitionTo(PersonaLifecycleState.DEPLOYED))
        assertTrue(PersonaLifecycleState.DEPLOYED.canTransitionTo(PersonaLifecycleState.ARCHIVED))
    }

    @Test
    fun `rejects invalid lifecycle transitions`() {
        assertFalse(PersonaLifecycleState.DRAFT.canTransitionTo(PersonaLifecycleState.DEPLOYED))
        assertFalse(PersonaLifecycleState.DEPLOYED.canTransitionTo(PersonaLifecycleState.REVIEWED))
        assertFalse(PersonaLifecycleState.ARCHIVED.canTransitionTo(PersonaLifecycleState.DEPLOYED))
    }
}
