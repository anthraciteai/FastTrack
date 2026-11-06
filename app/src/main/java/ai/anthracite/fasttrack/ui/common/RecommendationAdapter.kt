package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.data.health.Recommendation
import ai.anthracite.fasttrack.databinding.ItemRecommendationBinding
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

class RecommendationAdapter : ListAdapter<Recommendation, RecommendationAdapter.VH>(DIFF) {

    class VH(val binding: ItemRecommendationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRecommendationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rec = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        val accent = ContextCompat.getColor(ctx, rec.accentRes)

        b.recIcon.setImageResource(rec.iconRes)
        b.recIcon.imageTintList = ColorStateList.valueOf(accent)
        b.recIconWrap.backgroundTintList =
            ColorStateList.valueOf(MaterialColors.compositeARGBWithAlpha(accent, 40))
        b.recTitle.text = rec.title
        b.recDetail.text = rec.detail
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Recommendation>() {
            override fun areItemsTheSame(a: Recommendation, b: Recommendation) = a.id == b.id
            override fun areContentsTheSame(a: Recommendation, b: Recommendation) = a == b
        }
    }
}
