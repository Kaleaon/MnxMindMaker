package com.kaleaon.mnxmindmaker.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.FragmentSettingsAccountsSecurityBinding
import com.kaleaon.mnxmindmaker.model.ExternalProvider

class SettingsAccountsSecurityFragment : Fragment() {

    private var _binding: FragmentSettingsAccountsSecurityBinding? = null
    private val binding get() = requireNotNull(_binding) { "Binding accessed outside of view lifecycle." }

    private val viewModel: SettingsViewModel by viewModels({ requireParentFragment() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsAccountsSecurityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupLocalAuthSection()
        setupAccountLinkingSection()
        observeViewModel()
    }

    private fun setupLocalAuthSection() {
        binding.etLocalAuthEmail.setText(viewModel.authRepository.getStoredEmail())
        binding.switchPasskeyEnabled.isChecked = viewModel.authRepository.getPasskeyStatus()

        binding.btnSaveLocalAuth.setOnClickListener {
            val email = binding.etLocalAuthEmail.text.toString().trim()
            val password = binding.etLocalAuthPassword.text.toString()
            viewModel.saveLocalAuth(email, password, binding.switchPasskeyEnabled.isChecked)
            binding.etLocalAuthPassword.setText("")
        }

        binding.btnCreateSession.setOnClickListener {
            val email = binding.etLocalAuthEmail.text.toString().trim()
            val password = binding.etLocalAuthPassword.text.toString()
            viewModel.createSession(email, password)
            binding.etLocalAuthPassword.setText("")
        }

        binding.btnRevokeSession.setOnClickListener {
            viewModel.revokeSession()
        }
    }

    private fun setupAccountLinkingSection() {
        binding.btnLinkClaude.setOnClickListener { promptLinkAccount(ExternalProvider.CLAUDE) }
        binding.btnLinkChatgpt.setOnClickListener { promptLinkAccount(ExternalProvider.CHATGPT) }
        binding.btnLinkHuggingface.setOnClickListener { promptLinkAccount(ExternalProvider.HUGGING_FACE) }

        binding.btnRefreshClaude.setOnClickListener { viewModel.refreshLinkedAccount(ExternalProvider.CLAUDE) }
        binding.btnRefreshChatgpt.setOnClickListener { viewModel.refreshLinkedAccount(ExternalProvider.CHATGPT) }
        binding.btnRefreshHuggingface.setOnClickListener { viewModel.refreshLinkedAccount(ExternalProvider.HUGGING_FACE) }

        binding.btnRevokeClaude.setOnClickListener { viewModel.revokeLinkedAccount(ExternalProvider.CLAUDE) }
        binding.btnRevokeChatgpt.setOnClickListener { viewModel.revokeLinkedAccount(ExternalProvider.CHATGPT) }
        binding.btnRevokeHuggingface.setOnClickListener { viewModel.revokeLinkedAccount(ExternalProvider.HUGGING_FACE) }
    }

    private fun observeViewModel() {
        viewModel.localAuthStatusText.observe(viewLifecycleOwner) { text ->
            binding.tvLocalAuthStatus.text = text
        }

        viewModel.linkedAccountsStatusText.observe(viewLifecycleOwner) { text ->
            binding.tvLinkedAccountsStatus.text = text

            val claudeRefreshEnabled = viewModel.externalAccountRepository.canRefreshLinkedAccount(ExternalProvider.CLAUDE)
            val chatGptRefreshEnabled = viewModel.externalAccountRepository.canRefreshLinkedAccount(ExternalProvider.CHATGPT)
            val huggingFaceRefreshEnabled = viewModel.externalAccountRepository.canRefreshLinkedAccount(ExternalProvider.HUGGING_FACE)

            binding.btnRefreshClaude.isEnabled = claudeRefreshEnabled
            binding.btnRefreshChatgpt.isEnabled = chatGptRefreshEnabled
            binding.btnRefreshHuggingface.isEnabled = huggingFaceRefreshEnabled
        }

        viewModel.snackbarMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun promptLinkAccount(provider: ExternalProvider) {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val accessInput = EditText(context).apply {
            hint = getString(R.string.link_access_token_hint)
        }
        val refreshInput = EditText(context).apply {
            hint = getString(R.string.link_refresh_token_hint)
        }
        val expiresInput = EditText(context).apply {
            hint = getString(R.string.link_expiry_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val clientIdInput = EditText(context).apply {
            hint = getString(R.string.link_oauth_client_id_hint)
        }
        val clientSecretInput = EditText(context).apply {
            hint = getString(R.string.link_oauth_client_secret_hint)
        }
        layout.addView(accessInput)
        layout.addView(refreshInput)
        layout.addView(expiresInput)
        layout.addView(clientIdInput)
        layout.addView(clientSecretInput)

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.link_provider_title, provider.displayName))
            .setView(layout)
            .setPositiveButton(R.string.link_account_action) { _, _ ->
                val access = accessInput.text.toString().trim()
                val refresh = refreshInput.text.toString().trim()
                val expires = expiresInput.text.toString().trim().toLongOrNull()
                val clientId = clientIdInput.text.toString().trim()
                val clientSecret = clientSecretInput.text.toString().trim()

                viewModel.linkAccount(provider, access, refresh, expires, clientId, clientSecret)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
