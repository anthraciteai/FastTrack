package ai.anthracite.fasttrack.ui.fasting

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.Achievement
import ai.anthracite.fasttrack.databinding.ItemAchievementBinding
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class AchievementAdapter : ListAdapter<Achievement, AchievementAdapter.VH>(DIFF) {

    private val iconFor = mapOf(
        "first" to R.drawable.ic_check,
        "streak3" to R.drawable.ic_flame,
        "streak7" to R.drawable.ic_flame,
        "streak30" to R.drawable.ic_flame,
        "total10" to R.drawable.ic_trophy,
        "total25" to R.drawable.ic_trophy,
        "club16" to R.drawable.ic_fasting,
        "warrior" to R.drawable.ic_fasting
    )

    class VH(val binding: ItemAchievementBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAchievementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        b.badgeIcon.setImageResource(iconFor[a.id] ?: R.drawable.ic_trophy)
        b.badgeTitle.text = a.title

        val badge = b.badgeIcon.parent as android.widget.FrameLayout
        if (a.unlocked) {
            badge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.accent_fasting_container)
            )
            b.badgeIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.accent_fasting)
            )
            b.root.alpha = 1f
        } else {
            badge.backgroundTintList = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    b.root, com.google.android.material.R.attr.colorSurfaceVariant
                )
            )
            b.badgeIcon.imageTintList = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    b.root, com.google.android.material.R.attr.colorOutline
                )
            )
            b.root.alpha = 0.6f
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Achievement>() {
            override fun areItemsTheSame(a: Achievement, b: Achievement) = a.id == b.id
            override fun areContentsTheSame(a: Achievement, b: Achievement) = a == b
        }
    }
}
