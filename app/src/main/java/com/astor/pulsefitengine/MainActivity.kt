package com.astor.pulsefitengine

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application)
    }
    private val adapter = GarminMetricsAdapter()
    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        collectState()
        requestBluetoothPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        requestBluetoothPermissionsIfNeeded()
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        val missingPermissions = requiredRuntimePermissions().filterNot(::isGranted)
        if (missingPermissions.isNotEmpty()) {
            permissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun requiredRuntimePermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
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
