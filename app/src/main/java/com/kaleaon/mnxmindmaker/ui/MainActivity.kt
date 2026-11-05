package com.kaleaon.mnxmindmaker.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.ktheme.Theme
import com.kaleaon.mnxmindmaker.model.OAuthAuthorizationResult
import com.kaleaon.mnxmindmaker.model.OAuthTokenExchangeResult
import com.kaleaon.mnxmindmaker.repository.ExternalAccountRepository
import com.kaleaon.mnxmindmaker.repository.OAuthManager
import com.kaleaon.mnxmindmaker.ui.importdata.ImportDataHolder
import com.kaleaon.mnxmindmaker.util.FileImporter
import com.kaleaon.mnxmindmaker.util.background.MindHealthWorkScheduler
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise Ktheme engine and load bundled themes
        KthemeManager.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navHostFragment = findNavHostFragment()
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.mindMapFragment, R.id.importFragment, R.id.settingsFragment)
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNav.setupWithNavController(navController)

        if (savedInstanceState == null) {
            handleInboundIntent(intent)
        }

        // Reactively apply theme colours when the active theme changes
        KthemeManager.activeTheme.observe(this) { theme ->
            applyThemeToChrome(theme)
        }

        // Schedule periodic background mind-health maintenance work.
        MindHealthWorkScheduler.schedule(this)
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInboundIntent(intent)
    }

    private fun handleInboundIntent(intent: Intent?) {
        val data = intent?.data
        if (data != null && isOAuthCallbackUri(data)) {
            handleOAuthCallback(data)
            return
        }

        val resolution = ImportIntentResolver.resolve(
            action = intent?.action,
            type = intent?.type,
            dataString = intent?.dataString
        )

        when (resolution) {
            ImportIntentResolver.Resolution.Ignore -> Unit
            ImportIntentResolver.Resolution.Unsupported -> {
                showImportErrorAndRoute(getString(R.string.import_intent_unsupported))
            }
            ImportIntentResolver.Resolution.InvalidUri -> {
                showImportErrorAndRoute(getString(R.string.import_intent_invalid_uri))
            }
            is ImportIntentResolver.Resolution.Import -> {
                val uri = Uri.parse(resolution.uriString)
                val graphName = getString(R.string.default_import_name)
                val result = FileImporter.importFromUri(uri, this, graphName)
                result.onSuccess { graph ->
                    ImportDataHolder.pendingGraph = graph
                    navigateToDestination(R.id.mindMapFragment)
                    Snackbar.make(
                        binding.root,
                        getString(R.string.import_intent_success, graph.nodes.size),
                        Snackbar.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    val reason = error.message ?: getString(R.string.import_intent_unknown_error)
                    showImportErrorAndRoute(getString(R.string.import_parse_error, reason))
                }
            }
        }
    }

    private fun isOAuthCallbackUri(data: Uri): Boolean {
        val scheme = data.scheme?.lowercase()
        val host = data.host?.lowercase()
        val path = data.path?.lowercase()
        return (scheme == "mnxmindmaker" || scheme == "mnx") && host == "oauth" && path == "/callback"
    }

    private fun handleOAuthCallback(uri: Uri) {
        val oauthManager = OAuthManager(this)
        val externalAccountRepository = ExternalAccountRepository(this)
        val authResult = oauthManager.parseAndValidateCallbackUri(uri)

        when (authResult) {
            is OAuthAuthorizationResult.Success -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val clientId = externalAccountRepository.getOAuthClientId(authResult.provider) ?: ""
                    val clientSecret = externalAccountRepository.getOAuthClientSecret(authResult.provider)

                    val exchangeResult = oauthManager.exchangeAuthorizationCode(
                        provider = authResult.provider,
                        code = authResult.code,
                        codeVerifier = authResult.codeVerifier,
                        redirectUri = authResult.redirectUri,
                        clientId = clientId,
                        clientSecret = clientSecret
                    )

                    withContext(Dispatchers.Main) {
                        when (exchangeResult) {
                            is OAuthTokenExchangeResult.Success -> {
                                externalAccountRepository.linkAccount(
                                    provider = exchangeResult.provider,
                                    accessToken = exchangeResult.accessToken,
                                    refreshToken = exchangeResult.refreshToken ?: "",
                                    expiresInSeconds = exchangeResult.expiresInSeconds
                                )
                                navigateToDestination(R.id.settingsFragment)
                                Snackbar.make(
                                    binding.root,
                                    getString(R.string.oauth_success_linked, exchangeResult.provider.displayName),
                                    Snackbar.LENGTH_LONG
                                ).show()
                            }
                            is OAuthTokenExchangeResult.Failure -> {
                                navigateToDestination(R.id.settingsFragment)
                                Snackbar.make(
                                    binding.root,
                                    getString(R.string.oauth_failed, exchangeResult.reason),
                                    Snackbar.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            is OAuthAuthorizationResult.Error -> {
                navigateToDestination(R.id.settingsFragment)
                val msg = authResult.errorDescription ?: authResult.error
                Snackbar.make(binding.root, getString(R.string.oauth_failed, msg), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showImportErrorAndRoute(message: String) {
        navigateToDestination(R.id.importFragment)
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun navigateToDestination(destinationId: Int) {
        if (navController.currentDestination?.id != destinationId) {
            navController.navigate(destinationId)
        }
    }

    private fun applyThemeToChrome(theme: Theme?) {
        theme ?: return
        val cs = theme.colorScheme
        val surface = KthemeManager.parseColor(cs.surface)
        val onSurface = KthemeManager.parseColor(cs.onSurface)
        val onSurfaceVariant = KthemeManager.parseColor(cs.onSurfaceVariant)
        val background = KthemeManager.parseColor(cs.background)
        val primary = KthemeManager.parseColor(cs.primary)
        val bottomNavItemTint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked),
            ),
            intArrayOf(primary, onSurfaceVariant),
        )

        // Toolbar / AppBar
        binding.toolbar.setBackgroundColor(surface)
        binding.toolbar.setTitleTextColor(onSurface)
        binding.appBar.setBackgroundColor(surface)

        // Bottom navigation
        binding.bottomNav.setBackgroundColor(surface)
        binding.bottomNav.itemIconTintList = bottomNavItemTint
        binding.bottomNav.itemTextColor = bottomNavItemTint
        binding.bottomNav.itemActiveIndicatorColor = ColorStateList.valueOf(primary)

        // Root background
        binding.root.setBackgroundColor(background)

        // Status bar colour
        window.statusBarColor = KthemeManager.parseColor(cs.surfaceVariant)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = findNavHostFragment()
        return navHostFragment.navController.navigateUp(appBarConfiguration) ||
                super.onSupportNavigateUp()
    }

    private fun findNavHostFragment(): NavHostFragment {
        val fragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        return requireNavHostFragment(fragment)
    }

    companion object {
        private const val TAG = "MainActivity"

        internal fun requireNavHostFragment(fragment: Any?): NavHostFragment {
            return fragment as? NavHostFragment ?: run {
                val actualType = fragment?.javaClass?.name ?: "null"
                val message =
                    "Expected NavHostFragment at R.id.nav_host_fragment, but found $actualType. " +
                            "Ensure activity_main.xml defines a NavHostFragment with that id."
                runCatching { Log.e(TAG, message) }
                throw IllegalStateException(message)
            }
        }
    }
}
