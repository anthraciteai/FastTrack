package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.DialogLogWeightBinding
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * A single-field weigh-in dialog. Shows and accepts the user's preferred unit (lb or kg), but
 * hands the caller a canonical kilogram value via [onSave].
 */
object WeightLogDialog {

    fun show(
        context: Context,
        system: UnitSystem,
        currentWeightKg: Double,
        lastLoggedKg: Double?,
        onSave: (weightKg: Double) -> Unit
    ) {
        val binding = DialogLogWeightBinding.inflate(LayoutInflater.from(context))
        val imperial = system == UnitSystem.IMPERIAL
        val unitLabel = if (imperial) "lb" else "kg"
        binding.weightInputLayout.suffixText = unitLabel

        val prefill = if (imperial) Units.kgToLb(currentWeightKg) else currentWeightKg
        binding.weightInput.setText(String.format(Locale.US, "%.1f", prefill))

        lastLoggedKg?.let {
            binding.weightLastLogged.visibility = View.VISIBLE
            binding.weightLastLogged.text =
                context.getString(R.string.weight_last_logged, Units.weightLabel(it, system))
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.log_weight_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.weight_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = binding.weightInput.text?.toString()?.trim()?.toDoubleOrNull()
                if (entered == null || entered <= 0.0) {
                    binding.weightInputLayout.error = context.getString(R.string.weight_invalid)
                    return@setOnClickListener
                }
                val kg = if (imperial) Units.lbToKg(entered) else entered
                onSave(kg)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
