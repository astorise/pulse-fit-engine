package com.astor.pulsefitengine.ui

data class MetricCardUiModel(
    val wireId: Int,
    val title: String,
    val wireName: String,
    val value: String,
    val updatedAt: String,
)

data class MainUiState(
    val sourceLabel: String,
    val transportStatus: String,
    val lastUpdate: String,
    val metrics: List<MetricCardUiModel>,
)
