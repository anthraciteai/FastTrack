package ai.anthracite.fasttrack.ui.profile

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.GuidanceEngine
import ai.anthracite.fasttrack.data.local.ActivityLevel
import ai.anthracite.fasttrack.data.local.DietType
import ai.anthracite.fasttrack.data.local.DietaryRestrictions
import ai.anthracite.fasttrack.data.local.Sex
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WeightGoal
import ai.anthracite.fasttrack.integration.enterprise.EnterpriseClient
import com.google.android.material.chip.Chip
import ai.anthracite.fasttrack.databinding.DialogEditProfileBinding
import ai.anthracite.fasttrack.databinding.FragmentProfileBinding
import ai.anthracite.fasttrack.ui.common.showScrollable
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by viewModels()
    private var current: ProfileUiState = ProfileUiState()
    private val app get() = requireContext().applicationContext as FastTrackApp

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewLifecycleOwner.lifecycleScope.launch { finishWebSignIn(result.data) }
    }

    private val activityLabels = listOf("Sedentary", "Light", "Moderate", "Active", "Very active")
    private val activityValues = ActivityLevel.entries
    private val dietValues = DietType.entries
    private val dietLabels = DietType.entries.map { it.label }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.weightStat.statIcon.setImageResource(R.drawable.ic_weight)
        binding.bmiStat.statIcon.setImageResource(R.drawable.ic_trophy)
        binding.goalStat.statIcon.setImageResource(R.drawable.ic_flame)

        binding.settingsRowContent.menuIcon.setImageResource(R.drawable.ic_settings)
        binding.settingsRowContent.menuTitle.text = getString(R.string.settings_title)
        binding.settingsRowContent.menuSubtitle.text = "Units, reminders, data"

        binding.connectionsRowContent.menuIcon.setImageResource(R.drawable.ic_watch)
        binding.connectionsRowContent.menuTitle.text = getString(R.string.settings_integrations)
        binding.connectionsRowContent.menuSubtitle.text = "Garmin, Health Connect, Calendar"

        binding.dailyUpdateRowContent.menuIcon.setImageResource(R.drawable.ic_dashboard)
        binding.dailyUpdateRowContent.menuTitle.text = getString(R.string.daily_update_title)
        binding.dailyUpdateRowContent.menuSubtitle.text = "Revisit past daily recaps"

        binding.weeklyReviewRowContent.menuIcon.setImageResource(R.drawable.ic_trophy)
        binding.weeklyReviewRowContent.menuTitle.text = "Weekly review"
        binding.weeklyReviewRowContent.menuSubtitle.text = "Your week vs last, patterns and one focus"

        binding.protocolRowContent.menuIcon.setImageResource(R.drawable.ic_fasting)
        binding.protocolRowContent.menuTitle.text = getString(R.string.protocol_title)
        binding.protocolRowContent.menuSubtitle.text = "Choose your fasting window"

        binding.scheduleRowContent.menuIcon.setImageResource(R.drawable.ic_schedule)
        binding.scheduleRowContent.menuTitle.text = getString(R.string.schedule_title)
        binding.scheduleRowContent.menuSubtitle.text = "Plan fasts and meals"

        binding.achievementsRowContent.menuIcon.setImageResource(R.drawable.ic_trophy)
        binding.achievementsRowContent.menuTitle.text = getString(R.string.achievements_title)
        binding.achievementsRowContent.menuSubtitle.text = "Streaks and badges"

        binding.dailyUpdateRow.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_dailyupdate)
        }
        binding.weeklyReviewRow.setOnClickListener {
            findNavController().navigate(R.id.weeklyReviewFragment)
        }
        binding.protocolRow.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_protocol)
        }
        binding.scheduleRow.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_schedule)
        }
        binding.achievementsRow.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_achievements)
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.editButton.setOnClickListener { showEditDialog() }
        binding.settingsRow.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_settings)
        }
        binding.connectionsRow.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_integrations)
        }

        binding.webSignInButton.setOnClickListener {
            if (isWebConnected()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    app.googleIdentity.signOut()
                    app.prefs.googleSyncConnected = false
                    app.prefs.googleSyncEmail = ""
                    snack("Signed out of web sync")
                    updateWebButton()
                }
            } else {
                googleSignInLauncher.launch(app.googleIdentity.client(requireActivity()).signInIntent)
            }
        }
        binding.scanQrButton.setOnClickListener { launchQrScan() }
        updateWebButton()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) updateWebButton()
    }

    private fun isWebConnected(): Boolean =
        app.googleIdentity.isSignedIn() || app.prefs.googleSyncConnected

    private fun updateWebButton() {
        if (_binding == null) return
        val connected = isWebConnected()
        binding.webSignInButton.text = if (connected) {
            val email = app.prefs.googleSyncEmail
            if (email.isBlank()) "Web synced · tap to sign out" else "Web synced · $email"
        } else {
            "Sign in to web"
        }
        binding.webSignInButton.setIconResource(if (connected) R.drawable.ic_check else R.drawable.ic_sync)
    }

    private suspend fun finishWebSignIn(data: android.content.Intent?) {
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(data).await()
        }.getOrNull()
        if (account == null) {
            snack("Google sign-in didn't complete")
            updateWebButton()
            return
        }
        app.prefs.googleSyncConnected = true
        app.prefs.googleSyncEmail = account.email.orEmpty()
        snack("Signed in as ${account.email} — syncing")
        app.syncClient.syncNow()
        updateWebButton()
    }

    /** Launch the Google code scanner (no camera UI/permission) and route the result. */
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
        }
    }

    private fun snack(msg: String) = Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    private fun render(s: ProfileUiState) {
        current = s
        val p = s.profile
        val unit = runCatching { UnitSystem.valueOf(p.unitSystem) }.getOrDefault(UnitSystem.IMPERIAL)
        binding.nameText.text = p.displayName
        val goalLabel = when (WeightGoal.valueOf(p.goal)) {
            WeightGoal.LOSE -> "Losing weight"
            WeightGoal.MAINTAIN -> "Maintaining"
            WeightGoal.GAIN -> "Gaining"
        }
        val protocol = ai.anthracite.fasttrack.data.health.FastingProtocol.fromId(p.fastingProtocol).displayName
        val dietLabel = DietType.fromId(p.dietType).label
        val restrictions = DietaryRestrictions.parse(p.dietaryRestrictions)
        binding.goalText.text = buildString {
            append(Units.heightLabel(p.heightCm, unit))
            append(" · $goalLabel · $protocol · $dietLabel")
            if (restrictions.isNotEmpty()) append(" · ${restrictions.joinToString(", ")}")
        }

        binding.weightStat.statValue.text = Units.weightLabel(p.weightKg, unit)
        binding.weightStat.statLabel.text = "Weight"
        binding.bmiStat.statValue.text = String.format("%.1f", s.targets.bmi)
        binding.bmiStat.statLabel.text = GuidanceEngine.bmiCategory(s.targets.bmi)
        binding.goalStat.statValue.text = Units.weightLabel(p.goalWeightKg, unit)
        binding.goalStat.statLabel.text = "Goal"

        binding.targetWater.text = "  Hydration   ${Units.volumeLabel(s.targets.waterMl, unit)}"
        binding.targetCalories.text = "  Calories   ${s.targets.calories} kcal · ${s.targets.dietLabel}"
        binding.targetProtein.text =
            "  P ${s.targets.proteinG}g · C ${s.targets.carbsG}g · F ${s.targets.fatG}g\n  ${s.targets.macroNote}"
        binding.targetFasting.text = "  Fasting   ${s.targets.fastingHours.toInt()}h window"
    }

    private fun showEditDialog() {
        val p = current.profile
        val db = DialogEditProfileBinding.inflate(layoutInflater)
        var unit = runCatching { UnitSystem.valueOf(p.unitSystem) }.getOrDefault(UnitSystem.IMPERIAL)

        db.activityInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, activityLabels)
        )
        db.dietInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, dietLabels)
        )

        // Restriction chips (multi-select)
        val currentRestrictions = DietaryRestrictions.parse(p.dietaryRestrictions)
        db.restrictionChips.removeAllViews()
        DietaryRestrictions.OPTIONS.forEach { option ->
            val chip = Chip(requireContext()).apply {
                text = option
                isCheckable = true
                isChecked = option in currentRestrictions
                tag = option
            }
            db.restrictionChips.addView(chip)
        }

        var applyingUnits = false

        fun liveSex(): Sex = when (db.sexChips.checkedChipId) {
            R.id.sexMale -> Sex.MALE
            R.id.sexFemale -> Sex.FEMALE
            else -> Sex.OTHER
        }

        fun liveHeightCm(): Double {
            val parsed = if (unit == UnitSystem.IMPERIAL) {
                val feet = db.heightFeetInput.text?.toString()?.toIntOrNull() ?: 0
                val inches = db.heightInchesInput.text?.toString()?.toIntOrNull() ?: 0
                Units.feetInchesToCm(feet, inches)
            } else {
                db.heightInput.text?.toString()?.toDoubleOrNull() ?: 0.0
            }
            return if (parsed > 0) parsed else p.heightCm
        }

        fun liveGoalKg(): Double {
            val raw = db.goalWeightInput.text?.toString()?.toDoubleOrNull() ?: 0.0
            val kg = if (unit == UnitSystem.IMPERIAL) Units.lbToKg(raw) else raw
            return if (kg > 0) kg else p.goalWeightKg
        }

        fun refreshSuggestion() {
            val heightCm = liveHeightCm()
            val goalKg = liveGoalKg()
            val ref = GuidanceEngine.weightReference(heightCm, liveSex())
            val compare = GuidanceEngine.goalVsReference(goalKg, ref, unit)
            db.heightRefNote.text = buildString {
                append("Reference for ${Units.heightLabel(heightCm, unit)}: ")
                append("ideal ${Units.weightLabel(ref.idealKg, unit)}")
                append(" · healthy ${Units.weightLabel(ref.healthyLowKg, unit)}")
                append("–${Units.weightLabel(ref.healthyHighKg, unit)}")
                append("\n$compare Just a reference — your goal stays yours.")
            }
        }

        fun applyUnitsToFields(system: UnitSystem) {
            applyingUnits = true
            val imperial = system == UnitSystem.IMPERIAL
            db.weightLayout.hint = if (imperial) "Weight (lb)" else "Weight (kg)"
            db.goalWeightLayout.hint = if (imperial) "Goal (lb)" else "Goal (kg)"
            db.heightLayout.visibility = if (imperial) View.GONE else View.VISIBLE
            db.heightImperialRow.visibility = if (imperial) View.VISIBLE else View.GONE
            if (imperial) {
                db.weightInput.setText(Units.kgToLb(p.weightKg).roundToInt().toString())
                db.goalWeightInput.setText(Units.kgToLb(p.goalWeightKg).roundToInt().toString())
                val (ft, inch) = Units.heightFeetInches(p.heightCm)
                db.heightFeetInput.setText(ft.toString())
                db.heightInchesInput.setText(inch.toString())
            } else {
                db.weightInput.setText(p.weightKg.roundToInt().toString())
                db.goalWeightInput.setText(p.goalWeightKg.roundToInt().toString())
                db.heightInput.setText(p.heightCm.roundToInt().toString())
            }
            applyingUnits = false
            refreshSuggestion()
        }

        // Prefill
        db.nameInput.setText(p.displayName)
        db.birthYearInput.setText(p.birthYear.toString())
        db.activityInput.setText(activityLabels[activityValues.indexOf(ActivityLevel.valueOf(p.activityLevel))], false)
        db.dietInput.setText(DietType.fromId(p.dietType).label, false)
        if (unit == UnitSystem.IMPERIAL) db.unitImperial.isChecked = true else db.unitMetric.isChecked = true
        when (Sex.valueOf(p.sex)) {
            Sex.MALE -> db.sexMale.isChecked = true
            Sex.FEMALE -> db.sexFemale.isChecked = true
            Sex.OTHER -> db.sexOther.isChecked = true
        }
        when (WeightGoal.valueOf(p.goal)) {
            WeightGoal.LOSE -> db.goalLose.isChecked = true
            WeightGoal.MAINTAIN -> db.goalMaintain.isChecked = true
            WeightGoal.GAIN -> db.goalGain.isChecked = true
        }
        applyUnitsToFields(unit)

        db.unitToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            unit = if (checkedId == R.id.unitMetric) UnitSystem.METRIC else UnitSystem.IMPERIAL
            applyUnitsToFields(unit)
        }
        db.sexChips.setOnCheckedStateChangeListener { _, _ -> refreshSuggestion() }
        listOf(
            db.heightInput, db.heightFeetInput, db.heightInchesInput, db.goalWeightInput
        ).forEach { field ->
            field.doAfterTextChanged { if (!applyingUnits) refreshSuggestion() }
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit profile")
            .setView(db.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val weightVal = db.weightInput.text?.toString()?.toDoubleOrNull() ?: 0.0
                val weightKg = if (unit == UnitSystem.IMPERIAL) Units.lbToKg(weightVal) else weightVal
                val heightCm = liveHeightCm()
                val goalKg = liveGoalKg()
                val sex = liveSex()
                val goal = when (db.goalChips.checkedChipId) {
                    R.id.goalMaintain -> WeightGoal.MAINTAIN
                    R.id.goalGain -> WeightGoal.GAIN
                    else -> WeightGoal.LOSE
                }
                val activity = activityValues.getOrElse(
                    activityLabels.indexOf(db.activityInput.text?.toString())
                ) { ActivityLevel.MODERATE }
                val diet = dietValues.getOrElse(
                    dietLabels.indexOf(db.dietInput.text?.toString())
                ) { DietType.BALANCED }
                val selectedRestrictions = buildSet {
                    for (i in 0 until db.restrictionChips.childCount) {
                        val chip = db.restrictionChips.getChildAt(i) as Chip
                        if (chip.isChecked) add(chip.tag as String)
                    }
                }

                viewModel.save(
                    p.copy(
                        displayName = db.nameInput.text?.toString()?.ifBlank { "Athlete" } ?: "Athlete",
                        sex = sex.name,
                        birthYear = db.birthYearInput.text?.toString()?.toIntOrNull() ?: p.birthYear,
                        heightCm = if (heightCm > 0) heightCm else p.heightCm,
                        weightKg = if (weightKg > 0) weightKg else p.weightKg,
                        goalWeightKg = if (goalKg > 0) goalKg else p.goalWeightKg,
                        activityLevel = activity.name,
                        goal = goal.name,
                        unitSystem = unit.name,
                        dietType = diet.name,
                        dietaryRestrictions = DietaryRestrictions.join(selectedRestrictions)
                    )
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showScrollable(db.root)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
