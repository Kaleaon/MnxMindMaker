package com.kaleaon.mnxmindmaker.ui

import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.kaleaon.mnxmindmaker.R
import com.kaleaon.mnxmindmaker.databinding.ActivityMainBinding
import com.kaleaon.mnxmindmaker.ktheme.KthemeManager
import com.kaleaon.mnxmindmaker.ktheme.Theme
import com.kaleaon.mnxmindmaker.util.background.MindHealthWorkScheduler

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise Ktheme engine and load bundled themes
        KthemeManager.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.mindMapFragment, R.id.importFragment, R.id.settingsFragment)
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNav.setupWithNavController(navController)

        // Reactively apply theme colours when the active theme changes
        KthemeManager.activeTheme.observe(this) { theme ->
            applyThemeToChrome(theme)
        }

        // Schedule periodic background mind-health maintenance work.
        MindHealthWorkScheduler.schedule(this)
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
