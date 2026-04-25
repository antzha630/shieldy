package com.echoshield.echonode.experience

import com.echoshield.echonode.core.contracts.AppState
import com.echoshield.echonode.core.contracts.EchoUiState
import com.echoshield.echonode.core.contracts.MeshGateway
import com.echoshield.echonode.core.contracts.SensorGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EchoOrchestrator(
    private val sensorGateway: SensorGateway,
    private val meshGateway: MeshGateway
) {
    private val _uiState = MutableStateFlow(EchoUiState())
    val uiState: StateFlow<EchoUiState> = _uiState.asStateFlow()

    fun bind(scope: CoroutineScope) {
        scope.launch {
            sensorGateway.localTriggerEvents.collect {
                transitionToBarricade()
                meshGateway.broadcastThreat(_uiState.value.threatZone)
            }
        }

        scope.launch {
            sensorGateway.telemetry.collect { telemetry ->
                _uiState.value = _uiState.value.copy(detectionTelemetry = telemetry)
            }
        }

        scope.launch {
            meshGateway.connectedPeers.collect { peers ->
                _uiState.value = _uiState.value.copy(connectedPeers = peers)
            }
        }

        scope.launch {
            meshGateway.meshStatus.collect { status ->
                _uiState.value = _uiState.value.copy(meshStatus = status)
            }
        }

        scope.launch {
            meshGateway.incomingAlerts.collect { payload ->
                handleMeshPayload(payload)
            }
        }
    }

    fun startMesh() = meshGateway.startMesh()

    fun stopMesh() = meshGateway.stopMesh()

    fun setDetectionThreshold(threshold: Double) = sensorGateway.setDetectionThreshold(threshold)

    fun triggerManualDebugAlert() {
        transitionToBarricade()
        meshGateway.broadcastThreat(_uiState.value.threatZone)
    }

    fun triggerEvacuate(route: String = "SOUTH - EXIT 4") {
        _uiState.value = _uiState.value.copy(
            appState = AppState.EVACUATE,
            evacuationRoute = route
        )
        meshGateway.broadcastEvacuate(route)
    }

    fun toggleBarricadeEvacuate() {
        when (_uiState.value.appState) {
            AppState.BARRICADE -> triggerEvacuate()
            AppState.EVACUATE -> transitionToBarricade()
            AppState.LISTENING -> transitionToBarricade()
        }
    }

    fun resetAlert() {
        _uiState.value = _uiState.value.copy(appState = AppState.LISTENING)
        meshGateway.broadcastAllClear()
    }

    private fun handleMeshPayload(payload: String) {
        when {
            payload.startsWith("ALERT:THREAT_DETECTED") -> transitionToBarricade()
            payload.startsWith("ALERT:ALL_CLEAR") -> {
                _uiState.value = _uiState.value.copy(appState = AppState.LISTENING)
            }
            payload.startsWith("ALERT:EVACUATE") -> {
                val route = payload.split("|").getOrNull(2) ?: "SOUTH - EXIT 4"
                _uiState.value = _uiState.value.copy(
                    appState = AppState.EVACUATE,
                    evacuationRoute = route
                )
            }
        }
    }

    private fun transitionToBarricade() {
        _uiState.value = _uiState.value.copy(appState = AppState.BARRICADE)
    }
}
