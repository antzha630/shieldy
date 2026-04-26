package com.echoshield.echonode.experience

import com.echoshield.echonode.core.contracts.AppState
import com.echoshield.echonode.core.contracts.EchoUiState
import com.echoshield.echonode.core.contracts.MeshGateway
import com.echoshield.echonode.core.contracts.ResponseTriggerEvent
import com.echoshield.echonode.core.contracts.SafetyStatus
import com.echoshield.echonode.core.contracts.SensorGateway
import com.echoshield.echonode.sensor.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class EchoOrchestrator(
    private val sensorGateway: SensorGateway,
    private val meshGateway: MeshGateway,
    private val locationProvider: LocationProvider? = null
) {
    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val DISTANCE_VERY_CLOSE_M = 50.0
        private const val DISTANCE_CLOSE_M = 150.0
        private const val DISTANCE_MEDIUM_M = 500.0
    }

    private val _uiState = MutableStateFlow(EchoUiState())
    val uiState: StateFlow<EchoUiState> = _uiState.asStateFlow()

    private var lastThreatLatitude: Double = 0.0
    private var lastThreatLongitude: Double = 0.0

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

        scope.launch {
            meshGateway.responseTriggered.collect { trigger ->
                handleResponseTrigger(trigger)
            }
        }

        scope.launch {
            meshGateway.sentinelDutyActive.collect { _ ->
                // Could update UI to show sentinel status if desired
            }
        }

        locationProvider?.let { provider ->
            provider.startLocationUpdates()
            scope.launch {
                provider.currentLocation.collect { location ->
                    _uiState.value = _uiState.value.copy(
                        locationLabel = location.label,
                        relativeLocation = location.relativeLocation,
                        coordinateText = location.coordinateText,
                        locationTimestamp = location.timestamp,
                        locationLatitude = location.latitude,
                        locationLongitude = location.longitude
                    )
                }
            }
        }
    }

    private fun handleResponseTrigger(trigger: ResponseTriggerEvent) {
        lastThreatLatitude = trigger.latitude
        lastThreatLongitude = trigger.longitude

        val myLat = _uiState.value.locationLatitude
        val myLon = _uiState.value.locationLongitude

        val distanceMeters = calculateDistanceMeters(
            lat1 = trigger.latitude,
            lon1 = trigger.longitude,
            lat2 = myLat,
            lon2 = myLon
        )

        val bearing = calculateBearing(
            lat1 = trigger.latitude,
            lon1 = trigger.longitude,
            lat2 = myLat,
            lon2 = myLon
        )

        val directionFromThreat = bearingToDirection(bearing)
        val relativeMessage = generateDistanceBasedMessage(distanceMeters, directionFromThreat)

        val threatZone = buildString {
            append("Threat confirmed by ${trigger.confirmedByNodes.size} devices")
            if (distanceMeters > 0) {
                append(" - ${distanceMeters.roundToInt()}m $directionFromThreat")
            }
        }

        _uiState.value = _uiState.value.copy(
            appState = AppState.BARRICADE,
            threatZone = threatZone,
            relativeLocation = relativeMessage
        )
    }

    private fun generateDistanceBasedMessage(distanceMeters: Double, direction: String): String {
        return when {
            distanceMeters < DISTANCE_VERY_CLOSE_M -> {
                "IMMEDIATE DANGER - Threat very close! Take cover NOW!"
            }
            distanceMeters < DISTANCE_CLOSE_M -> {
                "HIGH ALERT - Threat nearby (~${distanceMeters.roundToInt()}m $direction). " +
                    "Barricade immediately, stay away from windows."
            }
            distanceMeters < DISTANCE_MEDIUM_M -> {
                "ALERT - Threat detected ~${distanceMeters.roundToInt()}m $direction. " +
                    "Shelter in place, lock doors, silence phones."
            }
            else -> {
                "CAUTION - Threat reported ~${distanceMeters.roundToInt()}m $direction. " +
                    "Stay alert, prepare to shelter if situation escalates."
            }
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return 0.0
        if (lat2 == 0.0 && lon2 == 0.0) return 0.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val x = sin(dLon) * cos(lat2Rad)
        val y = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)

        val bearing = Math.toDegrees(atan2(x, y))
        return (bearing + 360) % 360
    }

    private fun bearingToDirection(bearing: Double): String {
        return when {
            bearing < 22.5 || bearing >= 337.5 -> "to your NORTH"
            bearing < 67.5 -> "to your NORTHEAST"
            bearing < 112.5 -> "to your EAST"
            bearing < 157.5 -> "to your SOUTHEAST"
            bearing < 202.5 -> "to your SOUTH"
            bearing < 247.5 -> "to your SOUTHWEST"
            bearing < 292.5 -> "to your WEST"
            else -> "to your NORTHWEST"
        }
    }

    fun setConfirmationThreshold(threshold: Int) {
        meshGateway.setConfirmationThreshold(threshold)
    }

    fun getConfirmationThreshold(): Int = meshGateway.getConfirmationThreshold()

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
            roomNumber = "",
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

    fun setRoomNumber(room: String) {
        _uiState.value = _uiState.value.copy(roomNumber = room)
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

    fun goBackToLocation() {
        _uiState.value = _uiState.value.copy(appState = AppState.LOCATION_CONFIRMATION)
    }

    fun goBackToSafetyCheck() {
        _uiState.value = _uiState.value.copy(appState = AppState.SAFETY_CHECK)
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
            roomNumber = "",
            incidentNotes = ""
        )
    }
}
