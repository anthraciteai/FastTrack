package ai.anthracite.fasttrack.ui.nutrition

import ai.anthracite.fasttrack.databinding.ItemFoodEditorBinding
import ai.anthracite.fasttrack.nutrition.NutritionCalculator
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Renders the editable list of [FoodItem]s in the meal editor with per-item calories + range. */
class FoodItemAdapter(
    private val onEdit: (FoodItem) -> Unit,
    private val onDelete: (FoodItem) -> Unit
) : ListAdapter<FoodItem, FoodItemAdapter.VH>(DIFF) {

    class VH(val binding: ItemFoodEditorBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFoodEditorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding

        val qty = FoodLabels.quantity(item.quantity)
        b.itemName.text = "$qty× ${item.name}"

        val unitLabel = FoodLabels.unit(item.portion.amount, item.portion.unit)
        val typeLabel = item.foodType.ifBlank { FoodLabels.category(item.category) }
        b.itemSubtitle.text = listOf(unitLabel, typeLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        b.itemBadges.text = FoodLabels.quality(item)

        val cal = NutritionCalculator.itemCalories(item)
        if (cal.estimate == null) {
            b.itemCalories.text = "—"
            b.itemCalorieRange.text = "unknown"
        } else {
            b.itemCalories.text = "${NutritionCalculator.round0(cal.estimate)}"
            val low = NutritionCalculator.round0(cal.low)
            val high = NutritionCalculator.round0(cal.high)
            b.itemCalorieRange.text =
                if (low != null && high != null && (low != high)) "$low–$high kcal" else "kcal"
        }

        b.itemCard.setOnClickListener { onEdit(item) }
        b.deleteItem.setOnClickListener { onDelete(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FoodItem>() {
            override fun areItemsTheSame(a: FoodItem, b: FoodItem) = a.id == b.id
            override fun areContentsTheSame(a: FoodItem, b: FoodItem) = a == b
        }
    }
}
