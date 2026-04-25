package com.echoshield.echonode.core.contracts

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppState {
    LISTENING,
    BARRICADE,
    EVACUATE
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
    val threshold: Double,
    val modelGunshotConfidence: Float,
    val modelTopLabel: String,
    val serviceRunning: Boolean
)

data class EchoUiState(
    val appState: AppState = AppState.LISTENING,
    val connectedPeers: Int = 0,
    val detectionTelemetry: DetectionTelemetry = DetectionTelemetry(
        amplitude = 0.0,
        threshold = 450.0,
        modelGunshotConfidence = 0f,
        modelTopLabel = "unknown",
        serviceRunning = false
    ),
    val meshStatus: MeshStatus = MeshStatus.IDLE,
    val evacuationRoute: String = "EXIT 4",
    val threatZone: String = "NORTH HALL"
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
