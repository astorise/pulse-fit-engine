package com.astor.pulsefitengine.data

import java.util.Locale
import kotlin.math.roundToInt

object GarminMetricFormatter {
    private val frenchLocale = Locale.FRANCE

    fun formatValue(sample: GarminMetricSample): String {
        sample.textValue?.takeIf { it.isNotBlank() }?.let { return it }
        val value = sample.numericValue ?: return "--"
        return when (sample.type) {
            GarminMetricType.PACE,
            GarminMetricType.PACE_AVG,
            GarminMetricType.PACE_LAP,
            GarminMetricType.WORKOUT_INTERVAL_PACE -> formatPace(value)

            GarminMetricType.TIME,
            GarminMetricType.TIME_ACTIVE,
            GarminMetricType.TIME_ELAPSED,
            GarminMetricType.TIME_LAP,
            GarminMetricType.WORKOUT_TIME_TO_GO,
            GarminMetricType.WORKOUT_INTERVAL_TIMER,
            GarminMetricType.ESTIMATED_FINISH_TIME -> formatDuration(value)

            GarminMetricType.DISTANCE,
            GarminMetricType.DISTANCE_LAP,
            GarminMetricType.DISTANCE_SET,
            GarminMetricType.DISTANCE_REMAINING,
            GarminMetricType.WORKOUT_DIST_TO_GO -> String.format(frenchLocale, "%.2f km", value)

            GarminMetricType.SPEED,
            GarminMetricType.SPEED_AVG,
            GarminMetricType.WORKOUT_INTERVAL_SPEED -> String.format(frenchLocale, "%.1f km/h", value)

            GarminMetricType.HEART_RATE,
            GarminMetricType.HEART_RATE_AVG,
            GarminMetricType.HEART_RATE_LAP -> "${value.roundToInt()} bpm"

            GarminMetricType.SPO2 -> "${value.roundToInt()} %"

            GarminMetricType.POWER,
            GarminMetricType.POWER_AVG,
            GarminMetricType.POWER_LAP,
            GarminMetricType.WORKOUT_INTERVAL_POWER_AVG -> "${value.roundToInt()} W"

            GarminMetricType.CALORIES,
            GarminMetricType.ACTIVE_CALORIES,
            GarminMetricType.LAP_SUMMARY_CALORIES,
            GarminMetricType.WORKOUT_CALS_TO_GO -> "${value.roundToInt()} kcal"

            GarminMetricType.CADENCE,
            GarminMetricType.STROKE_RATE,
            GarminMetricType.BREATHING_RATE -> "${value.roundToInt()} rpm"

            GarminMetricType.STRESS,
            GarminMetricType.HEART_RATE_ZONE_NUMBER,
            GarminMetricType.POWER_ZONE,
            GarminMetricType.LAPS,
            GarminMetricType.STROKES,
            GarminMetricType.WORKOUT_REPS_TO_GO,
            GarminMetricType.WORKOUT_SET_COUNT,
            GarminMetricType.WORKOUT_SET_REPS,
            GarminMetricType.INTERVALS -> value.roundToInt().toString()

            else -> defaultFormat(sample, value)
        }
    }

    private fun defaultFormat(sample: GarminMetricSample, value: Float): String {
        val suffix = sample.unitOverride ?: sample.type.defaultUnit
        val text = if (value % 1f == 0f) {
            value.roundToInt().toString()
        } else {
            String.format(frenchLocale, "%.1f", value)
        }
        return if (suffix.isBlank()) text else "$text $suffix"
    }

    private fun formatDuration(value: Float): String {
        val totalSeconds = value.roundToInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(frenchLocale, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(frenchLocale, "%02d:%02d", minutes, seconds)
        }
    }

    private fun formatPace(minutesPerKm: Float): String {
        val totalSeconds = (minutesPerKm * 60f).roundToInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(frenchLocale, "%d:%02d /km", minutes, seconds)
    }
}
