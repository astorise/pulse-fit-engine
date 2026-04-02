package com.astor.pulsefitengine

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.astor.pulsefitengine.databinding.ActivityMainBinding
import com.astor.pulsefitengine.ui.GarminMetricsAdapter
import com.astor.pulsefitengine.ui.MainUiState
import com.astor.pulsefitengine.ui.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val adapter = GarminMetricsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        collectState()
    }

    private fun setupRecyclerView() {
        val spanCount = when {
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE -> 3
            resources.configuration.screenWidthDp >= 600 -> 3
            else -> 2
        }
        binding.metricsRecyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.metricsRecyclerView.adapter = adapter
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: MainUiState) {
        binding.sourceTextView.text = state.sourceLabel
        binding.transportTextView.text = state.transportStatus
        binding.lastUpdateTextView.text = state.lastUpdate
        adapter.submitList(state.metrics)
    }
}
