package com.echoshield.echonode.experience

import android.util.Log
import com.echoshield.echonode.core.contracts.AppState
import com.echoshield.echonode.core.contracts.CloudGateway
import com.echoshield.echonode.core.contracts.EchoUiState
import com.echoshield.echonode.core.contracts.IncidentReportDraft
import com.echoshield.echonode.core.contracts.MeshGateway
import com.echoshield.echonode.core.contracts.ResponseTriggerEvent
import com.echoshield.echonode.core.contracts.SafetyStatus
import com.echoshield.echonode.core.contracts.ServerIncidentUpdate
import com.echoshield.echonode.core.contracts.SensorGateway
import com.echoshield.echonode.core.contracts.ThreatZone
import com.echoshield.echonode.sensor.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class EchoOrchestrator(
    private val sensorGateway: SensorGateway,
    private val meshGateway: MeshGateway,
    private val cloudGateway: CloudGateway = CloudGateway.Disabled,
    private val locationProvider: LocationProvider? = null
) {
    companion object {
        private const val TAG = "EchoOrchestrator"
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val DISTANCE_VERY_CLOSE_M = 50.0
        private const val DISTANCE_CLOSE_M = 150.0
        private const val DISTANCE_MEDIUM_M = 500.0
        private const val DEFAULT_EVACUATION_ROUTE = "SOUTH - EXIT 4"
    }

    private val _uiState = MutableStateFlow(EchoUiState())
    val uiState: StateFlow<EchoUiState> = _uiState.asStateFlow()
    private var boundScope: CoroutineScope? = null

    private var lastThreatLatitude: Double = 0.0
    private var lastThreatLongitude: Double = 0.0
    private var lastThreatSessionId: String? = null
    private var barricadeSplashShownForSessionId: String? = null

    fun bind(scope: CoroutineScope) {
        boundScope = scope
        cloudGateway.startPolling(scope)

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

        scope.launch {
            cloudGateway.latestIncident.collect { incident ->
                if (incident == null) return@collect
                handleConfirmedCloudIncident(incident)
                _uiState.value = _uiState.value.copy(
                    serverIncidentId = incident.incidentId,
                    serverRecommendedAction = incident.recommendedAction,
                    serverPoliceBrief = incident.policeBrief,
                    serverMedicalBrief = incident.medicalBrief,
                    threatLatitude = incident.threatLatitude ?: _uiState.value.threatLatitude,
                    threatLongitude = incident.threatLongitude ?: _uiState.value.threatLongitude,
                    threatZones = incident.threatZones,
                    conversationMessages = incident.authorityMessages.takeLast(30),
                    liveUpdates = incident.liveUpdates.takeLast(6),
                    // Scale radius by how many peers confirmed for easier map visualization.
                    threatRadiusMeters = (50.0 + incident.confirmedByCount * 20.0).coerceAtMost(250.0)
                )
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

    private fun handleConfirmedCloudIncident(incident: ServerIncidentUpdate) {
        if (incident.status != "CONFIRMED_RESPONSE") return

        val sessionId = incident.incidentId.removePrefix("session-").ifBlank { incident.incidentId }
        val shouldShowBarricadeSplash = shouldShowBarricadeSplashFor(sessionId)
        if (!shouldShowBarricadeSplash && lastThreatSessionId == sessionId) return

        val latitude = incident.threatLatitude ?: _uiState.value.threatLatitude
        val longitude = incident.threatLongitude ?: _uiState.value.threatLongitude
        val confirmedNodes = List(incident.confirmedByCount.coerceAtLeast(1)) { index ->
            "cloud-confirmed-${index + 1}"
        }

        if (latitude != 0.0 || longitude != 0.0) {
            handleResponseTrigger(
                ResponseTriggerEvent(
                    sessionId = sessionId,
                    confirmedByNodes = confirmedNodes,
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = System.currentTimeMillis()
                )
            )
        } else {
            if (shouldShowBarricadeSplash) {
                barricadeSplashShownForSessionId = sessionId
            }
            lastThreatSessionId = sessionId
            _uiState.value = _uiState.value.copy(
                appState = if (shouldShowBarricadeSplash) AppState.BARRICADE else AppState.INCIDENT_REPORT,
                threatZone = "Threat confirmed by ${incident.confirmedByCount.coerceAtLeast(1)} devices",
                evacuationRoute = DEFAULT_EVACUATION_ROUTE,
                relativeLocation = incident.recommendedAction
            )
        }
    }

    private fun handleResponseTrigger(trigger: ResponseTriggerEvent) {
        val shouldShowBarricadeSplash = shouldShowBarricadeSplashFor(trigger.sessionId)
        if (shouldShowBarricadeSplash) {
            barricadeSplashShownForSessionId = trigger.sessionId
        }
        lastThreatSessionId = trigger.sessionId
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
        val escapeDirection = bearingToCardinalDirection(bearing)
        val evacuationRoute = generateEvacuationRoute(distanceMeters, escapeDirection)
        val relativeMessage = generateDistanceBasedMessage(distanceMeters, directionFromThreat, evacuationRoute)

        val threatZone = buildString {
            append("Threat confirmed by ${trigger.confirmedByNodes.size} devices")
            if (distanceMeters > 0) {
                append(" - ${distanceMeters.roundToInt()}m $directionFromThreat")
            }
        }

        _uiState.value = _uiState.value.copy(
            // Show full-screen red barricade only once per threat session.
            appState = if (shouldShowBarricadeSplash) AppState.BARRICADE else AppState.INCIDENT_REPORT,
            threatZone = threatZone,
            evacuationRoute = evacuationRoute,
            relativeLocation = relativeMessage,
            threatLatitude = trigger.latitude,
            threatLongitude = trigger.longitude,
            threatZones = listOf(
                ThreatZone(
                    latitude = trigger.latitude,
                    longitude = trigger.longitude,
                    radiusMeters = (50.0 + trigger.confirmedByNodes.size * 20.0).coerceAtMost(250.0),
                    confidence = 0.9f,
                    source = "mesh-response"
                )
            ),
            threatRadiusMeters = (50.0 + trigger.confirmedByNodes.size * 20.0).coerceAtMost(250.0)
        )
        Log.w(
            TAG,
            "Response trigger session=${trigger.sessionId} confirmed=${trigger.confirmedByNodes.size} " +
                "showBarricade=$shouldShowBarricadeSplash appState=${_uiState.value.appState}"
        )
    }

    private fun shouldShowBarricadeSplashFor(sessionId: String): Boolean {
        val isNewSession = barricadeSplashShownForSessionId != sessionId
        val alreadyInIncidentFlow = _uiState.value.appState != AppState.LISTENING
        // If user is already in incident tabs, don't interrupt with red splash again.
        return isNewSession && !alreadyInIncidentFlow
    }

    private fun generateDistanceBasedMessage(
        distanceMeters: Double,
        direction: String,
        evacuationRoute: String
    ): String {
        return when {
            distanceMeters < DISTANCE_VERY_CLOSE_M -> {
                "IMMEDIATE DANGER - Threat very close! Take cover NOW. If an exit is clear, $evacuationRoute."
            }
            distanceMeters < DISTANCE_CLOSE_M -> {
                "HIGH ALERT - Threat nearby (~${distanceMeters.roundToInt()}m $direction). " +
                    "Barricade immediately. If directed to move, $evacuationRoute."
            }
            distanceMeters < DISTANCE_MEDIUM_M -> {
                "ALERT - Threat detected ~${distanceMeters.roundToInt()}m $direction. " +
                    "Shelter in place unless a clear route opens. Recommended direction: $evacuationRoute."
            }
            else -> {
                "CAUTION - Threat reported ~${distanceMeters.roundToInt()}m $direction. " +
                    "Stay alert. Preferred movement direction: $evacuationRoute."
            }
        }
    }

    private fun generateEvacuationRoute(distanceMeters: Double, escapeDirection: String): String {
        return if (distanceMeters > 0) {
            "Move $escapeDirection away from threat"
        } else {
            "Move away from last reported threat"
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

    private fun bearingToCardinalDirection(bearing: Double): String {
        return when {
            bearing < 45.0 || bearing >= 315.0 -> "NORTH"
            bearing < 135.0 -> "EAST"
            bearing < 225.0 -> "SOUTH"
            else -> "WEST"
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
        // Simulate a two-phone confirmation for testing:
        // - "originator" phone detected gunshot at a random location 80-120m away
        // - "this phone" confirms it as the second device
        val myLat = _uiState.value.locationLatitude
        val myLon = _uiState.value.locationLongitude

        // Generate a random threat location around 30 meters away in a random direction
        val distanceMeters = Random.nextDouble(25.0, 35.0)
        val bearingRadians = Random.nextDouble(0.0, 2 * PI)

        val (threatLat, threatLon) = offsetLatLon(myLat, myLon, distanceMeters, bearingRadians)

        // Create a simulated ResponseTriggerEvent
        val sessionId = "test-${UUID.randomUUID().toString().take(8)}"
        val simulatedTrigger = ResponseTriggerEvent(
            sessionId = sessionId,
            confirmedByNodes = listOf("simulated-originator", "this-device"),
            latitude = threatLat,
            longitude = threatLon,
            timestamp = System.currentTimeMillis()
        )

        // Process this trigger through the normal flow (sets threatLatitude, threatLongitude, etc.)
        handleResponseTrigger(simulatedTrigger)
        Log.w(TAG, "Manual test alert confirmed session=$sessionId")

        // Publish the confirmed response, not just the pre-confirmation alert.
        // This makes the demo button mirror a two-device confirmation on the relay/server console.
        meshGateway.publishConfirmedResponse(simulatedTrigger)
    }

    /**
     * Returns a new (lat, lon) offset from the origin by [distanceMeters] at [bearingRadians].
     * Bearing: 0 = North, PI/2 = East, PI = South, 3*PI/2 = West.
     */
    private fun offsetLatLon(
        originLat: Double,
        originLon: Double,
        distanceMeters: Double,
        bearingRadians: Double
    ): Pair<Double, Double> {
        val latRad = Math.toRadians(originLat)
        val lonRad = Math.toRadians(originLon)
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS

        val newLatRad = kotlin.math.asin(
            sin(latRad) * cos(angularDistance) +
            cos(latRad) * sin(angularDistance) * cos(bearingRadians)
        )

        val newLonRad = lonRad + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(newLatRad)
        )

        return Pair(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad))
    }

    fun triggerEvacuate(
        route: String = DEFAULT_EVACUATION_ROUTE,
        broadcastToPeers: Boolean = false
    ) {
        val selectedRoute = if (
            route == DEFAULT_EVACUATION_ROUTE &&
            _uiState.value.evacuationRoute.isNotBlank()
        ) {
            _uiState.value.evacuationRoute
        } else {
            route
        }

        _uiState.value = _uiState.value.copy(
            appState = AppState.EVACUATE,
            evacuationRoute = selectedRoute
        )
        if (broadcastToPeers) {
            meshGateway.broadcastEvacuate(selectedRoute)
        }
    }

    fun toggleBarricadeEvacuate(broadcastToPeers: Boolean = false) {
        when (_uiState.value.appState) {
            AppState.BARRICADE -> triggerEvacuate(broadcastToPeers = broadcastToPeers)
            AppState.EVACUATE -> transitionToBarricade()
            AppState.LISTENING -> transitionToBarricade()
            AppState.LOCATION_CONFIRMATION -> transitionToBarricade()
            AppState.SAFETY_CHECK -> transitionToBarricade()
            AppState.INCIDENT_REPORT -> transitionToBarricade()
        }
    }

    fun resetAlert(broadcastToPeers: Boolean = false) {
        barricadeSplashShownForSessionId = null
        lastThreatSessionId = null
        _uiState.value = _uiState.value.copy(
            appState = AppState.LISTENING,
            locationConfirmed = false,
            safetyStatus = SafetyStatus.UNKNOWN,
            companionsCount = 0,
            injuredCount = 0,
            roomNumber = "",
            incidentNotes = ""
        )
        if (broadcastToPeers) {
            meshGateway.broadcastAllClear()
        }
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
        val snapshot = _uiState.value
        val hasSnapshotThreat = snapshot.threatLatitude != 0.0 || snapshot.threatLongitude != 0.0
        val hasLastThreat = lastThreatLatitude != 0.0 || lastThreatLongitude != 0.0
        val sessionId = when {
            snapshot.serverIncidentId.startsWith("session-") -> snapshot.serverIncidentId.removePrefix("session-")
            else -> lastThreatSessionId
        }
        val draft = IncidentReportDraft(
            appState = snapshot.appState,
            safetyStatus = snapshot.safetyStatus,
            injuredCount = snapshot.injuredCount,
            companionsCount = snapshot.companionsCount,
            roomNumber = snapshot.roomNumber,
            note = snapshot.incidentNotes,
            latitude = snapshot.locationLatitude.takeIf { it != 0.0 },
            longitude = snapshot.locationLongitude.takeIf { it != 0.0 },
            locationLabel = snapshot.locationLabel,
            relativeLocation = snapshot.relativeLocation,
            threatLatitude = snapshot.threatLatitude.takeIf { hasSnapshotThreat }
                ?: lastThreatLatitude.takeIf { hasLastThreat },
            threatLongitude = snapshot.threatLongitude.takeIf { hasSnapshotThreat }
                ?: lastThreatLongitude.takeIf { hasLastThreat },
            sessionId = sessionId,
            connectedPeerCount = snapshot.connectedPeers
        )
        // Keep submit local/non-blocking for UI. This uploads the status packet to cloud.
        boundScope?.launch(kotlinx.coroutines.Dispatchers.IO) {
            cloudGateway.submitIncidentReport(draft)
        }
    }

    fun sendChatMessage() {
        val text = _uiState.value.incidentNotes.trim()
        if (text.isBlank()) return
        val incidentId = _uiState.value.serverIncidentId
        if (incidentId.isBlank()) return

        _uiState.value = _uiState.value.copy(incidentNotes = "")
        boundScope?.launch(kotlinx.coroutines.Dispatchers.IO) {
            cloudGateway.sendAuthorityMessage(
                incidentId = incidentId,
                message = text
            )
        }
    }

    fun quickBarricade() {
        transitionToBarricade()
    }

    fun quickEvacuate(
        route: String = DEFAULT_EVACUATION_ROUTE,
        broadcastToPeers: Boolean = false
    ) {
        triggerEvacuate(route, broadcastToPeers)
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
                val route = payload.split("|").getOrNull(2) ?: DEFAULT_EVACUATION_ROUTE
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
        val currentState = _uiState.value.appState
        if (currentState != AppState.LISTENING) {
            return
        }

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
