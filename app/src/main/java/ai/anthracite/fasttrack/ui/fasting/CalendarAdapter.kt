package ai.anthracite.fasttrack.ui.fasting

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.DayState
import ai.anthracite.fasttrack.databinding.ItemCalendarDayBinding
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** One cell in the fasting calendar. [dayNumber] == 0 renders an empty padding cell. */
data class DayCell(
    val dayNumber: Int,
    val dayKey: String = "",
    val state: DayState = DayState.NONE,
    val isToday: Boolean = false
)

class CalendarAdapter(
    private val onDayClick: (DayCell) -> Unit = {}
) : RecyclerView.Adapter<CalendarAdapter.VH>() {

    private var cells: List<DayCell> = emptyList()

    fun submit(newCells: List<DayCell>) {
        cells = newCells
        notifyDataSetChanged()
    }

    class VH(val binding: ItemCalendarDayBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = cells.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cell = cells[position]
        val tv = holder.binding.dayText
        val ctx = tv.context
        if (cell.dayNumber == 0) {
            tv.text = ""
            tv.background = null
            holder.itemView.setOnClickListener(null)
            return
        }
        holder.itemView.setOnClickListener { onDayClick(cell) }
        tv.text = cell.dayNumber.toString()
        when (cell.state) {
            DayState.COMPLETED -> {
                tv.setBackgroundResource(R.drawable.bg_icon_circle)
                tv.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.accent_fasting)
                )
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.white))
            }
            DayState.PARTIAL -> {
                tv.setBackgroundResource(R.drawable.bg_icon_circle)
                tv.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.accent_fasting_container)
                )
                tv.setTextColor(ContextCompat.getColor(ctx, R.color.accent_fasting))
            }
            DayState.NONE -> {
                if (cell.isToday) {
                    tv.setBackgroundResource(R.drawable.bg_calendar_today)
                    tv.backgroundTintList = null
                } else {
                    tv.background = null
                    tv.backgroundTintList = null
                }
                tv.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        tv, com.google.android.material.R.attr.colorOnSurface
                    )
                )
            }
        }
    }
}
