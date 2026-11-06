package ai.anthracite.fasttrack.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    private val clock = SimpleDateFormat("h:mm a", Locale.US)
    private val dayFull = SimpleDateFormat("EEEE, MMM d", Locale.US)
    private val dayShort = SimpleDateFormat("EEE, MMM d", Locale.US)
    private val dateTime = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US)

    /** Milliseconds -> HH:MM:SS. */
    fun hms(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** Milliseconds -> "6h 24m". */
    fun hmShort(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun clock(epochMs: Long): String = clock.format(Date(epochMs))
    fun dayFull(epochMs: Long): String = dayFull.format(Date(epochMs))
    fun dayShort(epochMs: Long): String = dayShort.format(Date(epochMs))
    fun dateTime(epochMs: Long): String = dateTime.format(Date(epochMs))
}
