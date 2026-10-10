package com.kaleaon.mnxmindmaker.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.model.ExternalProvider
import com.kaleaon.mnxmindmaker.model.LlmProvider
import com.kaleaon.mnxmindmaker.model.LlmSettings
import com.kaleaon.mnxmindmaker.model.PrivacyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `getDraftSettings loads default settings for provider`() {
        val anthropicDraft = viewModel.getDraftSettings(LlmProvider.ANTHROPIC)
        assertNotNull(anthropicDraft)
        assertEquals(LlmProvider.ANTHROPIC, anthropicDraft.provider)

        val localDraft = viewModel.getDraftSettings(LlmProvider.LOCAL_ON_DEVICE)
        assertNotNull(localDraft)
        assertEquals(LlmProvider.LOCAL_ON_DEVICE, localDraft.provider)
    }

    @Test
    fun `updateDraftSettings preserves unsaved user input across tab switches`() {
        val original = viewModel.getDraftSettings(LlmProvider.OPENAI)
        val modified = original.copy(
            apiKey = "sk-test-draft-key-12345",
            model = "gpt-4o-custom",
            baseUrl = "https://api.openai.com/v1"
        )

        viewModel.updateDraftSettings(modified)

        val retrieved = viewModel.getDraftSettings(LlmProvider.OPENAI)
        assertEquals("sk-test-draft-key-12345", retrieved.apiKey)
        assertEquals("gpt-4o-custom", retrieved.model)
    }

    @Test
    fun `saveProviderSettings fails on critical validation issue`() {
        val invalidSettings = LlmSettings(
            provider = LlmProvider.OPENAI,
            model = "",
            baseUrl = "invalid-url",
            apiKey = "sk-test",
            enabled = true
        )

        val saved = viewModel.saveProviderSettings(invalidSettings, PrivacyMode.HYBRID)
        assertFalse(saved)
    }

    @Test
    fun `saveProviderSettings succeeds for valid configuration`() {
        val validSettings = LlmSettings(
            provider = LlmProvider.ANTHROPIC,
            model = "claude-3-5-sonnet-20241022",
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-valid-key",
            enabled = true
        )

        val saved = viewModel.saveProviderSettings(validSettings, PrivacyMode.HYBRID)
        assertTrue(saved)

        val loaded = viewModel.repository.loadSettings(LlmProvider.ANTHROPIC)
        assertEquals("claude-3-5-sonnet-20241022", loaded.model)
    }

    @Test
    fun `runPreflightDiagnostics blocks on critical validation failure`() {
        val invalidSettings = LlmSettings(
            provider = LlmProvider.OPENAI,
            model = "",
            baseUrl = "not-a-url"
        )

        viewModel.runPreflightDiagnostics(invalidSettings, PrivacyMode.HYBRID)
        val result = viewModel.preflightResultText.value
        assertNotNull(result)
        assertTrue(result.orEmpty().isNotBlank())
    }

    @Test
    fun `saveLocalAuth updates auth status`() {
        viewModel.saveLocalAuth("user@example.com", "password123", true)
        val status = viewModel.localAuthStatusText.value
        assertNotNull(status)

        val storedEmail = viewModel.authRepository.getStoredEmail()
        assertEquals("user@example.com", storedEmail)
    }

    @Test
    fun `createSession and revokeSession update session status`() {
        viewModel.saveLocalAuth("test@example.com", "pass1234", false)
        viewModel.createSession("test@example.com", "pass1234")

        val statusCreated = viewModel.localAuthStatusText.value
        assertNotNull(statusCreated)
        assertTrue(statusCreated.orEmpty().isNotBlank())

        viewModel.revokeSession()
        val statusRevoked = viewModel.localAuthStatusText.value
        assertNotNull(statusRevoked)
        assertTrue(statusRevoked.orEmpty().isNotBlank())
    }

    @Test
    fun `linkAccount updates account link states`() {
        viewModel.linkAccount(
            provider = ExternalProvider.CLAUDE,
            access = "access-token-123",
            refresh = "refresh-token-456",
            expires = 3600L,
            clientId = "client-id-1",
            clientSecret = "client-secret-1"
        )

        val statusText = viewModel.linkedAccountsStatusText.value
        assertNotNull(statusText)
        assertTrue(statusText.orEmpty().isNotBlank())
    }

    @Test
    fun `setActiveTheme and resetTheme update active theme state`() {
        val themes = KthemeManager.getAllThemes()
        if (themes.isNotEmpty()) {
            val theme = themes.first()
            viewModel.setActiveTheme(theme.metadata.id, theme.metadata.name)
            assertNotNull(viewModel.activeThemeText.value)
        }

        viewModel.resetTheme()
        assertNotNull(viewModel.activeThemeText.value)
    }

    @Test
    fun `installRecommendedModel updates local on-device draft settings`() {
        viewModel.installRecommendedModel()
        val summary = viewModel.modelManagerSummaryText.value
        assertNotNull(summary)

        val localDraft = viewModel.getDraftSettings(LlmProvider.LOCAL_ON_DEVICE)
        assertEquals("google/gemma-3n-E2B-it-litert-lm", localDraft.model)
        assertEquals("/data/local/tmp/gemma3n_e2b_litertlm.bin", localDraft.localModelPath)
    }
}
