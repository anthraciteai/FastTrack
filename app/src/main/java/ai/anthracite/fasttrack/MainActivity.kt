package ai.anthracite.fasttrack

import ai.anthracite.fasttrack.databinding.ActivityMainBinding
import ai.anthracite.fasttrack.integration.sleep.SleepEstimator
import ai.anthracite.fasttrack.ui.common.SleepLogDialog
import ai.anthracite.fasttrack.util.DayKeys
import ai.anthracite.fasttrack.widget.FastTrackWidget
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        /** Intent extra set by the morning sleep nudge to open the sleep-logging dialog. */
        const val EXTRA_OPEN_SLEEP = "open_sleep_log"
        /** Set by the Sunday weekly-review notification to open the review screen. */
        const val EXTRA_OPEN_WEEKLY_REVIEW = "open_weekly_review"
        const val EXTRA_CHECK_IN_FAST = "check_in_fast_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController

        binding.bottomNav.setOnItemSelectedListener { item ->
            goToTab(navController, item.itemId)
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            goToTab(navController, item.itemId)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.visibility = View.VISIBLE
            val tab = tabFor(destination.id)
            val menuItem = binding.bottomNav.menu.findItem(tab)
            if (menuItem != null && !menuItem.isChecked) menuItem.isChecked = true
        }

        maybeShowHealthConnectRationale()
        handleWidgetIntent(intent)
        handleSleepIntent(intent)
        handleWeeklyReviewIntent(intent)
        handleCheckInIntent(intent)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // adjustResize already lifts the window above the keyboard, so we must NOT also
            // pad the root by the IME height — doing both shrinks content twice and shoves the
            // composer (and the bottom nav) off-screen. When the keyboard is up the window is
            // already sized above it, so no bottom padding is needed; otherwise respect the nav bar.
            val keyboardUp = ime.bottom > bars.bottom
            v.setPadding(0, bars.top, 0, if (keyboardUp) 0 else bars.bottom)
            insets
        }
        // The root already reserves the system-bar space below the nav, so the BottomNavigationView
        // must NOT add the inset again — otherwise it grows a tall empty band under the labels.
        // Override NavigationBarView's built-in inset handling with a no-op bottom padding.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
        handleSleepIntent(intent)
        handleWeeklyReviewIntent(intent)
        handleCheckInIntent(intent)
    }

    private fun handleCheckInIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_CHECK_IN_FAST)?.toLongOrNull() ?: return
        intent.removeExtra(EXTRA_CHECK_IN_FAST)
        val app = application as FastTrackApp
        lifecycleScope.launch {
            val fast = app.repository.allFasts().firstOrNull { it.id == id } ?: return@launch
            ai.anthracite.fasttrack.ui.checkin.PostFastCheckIn.prompt(this@MainActivity, fast)
        }
    }

    /**
     * Opened from the morning sleep nudge: estimate last night from phone usage (if Usage Access
     * is granted), then show the sleep dialog pre-filled for the user to confirm/adjust.
     */
    private fun handleSleepIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SLEEP, false) != true) return
        intent.removeExtra(EXTRA_OPEN_SLEEP)
        val app = application as FastTrackApp
        lifecycleScope.launch {
            val estimate = withContext(Dispatchers.Default) {
                SleepEstimator.estimateLastNight(this@MainActivity)
            }
            SleepLogDialog.show(this@MainActivity, estimate) { minutes ->
                lifecycleScope.launch {
                    app.repository.setSleepMinutes(
                        DayKeys.today(), minutes, System.currentTimeMillis()
                    )
                }
                Snackbar.make(
                    binding.root,
                    getString(R.string.sleep_saved, minutes / 60, minutes % 60),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Select a bottom-nav tab programmatically. Routes through the same listener the bottom bar
     * uses so in-app shortcuts to a tab destination (e.g. a dashboard card opening Chat) share the
     * tab back-stack model — otherwise navigating to a tab root via a plain action corrupts the
     * saved-state back stack and makes "Home" dead-end with no way back.
     */
    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    /**
     * Handle a bottom-nav tap (whether it fires select or reselect).
     *
     * The tricky case: logging pages (water/nutrition/exercise) can be opened straight from a Home
     * dashboard card, which pushes them onto the *Home* back stack even though [tabFor] highlights
     * the Log tab. If we went straight to the multi-stack save/restore path, tapping "Home" would
     * `popUpTo` the start destination *and* restore its saved state — which can restore the very
     * page we're leaving, so the screen never changes. So first: if the tapped tab's destination
     * is already in the current back stack, just pop back to it. Only genuine cross-tab switches
     * fall through to the save/restore path that preserves each tab's own history.
     */
    private fun goToTab(navController: androidx.navigation.NavController, itemId: Int): Boolean {
        if (navController.currentDestination?.id == itemId) return true
        if (navController.popBackStack(itemId, false)) return true
        val options = NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(navController.graph.findStartDestination().id, inclusive = false, saveState = true)
            .build()
        return runCatching { navController.navigate(itemId, null, options) }.isSuccess
    }

    private fun tabFor(destinationId: Int): Int = when (destinationId) {
        // Logging destinations live under the Log hub tab.
        R.id.waterFragment, R.id.nutritionFragment, R.id.exerciseFragment -> R.id.logHubFragment
        // Trends IS the Movement tab root.
        R.id.trendsFragment -> R.id.trendsFragment
        // Everything reached from the Home header (profile + its sub-screens) keeps Home selected.
        R.id.profileFragment, R.id.settingsFragment, R.id.integrationsFragment,
        R.id.scheduleFragment, R.id.achievementsFragment, R.id.protocolFragment,
        R.id.dailyUpdateFragment, R.id.weeklyReviewFragment -> R.id.dashboardFragment
        else -> destinationId
    }

    private fun handleWeeklyReviewIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_WEEKLY_REVIEW) == null) return
        intent.removeExtra(EXTRA_OPEN_WEEKLY_REVIEW)
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return
        val nav = navHost.navController
        if (nav.currentDestination?.id != R.id.weeklyReviewFragment) {
            runCatching { nav.navigate(R.id.weeklyReviewFragment) }
        }
    }

    private fun handleWidgetIntent(intent: Intent?) {
        val dest = intent?.getStringExtra(FastTrackWidget.EXTRA_DEST) ?: return
        intent.removeExtra(FastTrackWidget.EXTRA_DEST)
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return
        val nav = navHost.navController
        val target = when (dest) {
            FastTrackWidget.DEST_FASTING -> R.id.fastingFragment
            FastTrackWidget.DEST_EXERCISE -> R.id.exerciseFragment
            else -> return
        }
        if (nav.currentDestination?.id != target) {
            runCatching { nav.navigate(target) }
        }
    }

    private fun maybeShowHealthConnectRationale() {
        val action = intent?.action ?: return
        if (action != "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" &&
            action != "android.intent.action.VIEW_PERMISSION_USAGE"
        ) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Health Connect")
            .setMessage(
                "FastTrack reads your workouts, active calories, distance, and heart rate from " +
                    "Health Connect so phone-logged activity shows up next to Garmin — nothing is written back."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
