package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.databinding.ItemLogEntryBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** A generic row rendered by [LogEntryAdapter], reused by water / nutrition / exercise. */
data class LogRow(
    val id: Long,
    @DrawableRes val icon: Int,
    @ColorInt val badgeTint: Int,
    @ColorInt val iconTint: Int,
    val title: String,
    val subtitle: String,
    val value: String
)

class LogEntryAdapter(
    private val onClick: ((Long) -> Unit)? = null,
    private val onDelete: (Long) -> Unit
) : ListAdapter<LogRow, LogEntryAdapter.VH>(DIFF) {

    class VH(val binding: ItemLogEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        val b = holder.binding
        b.leadingIcon.setImageResource(row.icon)
        b.leadingBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(row.badgeTint)
        b.leadingIcon.imageTintList = android.content.res.ColorStateList.valueOf(row.iconTint)
        b.titleText.text = row.title
        b.subtitleText.text = row.subtitle
        b.valueText.text = row.value
        b.deleteButton.setOnClickListener { onDelete(row.id) }
        if (onClick != null) {
            b.root.isClickable = true
            b.root.setOnClickListener { onClick.invoke(row.id) }
        } else {
            b.root.setOnClickListener(null)
            b.root.isClickable = false
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LogRow>() {
            override fun areItemsTheSame(a: LogRow, b: LogRow) = a.id == b.id
            override fun areContentsTheSame(a: LogRow, b: LogRow) = a == b
        }

        fun color(context: android.content.Context, @ColorInt resolved: Int) = resolved
        fun colorRes(context: android.content.Context, res: Int) =
            ContextCompat.getColor(context, res)
    }
}
