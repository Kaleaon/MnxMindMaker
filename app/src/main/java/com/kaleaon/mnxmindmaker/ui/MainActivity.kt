package com.kaleaon.mnxmindmaker.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
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
import com.kaleaon.mnxmindmaker.ui.importdata.ImportDataHolder
import com.kaleaon.mnxmindmaker.util.FileImporter
import com.kaleaon.mnxmindmaker.util.background.MindHealthWorkScheduler

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

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
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
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHostFragment.navController.navigateUp(appBarConfiguration) ||
                super.onSupportNavigateUp()
    }
}
