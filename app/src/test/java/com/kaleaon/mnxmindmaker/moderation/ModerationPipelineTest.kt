package com.kaleaon.mnxmindmaker.moderation

import com.kaleaon.mnxmindmaker.util.moderation.ModerationAction
import com.kaleaon.mnxmindmaker.util.moderation.ModerationPipeline
import com.kaleaon.mnxmindmaker.util.moderation.ModerationRequest
import com.kaleaon.mnxmindmaker.util.moderation.ModerationStage
import com.kaleaon.mnxmindmaker.util.moderation.SensitiveEntityModerationPolicy
import com.kaleaon.mnxmindmaker.util.moderation.StageActionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationPipelineTest {

    @Test
    fun `prompt policy masks email entities`() {
        val pipeline = ModerationPipeline(listOf(SensitiveEntityModerationPolicy()))
        val result = pipeline.moderate(
            ModerationRequest(
                text = "Contact me: jane@example.com",
                stage = ModerationStage.PROMPT,
                policyId = "prompt"
            )
        )

        assertEquals(ModerationAction.MASK, result.action)
        assertTrue(result.text.contains("[REDACTED:EMAIL]"))
    }

    @Test
    fun `memory write policy denies ssn by default`() {
        val pipeline = ModerationPipeline(listOf(SensitiveEntityModerationPolicy()))
        val result = pipeline.moderate(
            ModerationRequest(
                text = "Store SSN 123-45-6789",
                stage = ModerationStage.MEMORY_WRITE,
                policyId = "memory"
            )
        )

        assertEquals(ModerationAction.DENY, result.action)
        assertTrue(result.detectedEntityTypes.contains("ssn"))
    }

    @Test
    fun `policy specific allow overrides default deny for selected entities`() {
        val policy = SensitiveEntityModerationPolicy(
            stagePolicies = mapOf(
                ModerationStage.MEMORY_WRITE to StageActionPolicy(
                    defaultAction = ModerationAction.DENY,
                    allowEntityTypes = setOf("email")
                )
            )
        )
        val pipeline = ModerationPipeline(listOf(policy))

        val result = pipeline.moderate(
            ModerationRequest(
                text = "Email: team@example.com",
                stage = ModerationStage.MEMORY_WRITE,
                policyId = "policy-allow"
            )
        )

        assertEquals(ModerationAction.ALLOW, result.action)
        assertEquals("Email: team@example.com", result.text)
    }
}
