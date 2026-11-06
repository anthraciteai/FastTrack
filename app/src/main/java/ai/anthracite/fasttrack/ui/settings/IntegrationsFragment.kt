package ai.anthracite.fasttrack.ui.settings

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.FragmentIntegrationsBinding
import ai.anthracite.fasttrack.databinding.PartialIntegrationRowBinding
import ai.anthracite.fasttrack.integration.ConnectionState
import ai.anthracite.fasttrack.integration.ConnectionStatus
import ai.anthracite.fasttrack.integration.calendar.GoogleCalendarClient
import ai.anthracite.fasttrack.integration.enterprise.EnterpriseClient
import ai.anthracite.fasttrack.integration.healthconnect.HealthConnectSource
import ai.anthracite.fasttrack.nutrition.estimator.ModelState
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class IntegrationsFragment : Fragment() {

    private var _binding: FragmentIntegrationsBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireContext().applicationContext as FastTrackApp

    private val healthConnectLauncher = registerForActivityResult(
        HealthConnectSource.permissionContract()
    ) { granted ->
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = granted.containsAll(HealthConnectSource.PERMISSIONS)
            snack(if (ok) "Health Connect connected — pulling recent workouts" else "Permissions not granted")
            if (ok) syncHealth()
            refresh()
        }
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = GoogleCalendarClient.PERMISSIONS.all { granted[it] == true }
        viewLifecycleOwner.lifecycleScope.launch { finishCalendarConnect(ok) }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewLifecycleOwner.lifecycleScope.launch { finishGoogleSignIn(result.data) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIntegrationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        setupRow(binding.garminRow, R.drawable.ic_watch, getString(R.string.integration_garmin))
        setupRow(binding.healthConnectRow, R.drawable.ic_exercise, getString(R.string.integration_health_connect))
        setupRow(binding.calendarRow, R.drawable.ic_schedule, getString(R.string.integration_google_calendar))
        setupRow(binding.webRow, R.drawable.ic_sync, getString(R.string.integration_web_sync))
        setupRow(binding.orgRow, R.drawable.ic_stage_shield, "Organization")

        refresh()

        binding.garminRow.actionButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = app.garminClient.connect()
                if (!ok) {
                    snack("Add your Garmin key in local.properties to connect")
                    refresh()
                    return@launch
                }
                snack("Garmin connected — pulling your data…")
                val now = System.currentTimeMillis()
                val since = now - TimeUnit.DAYS.toMillis(14)
                val workouts = app.healthSyncManager.syncRange(since, now)
                val days = app.syncGarminDailyMetrics(since, now)
                snack(
                    "Synced $workouts workout${if (workouts == 1) "" else "s"} and " +
                        "$days day${if (days == 1) "" else "s"} of health data"
                )
                refresh()
            }
        }
        binding.healthConnectRow.actionButton.setOnClickListener { connectHealthConnect() }
        binding.calendarRow.actionButton.setOnClickListener {
            if (app.prefs.googleCalendarConnected) {
                app.calendarClient.signOut()
                snack("Google Calendar disconnected")
                refresh()
            } else {
                connectCalendar()
            }
        }
        binding.webRow.actionButton.setOnClickListener {
            if (app.googleIdentity.isSignedIn() || app.prefs.googleSyncConnected) {
                viewLifecycleOwner.lifecycleScope.launch {
                    app.googleIdentity.signOut()
                    app.prefs.googleSyncConnected = false
                    app.prefs.googleSyncEmail = ""
                    snack("Google signed out — phone and web will stop syncing")
                    refresh()
                }
            } else {
                googleSignInLauncher.launch(app.googleIdentity.client(requireActivity()).signInIntent)
            }
        }

        binding.orgRow.actionButton.setOnClickListener { launchQrScan() }
        binding.orgRow.root.setOnLongClickListener {
            if (app.enterpriseClient.isEnrolled) { confirmLeaveOrg(); true } else false
        }

        setupModelCard()
    }

    /** Launch the Google code scanner (no camera UI/permission needed) and route the result. */
    private fun launchQrScan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(requireContext(), options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw.isNullOrBlank()) snack("That QR code was empty") else handleScannedPayload(raw)
            }
            .addOnCanceledListener { /* user dismissed the scanner */ }
            .addOnFailureListener { e -> snack("Couldn't open the scanner: ${e.message}") }
    }

    private fun handleScannedPayload(raw: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            snack("Reading code…")
            when (val r = app.enterpriseClient.handleScan(raw)) {
                is EnterpriseClient.Outcome.Joined -> snack("Joined ${r.org} — your progress is now shared")
                is EnterpriseClient.Outcome.SignedIn -> snack("Signed the browser in to ${r.org}")
                is EnterpriseClient.Outcome.Pushed -> snack("Synced with ${r.org}")
                is EnterpriseClient.Outcome.Error -> snack(r.message)
            }
            refresh()
        }
    }

    private fun confirmLeaveOrg() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Leave ${app.prefs.enterpriseOrg}?")
            .setMessage("Your phone will stop sharing data with this organization.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Leave") { _, _ ->
                app.prefs.clearEnterprise()
                snack("Left the organization")
                refresh()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    private fun connectHealthConnect() {
        when (app.healthConnectSource.sdkStatus()) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                startActivity(app.healthConnectSource.providerInstallIntent())
                snack("Install Health Connect, then tap Connect again")
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                startActivity(app.healthConnectSource.providerInstallIntent())
                snack("Update Health Connect, then tap Connect again")
            }
            HealthConnectClient.SDK_AVAILABLE ->
                healthConnectLauncher.launch(HealthConnectSource.PERMISSIONS)
            else -> snack("Health Connect isn't available on this device")
        }
    }

    private fun connectCalendar() {
        if (app.calendarClient.hasPermissions()) {
            viewLifecycleOwner.lifecycleScope.launch { finishCalendarConnect(true) }
            return
        }
        calendarPermissionLauncher.launch(GoogleCalendarClient.PERMISSIONS)
    }

    private suspend fun finishGoogleSignIn(data: android.content.Intent?) {
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data).await()
        }.getOrNull()
        if (account == null) {
            snack("Google sign-in didn't complete")
            refresh()
            return
        }
        app.prefs.googleSyncConnected = true
        app.prefs.googleSyncEmail = account.email.orEmpty()
        snack("Signed in as ${account.email} — syncing")
        val result = app.syncClient.syncNow()
        snack(result.message)
        refresh()
    }

    private suspend fun finishCalendarConnect(granted: Boolean) {
        if (!app.calendarClient.onPermissionsGranted(granted)) {
            snack("Calendar permission is needed to sync your schedule")
            refresh()
            return
        }
        snack("Calendar connected — pulling upcoming events")
        syncCalendar()
        refresh()
    }

    private suspend fun syncHealth() {
        val now = System.currentTimeMillis()
        val imported = app.healthSyncManager.syncRange(now - TimeUnit.DAYS.toMillis(14), now)
        if (imported > 0) snack("Imported $imported workout${if (imported == 1) "" else "s"}")
    }

    private suspend fun syncCalendar() {
        val now = System.currentTimeMillis()
        val events = app.calendarClient.pullEvents(now - TimeUnit.DAYS.toMillis(1), now + TimeUnit.DAYS.toMillis(14))
        val imported = app.repository.importCalendarEvents(events)
        if (imported > 0) snack("Synced $imported calendar event${if (imported == 1) "" else "s"}")
    }

    private fun setupModelCard() {
        if (!app.prefs.aiFeaturesEnabled) {
            binding.aiModelCard.visibility = View.GONE
            return
        }
        binding.aiModelCard.visibility = View.VISIBLE
        val manager = app.mealModelManager
        manager.refresh()

        binding.aiModelButton.setOnClickListener {
            when (manager.state.value) {
                is ModelState.Ready -> {
                    manager.delete()
                    snack("On-device model removed")
                }
                is ModelState.Preparing -> manager.cancel()
                is ModelState.NotConfigured -> { }
                else -> viewLifecycleOwner.lifecycleScope.launch { manager.ensureInstalled() }
            }
        }
        binding.aiProbeButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.aiProbeButton.isEnabled = false
                binding.aiProbeResult.visibility = View.VISIBLE
                binding.aiProbeResult.text = "Running LiteRT ping…"
                val result = app.onDeviceLlm.probe("two scrambled eggs and toast")
                binding.aiProbeResult.text = buildString {
                    append(if (result.ok) "NLP OK" else "NLP FAILED")
                    append(" · ${result.elapsedMs}ms · ${result.engine}")
                    result.estimate?.let { append(" · ${it.calories} kcal ${it.name}") }
                    result.error?.let { append("\n$it") }
                    if (result.engineReset) append("\nEngine was reset after this run.")
                    if (result.rawPreview.isNotBlank()) append("\n${result.rawPreview}")
                }
                binding.aiProbeButton.isEnabled = true
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.state.collect { renderModelState(it) }
            }
        }
    }

    private fun renderModelState(state: ModelState) {
        val name = app.mealModelManager.model.displayName
        val progress = binding.aiModelProgress
        when (state) {
            is ModelState.Ready -> {
                binding.aiModelStatus.text = "$name is active · smarter food logging on"
                binding.aiModelButton.text = getString(R.string.action_delete)
                binding.aiModelButton.visibility = View.VISIBLE
                binding.aiModelButton.isEnabled = true
                progress.visibility = View.GONE
                binding.aiProbeButton.visibility = View.VISIBLE
            }
            is ModelState.Available -> {
                binding.aiProbeButton.visibility = View.GONE
                binding.aiProbeResult.visibility = View.GONE
                binding.aiModelStatus.text =
                    if (state.bundled) "$name is ready to set up (~${state.sizeLabel})"
                    else "$name available to install (~${state.sizeLabel})"
                binding.aiModelButton.text = "Set up"
                binding.aiModelButton.visibility = View.VISIBLE
                binding.aiModelButton.isEnabled = true
                progress.visibility = View.GONE
            }
            is ModelState.Preparing -> {
                binding.aiProbeButton.visibility = View.GONE
                binding.aiModelStatus.text =
                    if (state.percent >= 0) "Setting up $name… ${state.percent}%" else "Setting up $name…"
                binding.aiModelButton.text = getString(R.string.action_cancel)
                binding.aiModelButton.visibility = View.VISIBLE
                binding.aiModelButton.isEnabled = true
                progress.visibility = View.VISIBLE
                if (state.percent >= 0) progress.setProgressCompat(state.percent, true)
                else progress.isIndeterminate = true
            }
            is ModelState.NotConfigured -> {
                binding.aiProbeButton.visibility = View.GONE
                binding.aiProbeResult.visibility = View.GONE
                binding.aiModelStatus.text = "Smart food logging runs on the built-in estimator."
                binding.aiModelButton.visibility = View.GONE
                progress.visibility = View.GONE
            }
            is ModelState.Failed -> {
                binding.aiProbeButton.visibility = View.GONE
                binding.aiModelStatus.text = "Setup failed: ${state.message}"
                binding.aiModelButton.text = "Retry"
                binding.aiModelButton.visibility = View.VISIBLE
                binding.aiModelButton.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun setupRow(row: PartialIntegrationRowBinding, iconRes: Int, title: String) {
        row.icon.setImageResource(iconRes)
        row.title.text = title
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            applyStatus(binding.garminRow, app.garminClient.status())
            applyStatus(binding.healthConnectRow, app.healthConnectSource.status())
            applyStatus(binding.calendarRow, app.calendarClient.status())
            applyStatus(binding.webRow, app.syncClient.status())
            applyOrgStatus()
        }
    }

    /** The org row always offers a scan; it never becomes an inert "Connected" button. */
    private fun applyOrgStatus() {
        val row = binding.orgRow
        val client = app.enterpriseClient
        val connected = client.isEnrolled
        row.status.text = if (connected) {
            "Sharing with ${app.prefs.enterpriseOrg} · long-press to leave"
        } else {
            client.status().detail
        }
        val green = ContextCompat.getColor(requireContext(), R.color.accent_nutrition)
        val greenContainer = ContextCompat.getColor(requireContext(), R.color.accent_nutrition_container)
        val idle = ContextCompat.getColor(requireContext(), R.color.md_primary)
        val idleContainer = ContextCompat.getColor(requireContext(), R.color.md_primary_container)
        row.iconBadge.backgroundTintList = ColorStateList.valueOf(if (connected) greenContainer else idleContainer)
        row.icon.imageTintList = ColorStateList.valueOf(if (connected) green else idle)
        row.actionButton.text = if (connected) "Scan" else "Scan QR"
        row.actionButton.isEnabled = true
        row.actionButton.icon = null
    }

    private fun applyStatus(row: PartialIntegrationRowBinding, status: ConnectionStatus) {
        val connected = status.state == ConnectionState.CONNECTED
        row.status.text = if (connected) {
            val who = status.detail
            if (who.isBlank() || who.equals("Connected", ignoreCase = true)) "Connected"
            else "Connected · $who"
        } else {
            status.detail
        }
        val green = ContextCompat.getColor(requireContext(), R.color.accent_nutrition)
        val greenContainer = ContextCompat.getColor(requireContext(), R.color.accent_nutrition_container)
        val idle = ContextCompat.getColor(requireContext(), R.color.md_primary)
        val idleContainer = ContextCompat.getColor(requireContext(), R.color.md_primary_container)
        row.iconBadge.backgroundTintList = ColorStateList.valueOf(if (connected) greenContainer else idleContainer)
        row.icon.imageTintList = ColorStateList.valueOf(if (connected) green else idle)
        row.actionButton.text = when {
            connected && (row === binding.calendarRow || row === binding.webRow) -> "Disconnect"
            connected -> getString(R.string.integration_connected)
            status.state == ConnectionState.NOT_CONFIGURED -> "Set up"
            row === binding.webRow -> "Sign in"
            else -> "Connect"
        }
        row.actionButton.isEnabled = !connected || row === binding.calendarRow || row === binding.webRow
        if (connected) row.actionButton.setIconResource(R.drawable.ic_check) else row.actionButton.icon = null
    }

    private fun snack(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
