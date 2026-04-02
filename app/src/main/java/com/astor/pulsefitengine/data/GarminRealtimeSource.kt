package com.astor.pulsefitengine.data

import kotlinx.coroutines.flow.Flow

data class GarminRealtimeUpdate(
    val sourceLabel: String,
    val transportStatus: String,
    val samples: List<GarminMetricSample>,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

interface GarminRealtimeSource {
    fun stream(): Flow<GarminRealtimeUpdate>
}
