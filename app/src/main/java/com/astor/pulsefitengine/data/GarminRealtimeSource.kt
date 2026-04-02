package com.astor.pulsefitengine.data

import kotlinx.coroutines.flow.Flow

interface GarminRealtimeSource {
    val label: String
    val transportStatus: String

    fun stream(): Flow<List<GarminMetricSample>>
}
