package com.echoshield.echonode.core.contracts

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope

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
    val serviceRunning: Boolean,
    // Microphone saturation. A close gunshot pins a phone mic to the 16-bit rails,
    // which both signals a nearby impulse and degrades the ML input waveform.
    val clippingDetected: Boolean = false,
    val clipFraction: Float = 0f
)

/**
 * Live view of the anti-herding vote for the current detection session, so the UI can
 * show *why* the fleet has or has not escalated instead of a silent yes/no.
 */
data class ConsensusSnapshot(
    val sessionId: String = "",
    val active: Boolean = false,
    val confirmations: Int = 0,
    val independentConfirmations: Int = 0,
    val totalVotes: Int = 0,
    val required: Int = 1,
    val startedAtMs: Long = 0L
)

/** Triangulated origin of a confirmed detection, with its honest error bounds. */
data class SourceEstimateInfo(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = Double.NaN,
    /** Storeys above the lowest confirming phone, from altitude spread. */
    val floorOffset: Int = 0,
    /** "tdoa_multilateration" | "weighted_centroid" | "single_report" */
    val method: String = "single_report",
    val contributingNodes: Int = 0,
    /** Radius covering the confirming cluster: a crude confidence bound, in metres. */
    val spreadMeters: Double = 0.0,
    /** RMS timing residual of the TDoA solve; NaN when no TDoA fix was possible. */
    val timingResidualMs: Double = Double.NaN
) {
    val isTriangulated: Boolean get() = method == "tdoa_multilateration"
}

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
    val incidentNotes: String = "",
    val threatLatitude: Double = 0.0,
    val threatLongitude: Double = 0.0,
    val threatRadiusMeters: Double = 80.0,
    val threatZones: List<ThreatZone> = emptyList(),
    val serverIncidentId: String = "",
    val serverRecommendedAction: String = "",
    val serverPoliceBrief: String = "",
    val serverMedicalBrief: String = "",
    val liveUpdates: List<String> = emptyList(),
    val conversationMessages: List<ConversationMessage> = emptyList(),
    val consensus: ConsensusSnapshot = ConsensusSnapshot(),
    val sourceEstimate: SourceEstimateInfo? = null,
    val sentinelActive: Boolean = false,
    val firstAidSteps: List<FirstAidStep> = emptyList()
)

/** One piece of bystander first-aid guidance, ordered by what saves lives soonest. */
data class FirstAidStep(
    val title: String,
    val detail: String
)

data class ThreatZone(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
    val confidence: Float = 0.5f,
    val source: String = "server"
)

data class ConversationMessage(
    val id: String,
    val sender: String,
    val role: String,
    val message: String,
    val at: String
)

interface SensorGateway {
    val localTriggerEvents: SharedFlow<Unit>
    val telemetry: StateFlow<DetectionTelemetry>
    fun setDetectionThreshold(threshold: Double)
}

data class WakeClassifyEvent(
    val sessionId: String,
    val sourceNodeId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

data class ResponseTriggerEvent(
    val sessionId: String,
    val confirmedByNodes: List<String>,
    /** Position of the node that raised the alarm (fallback only). */
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    /** Triangulated source; null when no confirming node reported a usable fix. */
    val estimate: SourceEstimateInfo? = null
) {
    /** Best available source position: triangulated when we have it, raiser otherwise. */
    val sourceLatitude: Double get() = estimate?.latitude ?: latitude
    val sourceLongitude: Double get() = estimate?.longitude ?: longitude
}

data class IncidentReportEvent(
    val appState: AppState,
    val safetyStatus: SafetyStatus,
    val injuredCount: Int,
    val companionsCount: Int,
    val roomNumber: String,
    val notes: String,
    val latitude: Double,
    val longitude: Double,
    val locationLabel: String,
    val relativeLocation: String,
    val threatLatitude: Double?,
    val threatLongitude: Double?,
    val threatSessionId: String?,
    val submittedAtMs: Long
)

interface MeshGateway {
    val incomingAlerts: SharedFlow<String>
    val connectedPeers: StateFlow<Int>
    val meshStatus: StateFlow<MeshStatus>

    val wakeClassifyRequests: SharedFlow<WakeClassifyEvent>
    val responseTriggered: SharedFlow<ResponseTriggerEvent>
    val sentinelDutyActive: StateFlow<Boolean>
    val consensusState: StateFlow<ConsensusSnapshot>

    fun startMesh()
    fun stopMesh()
    fun broadcastThreat(zone: String)
    fun broadcastEvacuate(route: String)
    fun broadcastAllClear()

    fun broadcastWakeClassify(latitude: Double, longitude: Double)
    fun submitClassifyVote(
        sessionId: String,
        isGunshot: Boolean,
        confidence: Float,
        latitude: Double = Double.NaN,
        longitude: Double = Double.NaN,
        altitude: Double = Double.NaN,
        detectedAtMs: Long = System.currentTimeMillis()
    )
    fun publishConfirmedResponse(trigger: ResponseTriggerEvent)
    fun submitIncidentReport(report: IncidentReportEvent)
    fun disarmSentinel()
    fun setConfirmationThreshold(threshold: Int)
    fun getConfirmationThreshold(): Int
    fun isSentinelDutyActive(): Boolean
}

data class IncidentReportDraft(
    val appState: AppState,
    val safetyStatus: SafetyStatus,
    val injuredCount: Int,
    val companionsCount: Int,
    val roomNumber: String,
    val note: String,
    val latitude: Double?,
    val longitude: Double?,
    val locationLabel: String,
    val relativeLocation: String,
    val threatLatitude: Double?,
    val threatLongitude: Double?,
    val sessionId: String?,
    val connectedPeerCount: Int
)

data class ServerIncidentUpdate(
    val incidentId: String,
    val status: String,
    val recommendedAction: String,
    val policeBrief: String,
    val medicalBrief: String,
    val threatLatitude: Double?,
    val threatLongitude: Double?,
    val threatZones: List<ThreatZone>,
    val authorityMessages: List<ConversationMessage>,
    val liveUpdates: List<String>,
    val confirmedByCount: Int,
    val updatedAt: String
)

interface CloudGateway {
    val isEnabled: Boolean
    val latestIncident: StateFlow<ServerIncidentUpdate?>

    fun startPolling(scope: CoroutineScope)
    suspend fun submitIncidentReport(report: IncidentReportDraft): Boolean
    suspend fun sendAuthorityMessage(incidentId: String, message: String): Boolean

    companion object {
        val Disabled: CloudGateway = object : CloudGateway {
            override val isEnabled: Boolean = false
            override val latestIncident: StateFlow<ServerIncidentUpdate?> = kotlinx.coroutines.flow.MutableStateFlow(null)
            override fun startPolling(scope: CoroutineScope) = Unit
            override suspend fun submitIncidentReport(report: IncidentReportDraft): Boolean = false
            override suspend fun sendAuthorityMessage(incidentId: String, message: String): Boolean = false
        }
    }
}
