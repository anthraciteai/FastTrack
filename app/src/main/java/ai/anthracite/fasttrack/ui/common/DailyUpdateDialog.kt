package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.CoachContext
import ai.anthracite.fasttrack.data.health.DayRecap
import ai.anthracite.fasttrack.data.health.Recommendation
import ai.anthracite.fasttrack.databinding.DialogDailyUpdateBinding
import ai.anthracite.fasttrack.databinding.ItemRecommendationBinding
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The once-a-day "daily update" popup: the dated header, login streak, how the most recent day
 * went (recap), and today's focus (recommendations). The same content is archived and revisitable
 * from Profile ▸ Daily update.
 */
object DailyUpdateDialog {

    /** "Your weekly review is ready" row, shown Sun–Tue until the review is opened. */
    class WeeklyLink(val headline: String, val onOpen: () -> Unit)

    fun show(
        context: Context,
        dateLabel: String,
        streakLine: String,
        recap: DayRecap,
        recommendations: List<Recommendation>,
        weekly: WeeklyLink? = null
    ) {
        val binding = DialogDailyUpdateBinding.inflate(LayoutInflater.from(context))
        binding.duDate.text = dateLabel
        binding.duStreak.text = streakLine

        if (recap.hasData) {
            binding.duRecapSection.visibility = View.VISIBLE
            binding.duRecapDate.text = recap.dateLabel
            binding.duRecapHeadline.text = recap.headline
            binding.duRecapSummary.text = recap.summary
            binding.duRecapStats.bindRecapStats(recap.stats)
        } else {
            binding.duRecapSection.visibility = View.GONE
        }

        if (recommendations.isNotEmpty()) {
            binding.duRecsSection.visibility = View.VISIBLE
            bindRecommendations(binding.duRecsContainer, recommendations)
        } else {
            binding.duRecsSection.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()
        binding.duButton.setOnClickListener { dialog.dismiss() }
        bindWeekly(binding, dialog, weekly)
        dialog.show()
    }

    private fun bindWeekly(binding: DialogDailyUpdateBinding, dialog: AlertDialog, weekly: WeeklyLink?) {
        binding.duWeeklyCard.visibility = if (weekly == null) View.GONE else View.VISIBLE
        if (weekly == null) return
        binding.duWeeklyHeadline.text = weekly.headline
        binding.duWeeklyCard.setOnClickListener {
            dialog.dismiss()
            weekly.onOpen()
        }
    }

    /**
     * The AI alternative to [show]: same dated header + recap, but the deterministic "focus today"
     * tips are replaced by a personalized, goal-aware note written on-device by the coach. The note
     * streams in after a brief spinner; if generation fails we fall back to the classic tips so the
     * user never gets an empty update. A "Chat with Coach" button continues the conversation.
     */
    fun showAi(
        context: Context,
        scope: CoroutineScope,
        app: FastTrackApp,
        dateLabel: String,
        streakLine: String,
        recap: DayRecap,
        recommendations: List<Recommendation>,
        weekly: WeeklyLink?,
        onChatWithCoach: () -> Unit
    ) {
        val binding = DialogDailyUpdateBinding.inflate(LayoutInflater.from(context))
        binding.duDate.text = dateLabel
        binding.duStreak.text = streakLine

        if (recap.hasData) {
            binding.duRecapSection.visibility = View.VISIBLE
            binding.duRecapDate.text = recap.dateLabel
            binding.duRecapHeadline.text = recap.headline
            binding.duRecapSummary.text = recap.summary
            binding.duRecapStats.bindRecapStats(recap.stats)
        } else {
            binding.duRecapSection.visibility = View.GONE
        }

        // AI note takes the place of the deterministic recommendations.
        binding.duRecsSection.visibility = View.GONE
        binding.duCoachSection.visibility = View.VISIBLE
        binding.duCoachLoading.visibility = View.VISIBLE
        binding.duCoachNote.visibility = View.GONE

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()
        binding.duButton.setOnClickListener { dialog.dismiss() }
        binding.duChatButton.setOnClickListener {
            dialog.dismiss()
            onChatWithCoach()
        }
        bindWeekly(binding, dialog, weekly)
        dialog.show()

        scope.launch {
            val note = runCatching { CoachContext.generateDailyUpdate(app) }.getOrNull()
            if (!dialog.isShowing) return@launch
            binding.duCoachLoading.visibility = View.GONE
            if (!note.isNullOrBlank()) {
                binding.duCoachNote.text = note
                binding.duCoachNote.visibility = View.VISIBLE
                binding.duChatButton.visibility = View.VISIBLE
            } else {
                // Fall back to the classic focus-today tips.
                binding.duCoachSection.visibility = View.GONE
                if (recommendations.isNotEmpty()) {
                    binding.duRecsSection.visibility = View.VISIBLE
                    bindRecommendations(binding.duRecsContainer, recommendations)
                }
            }
        }
    }

    private fun bindRecommendations(container: LinearLayout, recs: List<Recommendation>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        recs.forEach { rec ->
            val row = ItemRecommendationBinding.inflate(inflater, container, false)
            val accent = ContextCompat.getColor(container.context, rec.accentRes)
            row.recIcon.setImageResource(rec.iconRes)
            row.recIcon.imageTintList = ColorStateList.valueOf(accent)
            row.recIconWrap.backgroundTintList =
                ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(accent, 40))
            row.recTitle.text = rec.title
            row.recDetail.text = rec.detail
            container.addView(row.root)
        }
    }
}
