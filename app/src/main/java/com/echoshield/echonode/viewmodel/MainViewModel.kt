package com.echoshield.echonode.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echoshield.echonode.core.AppContainer
import com.echoshield.echonode.core.contracts.AppState
import com.echoshield.echonode.core.contracts.EchoUiState
import com.echoshield.echonode.core.contracts.MeshStatus
import com.echoshield.echonode.core.contracts.SafetyStatus
import com.echoshield.echonode.experience.EchoOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = AppContainer(application.applicationContext)
    private val orchestrator = EchoOrchestrator(
        sensorGateway = container.sensorGateway,
        meshGateway = container.meshGateway,
        locationProvider = container.locationProvider
    )

    data class UiState(
        val appState: AppState = AppState.LISTENING,
        val connectedPeers: Int = 0,
        val currentAmplitude: Double = 0.0,
        val smoothedAmplitude: Double = 0.0,
        val detectionThreshold: Double = 200.0,
        val gateOpen: Boolean = false,
        val modelGunshotConfidence: Float = 0f,
        val modelTopLabel: String = "",
        val cooldownRemainingMs: Long = 0L,
        val isServiceRunning: Boolean = false,
        val meshStatus: MeshStatus = MeshStatus.IDLE,
        val evacuationRoute: String = "",
        val threatZone: String = "",
        val locationLabel: String = "",
        val relativeLocation: String = "",
        val coordinateText: String = "",
        val locationTimestamp: String = "",
        val locationLatitude: Double = 0.0,
        val locationLongitude: Double = 0.0,
        val locationConfirmed: Boolean = false,
        val safetyStatus: SafetyStatus = SafetyStatus.UNKNOWN,
        val companionsCount: Int = 0,
        val injuredCount: Int = 0,
        val roomNumber: String = "",
        val incidentNotes: String = ""
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

    fun toggleBarricadeEvacuate(broadcastToPeers: Boolean = false) {
        orchestrator.toggleBarricadeEvacuate(broadcastToPeers)
    }

    fun resetAlert(broadcastToPeers: Boolean = false) {
        orchestrator.resetAlert(broadcastToPeers)
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

    fun triggerEvacuate(route: String = "SOUTH - EXIT 4", broadcastToPeers: Boolean = false) {
        orchestrator.triggerEvacuate(route, broadcastToPeers)
    }

    fun confirmLocation(isConfirmed: Boolean) {
        orchestrator.confirmLocation(isConfirmed)
    }

    fun selectSafetyStatus(status: SafetyStatus) {
        orchestrator.setSafetyStatus(status)
    }

    fun continueToIncidentReport() {
        orchestrator.continueToIncidentReport()
    }

    fun setCompanionsCount(count: Int) {
        orchestrator.setCompanionsCount(count)
    }

    fun setInjuredCount(count: Int) {
        orchestrator.setInjuredCount(count)
    }

    fun setIncidentNotes(notes: String) {
        orchestrator.setIncidentNotes(notes)
    }

    fun setRoomNumber(room: String) {
        orchestrator.setRoomNumber(room)
    }

    fun submitIncidentReport() {
        orchestrator.submitIncidentReport()
    }

    fun quickBarricade() {
        orchestrator.quickBarricade()
    }

    fun quickEvacuate(route: String = "SOUTH - EXIT 4", broadcastToPeers: Boolean = false) {
        orchestrator.quickEvacuate(route, broadcastToPeers)
    }

    fun goBackToLocation() {
        orchestrator.goBackToLocation()
    }

    fun goBackToSafetyCheck() {
        orchestrator.goBackToSafetyCheck()
    }

    private fun map(source: EchoUiState): UiState {
        return UiState(
            appState = source.appState,
            connectedPeers = source.connectedPeers,
            currentAmplitude = source.detectionTelemetry.amplitude,
            smoothedAmplitude = source.detectionTelemetry.smoothedAmplitude,
            detectionThreshold = source.detectionTelemetry.threshold,
            gateOpen = source.detectionTelemetry.gateOpen,
            modelGunshotConfidence = source.detectionTelemetry.modelGunshotConfidence,
            modelTopLabel = source.detectionTelemetry.modelTopLabel,
            cooldownRemainingMs = source.detectionTelemetry.cooldownRemainingMs,
            isServiceRunning = source.detectionTelemetry.serviceRunning,
            meshStatus = source.meshStatus,
            evacuationRoute = source.evacuationRoute,
            threatZone = source.threatZone,
            locationLabel = source.locationLabel,
            relativeLocation = source.relativeLocation,
            coordinateText = source.coordinateText,
            locationTimestamp = source.locationTimestamp,
            locationLatitude = source.locationLatitude,
            locationLongitude = source.locationLongitude,
            locationConfirmed = source.locationConfirmed,
            safetyStatus = source.safetyStatus,
            companionsCount = source.companionsCount,
            injuredCount = source.injuredCount,
            roomNumber = source.roomNumber,
            incidentNotes = source.incidentNotes
        )
    }
}
