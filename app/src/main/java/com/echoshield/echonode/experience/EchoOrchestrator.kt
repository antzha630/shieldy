package com.echoshield.echonode.experience

import com.echoshield.echonode.core.contracts.AppState
import com.echoshield.echonode.core.contracts.EchoUiState
import com.echoshield.echonode.core.contracts.MeshGateway
import com.echoshield.echonode.core.contracts.SafetyStatus
import com.echoshield.echonode.core.contracts.SensorGateway
import com.echoshield.echonode.sensor.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EchoOrchestrator(
    private val sensorGateway: SensorGateway,
    private val meshGateway: MeshGateway,
    private val locationProvider: LocationProvider? = null
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

        locationProvider?.let { provider ->
            provider.startLocationUpdates()
            scope.launch {
                provider.currentLocation.collect { location ->
                    _uiState.value = _uiState.value.copy(
                        locationLabel = location.label,
                        locationTimestamp = location.timestamp
                    )
                }
            }
        }
    }

    fun startMesh() = meshGateway.startMesh()

    fun stopMesh() = meshGateway.stopMesh()

    fun setDetectionThreshold(threshold: Double) = sensorGateway.setDetectionThreshold(threshold)

    fun triggerManualDebugAlert() {
        transitionToIncidentFlow()
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
            AppState.LOCATION_CONFIRMATION -> transitionToBarricade()
            AppState.SAFETY_CHECK -> transitionToBarricade()
            AppState.INCIDENT_REPORT -> transitionToBarricade()
        }
    }

    fun resetAlert() {
        _uiState.value = _uiState.value.copy(
            appState = AppState.LISTENING,
            locationConfirmed = false,
            safetyStatus = SafetyStatus.UNKNOWN,
            companionsCount = 0,
            injuredCount = 0,
            incidentNotes = ""
        )
        meshGateway.broadcastAllClear()
    }

    fun confirmLocation(isConfirmed: Boolean) {
        _uiState.value = _uiState.value.copy(
            appState = AppState.SAFETY_CHECK,
            locationConfirmed = isConfirmed
        )
    }

    fun setSafetyStatus(status: SafetyStatus) {
        _uiState.value = _uiState.value.copy(safetyStatus = status)
    }

    fun continueToIncidentReport() {
        _uiState.value = _uiState.value.copy(appState = AppState.INCIDENT_REPORT)
    }

    fun setCompanionsCount(count: Int) {
        _uiState.value = _uiState.value.copy(companionsCount = count.coerceAtLeast(0))
    }

    fun setInjuredCount(count: Int) {
        _uiState.value = _uiState.value.copy(injuredCount = count.coerceAtLeast(0))
    }

    fun setIncidentNotes(notes: String) {
        _uiState.value = _uiState.value.copy(incidentNotes = notes)
    }

    fun submitIncidentReport() {
        meshGateway.broadcastThreat(_uiState.value.threatZone)
        transitionToBarricade()
    }

    fun quickBarricade() {
        transitionToBarricade()
    }

    fun quickEvacuate(route: String = "SOUTH - EXIT 4") {
        triggerEvacuate(route)
    }

    private fun handleMeshPayload(payload: String) {
        when {
            payload.startsWith("ALERT:THREAT_DETECTED") -> transitionToIncidentFlow()
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

    private fun transitionToIncidentFlow() {
        _uiState.value = _uiState.value.copy(
            appState = AppState.LOCATION_CONFIRMATION,
            locationConfirmed = false,
            safetyStatus = SafetyStatus.UNKNOWN,
            companionsCount = 0,
            injuredCount = 0,
            incidentNotes = ""
        )
    }
}
