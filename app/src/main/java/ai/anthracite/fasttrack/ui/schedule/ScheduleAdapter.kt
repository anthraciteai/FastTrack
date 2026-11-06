package ai.anthracite.fasttrack.ui.schedule

import ai.anthracite.fasttrack.data.local.ScheduleEvent
import ai.anthracite.fasttrack.databinding.ItemScheduleEventBinding
import ai.anthracite.fasttrack.util.TimeFmt
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleAdapter(
    private val onLongPress: (Long) -> Unit
) : ListAdapter<ScheduleEvent, ScheduleAdapter.VH>(DIFF) {

    private val hour = SimpleDateFormat("h:mm", Locale.US)
    private val amPm = SimpleDateFormat("a", Locale.US)

    class VH(val binding: ItemScheduleEventBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemScheduleEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        val b = holder.binding
        b.timeText.text = hour.format(Date(e.startEpochMs))
        b.amPmText.text = amPm.format(Date(e.startEpochMs))
        b.titleText.text = e.title
        val type = e.type.lowercase().replaceFirstChar { it.uppercase() }
        b.subtitleText.text = "${TimeFmt.dayShort(e.startEpochMs)} · $type"
        b.syncedIcon.visibility = if (e.synced) View.VISIBLE else View.GONE
        b.root.setOnLongClickListener { onLongPress(e.id); true }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScheduleEvent>() {
            override fun areItemsTheSame(a: ScheduleEvent, b: ScheduleEvent) = a.id == b.id
            override fun areContentsTheSame(a: ScheduleEvent, b: ScheduleEvent) = a == b
        }
    }
}
