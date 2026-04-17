package com.kaleaon.mnxmindmaker.ui.mindmap

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonaMentionResolverTest {

    @Test
    fun `resolvePersonaIds extracts distinct canonicalized mention handles`() {
        val resolved = PersonaMentionResolver.resolvePersonaIds(
            "Hey @Mentor, can @mentor review this with @Ops-Team and @ops-team?"
        )

        assertEquals(setOf("mentor", "ops-team"), resolved)
    }

    @Test
    fun `resolvePersonaIds ignores non-mention text`() {
        val resolved = PersonaMentionResolver.resolvePersonaIds("No persona mentions here.")

        assertEquals(emptySet<String>(), resolved)
    }
}
