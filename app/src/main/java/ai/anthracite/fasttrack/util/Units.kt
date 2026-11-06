package ai.anthracite.fasttrack.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** User-selectable measurement system. Stored on the profile. */
enum class UnitSystem { IMPERIAL, METRIC }

/**
 * Central place for every unit conversion in the app so screens never do ad-hoc math.
 * Canonical storage is always metric (kg, ml, cm, meters); we convert only for display/input.
 */
object Units {

    // ---- Mass ----
    fun kgToLb(kg: Double): Double = kg * 2.2046226218
    fun lbToKg(lb: Double): Double = lb / 2.2046226218

    // ---- Volume ----
    fun mlToFlOz(ml: Double): Double = ml / 29.5735295625
    fun flOzToMl(oz: Double): Double = oz * 29.5735295625

    // ---- Length / height ----
    fun cmToIn(cm: Double): Double = cm / 2.54
    fun inToCm(inches: Double): Double = inches * 2.54

    // ---- Distance ----
    fun metersToMiles(m: Double): Double = m / 1609.344
    fun metersToKm(m: Double): Double = m / 1000.0

    // ---- Display helpers ----
    fun weightLabel(kg: Double, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> "${kgToLb(kg).roundToInt()} lb"
        UnitSystem.METRIC -> "${kg.roundToInt()} kg"
    }

    fun volumeLabel(ml: Double, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> "${mlToFlOz(ml).roundToInt()} oz"
        UnitSystem.METRIC -> "${ml.roundToInt()} ml"
    }

    /** "64 / 105 oz" — current vs goal with the unit shown once, so it stays on one line. */
    fun volumeRangeLabel(ml: Double, goalMl: Double, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> "${mlToFlOz(ml).roundToInt()} / ${mlToFlOz(goalMl).roundToInt()} oz"
        UnitSystem.METRIC -> "${ml.roundToInt()} / ${goalMl.roundToInt()} ml"
    }

    fun distanceLabel(meters: Double, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.2f mi", metersToMiles(meters))
        UnitSystem.METRIC -> String.format(Locale.US, "%.2f km", metersToKm(meters))
    }

    /** Distance in the user's preferred unit (miles or km) as a raw number. */
    fun distanceValue(meters: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) metersToMiles(meters) else metersToKm(meters)

    fun distanceUnit(system: UnitSystem): String =
        if (system == UnitSystem.IMPERIAL) "mi" else "km"

    /** Minutes per mile/km as a raw number (lower is faster). Null if distance is ~0. */
    fun paceMinPerUnit(meters: Double, durationMin: Int, system: UnitSystem): Double? {
        val dist = distanceValue(meters, system)
        if (dist <= 0.01 || durationMin <= 0) return null
        return durationMin / dist
    }

    /** "8:30 /mi" style label from a min-per-unit value. */
    fun paceLabel(minPerUnit: Double, system: UnitSystem): String {
        val totalSeconds = (minPerUnit * 60).roundToInt()
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d /%s", m, s, distanceUnit(system))
    }

    /** Whole feet and leftover inches from a stored centimetre height. */
    fun heightFeetInches(cm: Double): Pair<Int, Int> {
        val totalInches = cmToIn(cm).roundToInt().coerceAtLeast(0)
        return (totalInches / 12) to (totalInches % 12)
    }

    fun feetInchesToCm(feet: Int, inches: Int): Double =
        inToCm((feet.coerceAtLeast(0) * 12 + inches.coerceAtLeast(0)).toDouble())

    fun heightLabel(cm: Double, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> {
            val (ft, inch) = heightFeetInches(cm)
            if (inch == 0) "$ft ft" else "$ft ft $inch in"
        }
        UnitSystem.METRIC -> "${cm.roundToInt()} cm"
    }
}

/** yyyy-MM-dd key in the device's local timezone, used to group logs by calendar day. */
object DayKeys {
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun of(epochMs: Long): String = fmt.format(Date(epochMs))
    fun today(): String = of(System.currentTimeMillis())
    fun startMs(dayKey: String): Long = fmt.parse(dayKey)?.time ?: 0L
    fun endMs(dayKey: String): Long = startMs(dayKey) + 86_400_000L - 1L
}
