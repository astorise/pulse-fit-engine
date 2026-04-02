package com.astor.pulsefitengine.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

class DemoGarminRealtimeSource : GarminRealtimeSource {
    override val label: String = "Source simulée locale"
    override val transportStatus: String =
        "Décodage protobuf prêt, transport propriétaire montre↔téléphone à brancher"

    override fun stream(): Flow<List<GarminMetricSample>> = flow {
        var tick = 0
        while (true) {
            emit(buildSnapshot(tick))
            tick += 1
            delay(1_000)
        }
    }

    private fun buildSnapshot(tick: Int): List<GarminMetricSample> {
        val now = System.currentTimeMillis()
        val phase = tick / 4.0
        val distanceKm = 1.25f + (tick * 0.035f)
        val elapsedSeconds = tick * 5f
        return listOf(
            GarminMetricSample(
                type = GarminMetricType.HEART_RATE,
                timestampMillis = now,
                numericValue = 146f + (sin(phase) * 9f).toFloat(),
            ),
            GarminMetricSample(
                type = GarminMetricType.SPEED,
                timestampMillis = now,
                numericValue = 12.4f + (sin(phase / 2.0) * 0.8f).toFloat(),
            ),
            GarminMetricSample(
                type = GarminMetricType.PACE,
                timestampMillis = now,
                numericValue = 4.86f - (sin(phase / 3.0) * 0.15f).toFloat(),
            ),
            GarminMetricSample(
                type = GarminMetricType.POWER,
                timestampMillis = now,
                numericValue = 228f + (sin(phase * 1.1) * 18f).toFloat(),
            ),
            GarminMetricSample(
                type = GarminMetricType.DISTANCE,
                timestampMillis = now,
                numericValue = distanceKm,
            ),
            GarminMetricSample(
                type = GarminMetricType.ACTIVE_CALORIES,
                timestampMillis = now,
                numericValue = 92f + (tick * 3.8f),
            ),
            GarminMetricSample(
                type = GarminMetricType.BREATHING_RATE,
                timestampMillis = now,
                numericValue = 19f + (sin(phase * 0.7) * 2f).toFloat(),
            ),
            GarminMetricSample(
                type = GarminMetricType.STRESS,
                timestampMillis = now,
                numericValue = 28f + (sin(phase * 0.4) * 6f).toFloat(),
            ),
            GarminMetricSample(
                type = GarminMetricType.TIME_ELAPSED,
                timestampMillis = now,
                numericValue = elapsedSeconds,
            ),
        )
    }
}
