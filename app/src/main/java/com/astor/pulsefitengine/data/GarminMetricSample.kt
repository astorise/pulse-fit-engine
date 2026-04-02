package com.astor.pulsefitengine.data

data class GarminMetricSample(
    val type: GarminMetricType,
    val timestampMillis: Long = System.currentTimeMillis(),
    val numericValue: Float? = null,
    val textValue: String? = null,
    val unitOverride: String? = null,
)
