package com.astor.pulsefitengine.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.astor.pulsefitengine.data.BluetoothGarminRealtimeSource
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
import java.util.LinkedHashMap
import java.util.Locale

class MainViewModel(
    private val realtimeSource: GarminRealtimeSource,
) : ViewModel() {
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    private val latestSamples = LinkedHashMap<GarminMetricType, GarminMetricSample>()

    private val _uiState = MutableStateFlow(
        MainUiState(
            sourceLabel = "Montre Garmin via BLE",
            transportStatus = "Preparation du transport temps reel...",
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
            realtimeSource.stream().collect { update ->
                update.samples.forEach { sample ->
                    latestSamples[sample.type] = sample
                }
                _uiState.value = MainUiState(
                    sourceLabel = update.sourceLabel,
                    transportStatus = update.transportStatus,
                    lastUpdate = "Derniere activite ${clockFormat.format(Date(update.updatedAtMillis))}",
                    metrics = buildMetricCards(latestSamples.values.toList()),
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

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(
                    BluetoothGarminRealtimeSource(application),
                ) as T
            }
            throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
        }
    }
}
