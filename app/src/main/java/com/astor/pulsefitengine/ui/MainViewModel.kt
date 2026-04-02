package com.astor.pulsefitengine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astor.pulsefitengine.data.DemoGarminRealtimeSource
import com.astor.pulsefitengine.data.GarminMetricFormatter
import com.astor.pulsefitengine.data.GarminMetricSample
import com.astor.pulsefitengine.data.GarminMetricType
import com.astor.pulsefitengine.data.GarminRealtimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(
    private val realtimeSource: GarminRealtimeSource = DemoGarminRealtimeSource(),
) : ViewModel() {
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    private val _uiState = MutableStateFlow(
        MainUiState(
            sourceLabel = realtimeSource.label,
            transportStatus = realtimeSource.transportStatus,
            lastUpdate = "En attente du premier paquet",
            metrics = GarminMetricType.featured.map { type ->
                MetricCardUiModel(
                    wireId = type.wireId,
                    title = type.displayName,
                    wireName = type.wireName,
                    value = "--",
                    updatedAt = "pas encore recu",
                )
            },
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            realtimeSource.stream().collect { samples ->
                _uiState.value = _uiState.value.copy(
                    lastUpdate = "Derniere trame ${clockFormat.format(Date())}",
                    metrics = buildMetricCards(samples),
                )
            }
        }
    }

    private fun buildMetricCards(samples: List<GarminMetricSample>): List<MetricCardUiModel> {
        val samplesByType = samples.associateBy { it.type }
        return GarminMetricType.featured.map { type ->
            val sample = samplesByType[type]
            MetricCardUiModel(
                wireId = type.wireId,
                title = type.displayName,
                wireName = type.wireName,
                value = sample?.let(GarminMetricFormatter::formatValue) ?: "--",
                updatedAt = sample?.timestampMillis?.let { "maj ${clockFormat.format(Date(it))}" }
                    ?: "pas encore recu",
            )
        }
    }
}
