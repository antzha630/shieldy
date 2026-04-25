package com.echoshield.echonode.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echoshield.echonode.core.AppContainer
import com.echoshield.echonode.core.contracts.AppState
import com.echoshield.echonode.core.contracts.EchoUiState
import com.echoshield.echonode.core.contracts.MeshStatus
import com.echoshield.echonode.experience.EchoOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = AppContainer(application.applicationContext)
    private val orchestrator = EchoOrchestrator(
        sensorGateway = container.sensorGateway,
        meshGateway = container.meshGateway
    )

    data class UiState(
        val appState: AppState = AppState.LISTENING,
        val connectedPeers: Int = 0,
        val currentAmplitude: Double = 0.0,
        val detectionThreshold: Double = 450.0,
        val modelGunshotConfidence: Float = 0f,
        val modelTopLabel: String = "unknown",
        val isServiceRunning: Boolean = false,
        val meshStatus: MeshStatus = MeshStatus.IDLE,
        val evacuationRoute: String = "EXIT 4",
        val threatZone: String = "NORTH HALL"
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        orchestrator.bind(viewModelScope)
        viewModelScope.launch {
            orchestrator.uiState.collect { _uiState.value = map(it) }
        }
    }

    fun triggerManualDebugAlert() {
        orchestrator.triggerManualDebugAlert()
    }

    fun toggleBarricadeEvacuate() {
        orchestrator.toggleBarricadeEvacuate()
    }

    fun resetAlert() {
        orchestrator.resetAlert()
    }

    fun startMesh() {
        orchestrator.startMesh()
    }

    fun stopMesh() {
        orchestrator.stopMesh()
    }

    fun setDetectionThreshold(threshold: Double) {
        orchestrator.setDetectionThreshold(threshold)
    }

    fun triggerEvacuate(route: String = "SOUTH - EXIT 4") {
        orchestrator.triggerEvacuate(route)
    }

    override fun onCleared() {
        super.onCleared()
        orchestrator.stopMesh()
    }

    private fun map(source: EchoUiState): UiState {
        return UiState(
            appState = source.appState,
            connectedPeers = source.connectedPeers,
            currentAmplitude = source.detectionTelemetry.amplitude,
            detectionThreshold = source.detectionTelemetry.threshold,
            modelGunshotConfidence = source.detectionTelemetry.modelGunshotConfidence,
            modelTopLabel = source.detectionTelemetry.modelTopLabel,
            isServiceRunning = source.detectionTelemetry.serviceRunning,
            meshStatus = source.meshStatus,
            evacuationRoute = source.evacuationRoute,
            threatZone = source.threatZone
        )
    }
}
