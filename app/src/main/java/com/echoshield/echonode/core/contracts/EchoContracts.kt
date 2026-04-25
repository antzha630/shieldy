package com.echoshield.echonode.core.contracts

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppState {
    LISTENING,
    LOCATION_CONFIRMATION,
    SAFETY_CHECK,
    INCIDENT_REPORT,
    BARRICADE,
    EVACUATE
}

enum class SafetyStatus {
    SAFE,
    INJURED,
    UNKNOWN
}

enum class MeshStatus {
    IDLE,
    ADVERTISING,
    DISCOVERING,
    CONNECTED,
    ERROR
}

data class DetectionTelemetry(
    val amplitude: Double,
    val smoothedAmplitude: Double,
    val threshold: Double,
    val gateOpen: Boolean,
    val modelGunshotConfidence: Float,
    val modelTopLabel: String,
    val cooldownRemainingMs: Long,
    val serviceRunning: Boolean
)

data class EchoUiState(
    val appState: AppState = AppState.LISTENING,
    val connectedPeers: Int = 0,
    val detectionTelemetry: DetectionTelemetry = DetectionTelemetry(
        amplitude = 0.0,
        smoothedAmplitude = 0.0,
        threshold = 450.0,
        gateOpen = false,
        modelGunshotConfidence = 0f,
        modelTopLabel = "unknown",
        cooldownRemainingMs = 0L,
        serviceRunning = false
    ),
    val meshStatus: MeshStatus = MeshStatus.IDLE,
    val evacuationRoute: String = "EXIT 4",
    val threatZone: String = "NORTH HALL",
    val locationLabel: String = "Detecting location...",
    val locationTimestamp: String = "",
    val locationConfirmed: Boolean = false,
    val safetyStatus: SafetyStatus = SafetyStatus.UNKNOWN,
    val companionsCount: Int = 0,
    val injuredCount: Int = 0,
    val incidentNotes: String = ""
)

interface SensorGateway {
    val localTriggerEvents: SharedFlow<Unit>
    val telemetry: StateFlow<DetectionTelemetry>
    fun setDetectionThreshold(threshold: Double)
}

interface MeshGateway {
    val incomingAlerts: SharedFlow<String>
    val connectedPeers: StateFlow<Int>
    val meshStatus: StateFlow<MeshStatus>
    fun startMesh()
    fun stopMesh()
    fun broadcastThreat(zone: String)
    fun broadcastEvacuate(route: String)
    fun broadcastAllClear()
}
