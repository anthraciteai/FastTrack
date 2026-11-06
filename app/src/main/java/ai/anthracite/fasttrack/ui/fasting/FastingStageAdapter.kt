package ai.anthracite.fasttrack.ui.fasting

import ai.anthracite.fasttrack.data.health.FastingStage
import ai.anthracite.fasttrack.databinding.ItemFastingStageBinding
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/** Where the body is relative to a given stage. */
enum class StageUiState { REACHED, CURRENT, UPCOMING }

data class StageRow(
    val stage: FastingStage,
    val state: StageUiState,
    val isFirst: Boolean,
    val isLast: Boolean
)

class FastingStageAdapter : ListAdapter<StageRow, FastingStageAdapter.VH>(DIFF) {

    class VH(val binding: ItemFastingStageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFastingStageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val stage = row.stage
        val b = holder.binding
        val ctx = b.root.context

        val stageColor = ContextCompat.getColor(ctx, stage.colorRes)
        val surfaceVariant = MaterialColors.getColor(
            b.root, com.google.android.material.R.attr.colorSurfaceVariant
        )
        val outline = MaterialColors.getColor(
            b.root, com.google.android.material.R.attr.colorOutline
        )
        val onSurface = MaterialColors.getColor(
            b.root, com.google.android.material.R.attr.colorOnSurface
        )

        b.stageIcon.setImageResource(stage.iconRes)
        b.stageTitle.text = stage.title
        b.stageBody.text = stage.bodyState
        b.stageDesc.text = stage.description
        b.stageHour.text = formatHour(stage.startHour)

        // Connector line: hidden on the last row's lower half via clipping is overkill; keep full.
        b.stageConnector.visibility = if (row.isLast) View.INVISIBLE else View.VISIBLE

        when (row.state) {
            StageUiState.CURRENT -> {
                b.stageRow.alpha = 1f
                b.stageNode.backgroundTintList = ColorStateList.valueOf(stageColor)
                b.stageIcon.imageTintList = ColorStateList.valueOf(onContrast(stageColor))
                b.stageTitle.setTextColor(stageColor)
                b.stageBody.setTextColor(stageColor)
                b.stageConnector.setBackgroundColor(stageColor)
                b.stageNow.visibility = View.VISIBLE
                b.stageNow.backgroundTintList = ColorStateList.valueOf(stageColor)
            }
            StageUiState.REACHED -> {
                b.stageRow.alpha = 1f
                b.stageNode.backgroundTintList = ColorStateList.valueOf(withAlpha(stageColor, 0.20f))
                b.stageIcon.imageTintList = ColorStateList.valueOf(stageColor)
                b.stageTitle.setTextColor(onSurface)
                b.stageBody.setTextColor(stageColor)
                b.stageConnector.setBackgroundColor(stageColor)
                b.stageNow.visibility = View.GONE
            }
            StageUiState.UPCOMING -> {
                b.stageRow.alpha = 0.55f
                b.stageNode.backgroundTintList = ColorStateList.valueOf(surfaceVariant)
                b.stageIcon.imageTintList = ColorStateList.valueOf(outline)
                b.stageTitle.setTextColor(onSurface)
                b.stageBody.setTextColor(outline)
                b.stageConnector.setBackgroundColor(outline)
                b.stageNow.visibility = View.GONE
            }
        }
    }

    private fun formatHour(hour: Double): String =
        if (hour == hour.toLong().toDouble()) "${hour.toLong()}h" else "${hour}h"

    /** Pick black or white for legibility on top of [bg]. */
    private fun onContrast(bg: Int): Int {
        val r = (bg shr 16 and 0xFF) / 255.0
        val g = (bg shr 8 and 0xFF) / 255.0
        val bl = (bg and 0xFF) / 255.0
        val luminance = 0.299 * r + 0.587 * g + 0.114 * bl
        return if (luminance > 0.6) 0xFF1A0F02.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StageRow>() {
            override fun areItemsTheSame(a: StageRow, b: StageRow) = a.stage.id == b.stage.id
            override fun areContentsTheSame(a: StageRow, b: StageRow) = a == b
        }
    }
}
