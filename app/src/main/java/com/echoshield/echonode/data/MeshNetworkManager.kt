package com.echoshield.echonode.data

import android.content.Context
import android.util.Log
import com.echoshield.echonode.comms.CloudIncidentReport
import com.echoshield.echonode.comms.CloudRelayClient
import com.echoshield.echonode.comms.CloudRelayEnvelope
import com.echoshield.echonode.comms.CloudRelayResult
import com.echoshield.echonode.comms.DutyAssignment
import com.echoshield.echonode.comms.LeaderDutyCoordinator
import com.echoshield.echonode.comms.RetrofitCloudRelayClient
import com.echoshield.echonode.core.Triangulation
import com.echoshield.echonode.core.contracts.ConsensusSnapshot
import com.echoshield.echonode.core.contracts.IncidentReportEvent
import com.echoshield.echonode.core.contracts.SourceEstimateInfo
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.random.Random

class MeshNetworkManager(context: Context) {

    companion object {
        private const val TAG = "MeshNetworkManager"
        private const val SERVICE_ID = "com.echoshield.echonode.mesh"
        private const val MAX_SEEN_MESSAGES = 500
        private const val SEEN_MESSAGE_TTL_MS = 10 * 60 * 1000L
        private const val CONNECTION_REQUEST_THROTTLE_MS = 8_000L
        private const val SEND_RETRY_DELAY_MS = 250L
        private const val SEND_RETRY_ATTEMPTS = 1
        private const val DUTY_REFRESH_MS = 15_000L
        private const val VOTE_WINDOW_MS = 5_000L
        private const val MESH_RETRY_INTERVAL_MS = 5_000L

        // Confirmation / anti-herding tuning.
        // 0 == AUTO: the required number of independent confirmations is derived from
        // the number of connected peers (see requiredConfirmations). A fixed positive
        // value (set via setConfirmationThreshold) overrides AUTO for testing.
        private const val DEFAULT_CONFIRMATION_THRESHOLD = 0
        // Fraction of the fleet (self + peers) that must independently confirm.
        private const val QUORUM_FRACTION = 0.34
        // Upper bound so very large fleets don't need everyone to agree.
        private const val MAX_REQUIRED_CONFIRMATIONS = 5
        // Nominal storey height (metres) used to convert an altitude delta into a
        // relative floor offset for within-building localization.
        private const val FLOOR_HEIGHT_METERS = 3.5
        private const val PREFS_NAME = "echoshield_mesh"
        private const val PREF_LOCAL_NODE_ID = "local_node_id"

        const val PAYLOAD_ALERT_PREFIX = "ALERT:"
        const val PAYLOAD_THREAT_DETECTED = "ALERT:THREAT_DETECTED"
        const val PAYLOAD_ALL_CLEAR = "ALERT:ALL_CLEAR"
        const val PAYLOAD_EVACUATE = "ALERT:EVACUATE"

        const val PAYLOAD_WAKE_CLASSIFY = "WAKE:CLASSIFY"
        const val PAYLOAD_CLASSIFY_VOTE = "VOTE:CLASSIFY"
        const val PAYLOAD_SENTINEL_HANDOFF = "SENTINEL:HANDOFF"
        const val PAYLOAD_SENTINEL_DISARM = "SENTINEL:DISARM"
        const val PAYLOAD_RESPONSE_TRIGGER = "RESPONSE:TRIGGER"

        // Clock synchronisation. Triangulation compares *when* each phone heard the
        // shot, so arrival times from different phones must live on one time base.
        const val PAYLOAD_TIME_PING = "TIME:PING"
        const val PAYLOAD_TIME_PONG = "TIME:PONG"
        private const val CLOCK_SYNC_INTERVAL_MS = 20_000L
        /** Offsets older than this are treated as unknown rather than trusted. */
        private const val CLOCK_SAMPLE_TTL_MS = 120_000L
        /** Round trips longer than this are too noisy to derive an offset from. */
        private const val MAX_CLOCK_RTT_MS = 2_000.0
        /** Uncertainty we claim for our own detections (audio frame quantisation). */
        private const val LOCAL_TIMING_UNCERTAINTY_MS = 5.0

        /** Great-circle distance in metres between two WGS84 coordinates. */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            Triangulation.haversineMeters(lat1, lon1, lat2, lon2)

        @Volatile
        private var instance: MeshNetworkManager? = null

        fun getInstance(context: Context): MeshNetworkManager {
            return instance ?: synchronized(this) {
                instance ?: MeshNetworkManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val localNodeId: String = loadOrCreateLocalNodeId()
    private val localEndpointName: String = "EchoNode-$localNodeId"
    private val dutyCoordinator = LeaderDutyCoordinator(localEndpointName)
    private val cloudRelayClient: CloudRelayClient =
        RetrofitCloudRelayClient.fromManifest(appContext, localEndpointName)

    private val connectedEndpoints = ConcurrentHashMap<String, String>()
    private val pendingEndpointNames = ConcurrentHashMap<String, String>()
    private val connectingEndpointIds: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val connectionAttemptTimestamps = ConcurrentHashMap<String, Long>()
    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    private val latestAlertStateLock = Any()
    private var latestAlertStatePayload: String? = null

    private val _incomingPayloads = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingPayloads: SharedFlow<String> = _incomingPayloads.asSharedFlow()

    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount.asStateFlow()

    private val _meshStatus = MutableStateFlow(MeshStatus.IDLE)
    val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

    private val _dutyAssignment = MutableStateFlow(dutyCoordinator.assign(emptyList()))
    val dutyAssignment: StateFlow<DutyAssignment> = _dutyAssignment.asStateFlow()

    private val _wakeClassifyRequests = MutableSharedFlow<WakeClassifyRequest>(extraBufferCapacity = 16)
    val wakeClassifyRequests: SharedFlow<WakeClassifyRequest> = _wakeClassifyRequests.asSharedFlow()

    private val _responseTriggered = MutableSharedFlow<ResponseTrigger>(extraBufferCapacity = 8)
    val responseTriggered: SharedFlow<ResponseTrigger> = _responseTriggered.asSharedFlow()

    private val _sentinelDutyActive = MutableStateFlow(_dutyAssignment.value.audioSentinelDuty)
    val sentinelDutyActive: StateFlow<Boolean> = _sentinelDutyActive.asStateFlow()

    /** Live vote tally for the current session, so the UI can show why we did or did not escalate. */
    private val _consensusState = MutableStateFlow(ConsensusSnapshot())
    val consensusState: StateFlow<ConsensusSnapshot> = _consensusState.asStateFlow()

    /** nodeId -> most recent usable clock-offset measurement for that peer. */
    private val peerClockOffsets = ConcurrentHashMap<String, ClockSample>()

    private val pendingVoteSessions = ConcurrentHashMap<String, VoteSession>()
    private val orphanVotes = ConcurrentHashMap<String, ConcurrentHashMap<String, ClassifyVote>>()
    private val orphanVoteTimestamps = ConcurrentHashMap<String, Long>()
    @Volatile
    private var confirmationThreshold = DEFAULT_CONFIRMATION_THRESHOLD

    private var isAdvertising = false
    private var isDiscovering = false
    private var advertisingStartInFlight = false
    private var discoveryStartInFlight = false
    @Volatile
    private var isMeshStarted = false
    private var dutyRotationJob: Job? = null
    private var voteCleanupJob: Job? = null
    private var clockSyncJob: Job? = null

    enum class MeshStatus {
        IDLE,
        ADVERTISING,
        DISCOVERING,
        CONNECTED,
        ERROR
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with: ${connectionInfo.endpointName} ($endpointId)")
            pendingEndpointNames[endpointId] = connectionInfo.endpointName
            runCatching {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }.onSuccess { task ->
                task.addOnSuccessListener {
                    Log.d(TAG, "Accepted connection with $endpointId")
                }.addOnFailureListener { e ->
                    connectingEndpointIds.remove(endpointId)
                    Log.w(TAG, "Failed to accept connection with $endpointId (${nearbyErrorDetails(e)})", e)
                    updateMeshStatus()
                }
            }.onFailure { e ->
                connectingEndpointIds.remove(endpointId)
                Log.w(TAG, "Failed to accept connection with $endpointId (${nearbyErrorDetails(e)})", e)
                updateMeshStatus()
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            connectingEndpointIds.remove(endpointId)
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val nodeId = pendingEndpointNames.remove(endpointId) ?: endpointId
                    Log.d(TAG, "Connected to: $nodeId ($endpointId)")
                    connectedEndpoints[endpointId] = nodeId
                    connectionAttemptTimestamps.remove(endpointId)
                    updatePeerCount()
                    updateDutyAssignment()
                    updateMeshStatus()
                    replayLatestAlertState(endpointId)
                    if (connectedEndpoints.size == 1) {
                        stopDiscovery()
                    }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    pendingEndpointNames.remove(endpointId)
                    Log.w(TAG, "Connection rejected by: $endpointId")
                    updateMeshStatus()
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    pendingEndpointNames.remove(endpointId)
                    connectionAttemptTimestamps.remove(endpointId)
                    Log.w(TAG, "Transient connection error with: $endpointId (STATUS_ERROR)")
                    updateMeshStatus()
                }

                else -> {
                    pendingEndpointNames.remove(endpointId)
                    Log.w(TAG, "Connection failed with status ${result.status.statusCode}: $endpointId")
                    updateMeshStatus()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.w(TAG, "Disconnected from: $endpointId - will attempt reconnect")
            connectedEndpoints.remove(endpointId)
            pendingEndpointNames.remove(endpointId)
            connectingEndpointIds.remove(endpointId)
            connectionAttemptTimestamps.remove(endpointId)
            updatePeerCount()
            updateDutyAssignment()
            updateMeshStatus()
            
            // Immediately restart discovery to reconnect
            scope.launch {
                delay(500)
                if (!isMeshStarted) {
                    return@launch
                }
                if (!isDiscovering) {
                    Log.i(TAG, "Restarting discovery after peer disconnect")
                    startDiscovery()
                }
                if (!isAdvertising) {
                    Log.i(TAG, "Restarting advertising after peer disconnect")
                    startAdvertising()
                }
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: ${info.endpointName} ($endpointId)")
            pendingEndpointNames[endpointId] = info.endpointName

            if (connectedEndpoints.containsKey(endpointId) || connectingEndpointIds.contains(endpointId)) {
                return
            }
            if (!shouldInitiateConnection(info.endpointName, endpointId)) {
                Log.d(TAG, "Waiting for ${info.endpointName} to initiate connection")
                return
            }
            if (!shouldRequestConnection(endpointId)) {
                Log.d(TAG, "Skipping throttled connection request to $endpointId")
                return
            }

            connectingEndpointIds.add(endpointId)
            runCatching {
                connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
            }.onSuccess { task ->
                task.addOnSuccessListener {
                    Log.d(TAG, "Requested connection to $endpointId")
                }.addOnFailureListener { e ->
                    connectingEndpointIds.remove(endpointId)
                    Log.w(TAG, "Failed to request connection to $endpointId (${nearbyErrorDetails(e)})", e)
                    updateMeshStatus()
                }
            }.onFailure { e ->
                connectingEndpointIds.remove(endpointId)
                Log.w(TAG, "Failed to request connection to $endpointId (${nearbyErrorDetails(e)})", e)
                updateMeshStatus()
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            if (!connectedEndpoints.containsKey(endpointId)) {
                pendingEndpointNames.remove(endpointId)
                connectingEndpointIds.remove(endpointId)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val message = String(bytes, StandardCharsets.UTF_8)
            Log.d(TAG, "Received payload from $endpointId: $message")
            handleIncomingPayload(endpointId, message)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.d(TAG, "Payload transfer to/from $endpointId succeeded")
                }

                PayloadTransferUpdate.Status.FAILURE -> {
                    Log.e(TAG, "Payload transfer to/from $endpointId failed")
                }

                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Transfer in progress.
                }

                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.w(TAG, "Payload transfer to/from $endpointId canceled")
                }
            }
        }
    }

    private fun loadOrCreateLocalNodeId(): String {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_LOCAL_NODE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val created = UUID.randomUUID().toString().take(8)
        prefs.edit().putString(PREF_LOCAL_NODE_ID, created).apply()
        return created
    }

    fun startAdvertising() {
        if (isAdvertising || advertisingStartInFlight) {
            Log.d(TAG, "Already advertising")
            return
        }

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        advertisingStartInFlight = true
        runCatching {
            connectionsClient.startAdvertising(
                localEndpointName,
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            )
        }.onSuccess { task ->
            task.addOnSuccessListener {
                advertisingStartInFlight = false
                isAdvertising = true
                if (!isMeshStarted) {
                    stopAdvertising()
                    return@addOnSuccessListener
                }
                Log.d(TAG, "Started advertising as: $localEndpointName")
                updateMeshStatus()
            }.addOnFailureListener { e ->
                advertisingStartInFlight = false
                handleAdvertisingStartFailure(e)
            }
        }.onFailure { e ->
            advertisingStartInFlight = false
            handleAdvertisingStartFailure(e)
        }
    }

    fun startDiscovery() {
        if (isDiscovering || discoveryStartInFlight) {
            Log.d(TAG, "Already discovering")
            return
        }

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        discoveryStartInFlight = true
        runCatching {
            connectionsClient.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
            )
        }.onSuccess { task ->
            task.addOnSuccessListener {
                discoveryStartInFlight = false
                isDiscovering = true
                if (!isMeshStarted) {
                    stopDiscovery()
                    return@addOnSuccessListener
                }
                Log.d(TAG, "Started discovery")
                updateMeshStatus()
            }.addOnFailureListener { e ->
                discoveryStartInFlight = false
                handleDiscoveryStartFailure(e)
            }
        }.onFailure { e ->
            discoveryStartInFlight = false
            handleDiscoveryStartFailure(e)
        }
    }

    private fun handleAdvertisingStartFailure(throwable: Throwable) {
        when (nearbyStatusCode(throwable)) {
            ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING -> {
                Log.d(TAG, "Advertising already active (${nearbyErrorDetails(throwable)})")
                isAdvertising = true
                updateMeshStatus()
            }
            else -> {
                Log.e(TAG, "Failed to start advertising (${nearbyErrorDetails(throwable)})", throwable)
                isAdvertising = false
                if (isMeshStarted && connectedEndpoints.isEmpty()) {
                    _meshStatus.value = MeshStatus.ERROR
                } else {
                    updateMeshStatus()
                }
            }
        }
    }

    private fun handleDiscoveryStartFailure(throwable: Throwable) {
        when (nearbyStatusCode(throwable)) {
            ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING -> {
                Log.d(TAG, "Discovery already active (${nearbyErrorDetails(throwable)})")
                isDiscovering = true
                updateMeshStatus()
            }
            else -> {
                Log.e(TAG, "Failed to start discovery (${nearbyErrorDetails(throwable)})", throwable)
                isDiscovering = false
                if (isMeshStarted && connectedEndpoints.isEmpty()) {
                    _meshStatus.value = MeshStatus.ERROR
                } else {
                    updateMeshStatus()
                }
            }
        }
    }

    private var meshRetryJob: Job? = null

    fun startMesh() {
        if (isMeshStarted) {
            Log.d(TAG, "startMesh ignored; mesh already started")
            return
        }
        isMeshStarted = true
        Log.i(TAG, "startMesh: node=$localEndpointName")
        startDutyRotation()
        startVoteCleanup()
        startClockSync()
        startAdvertising()
        startDiscovery()
        startMeshRetryLoop()
    }

    private fun startMeshRetryLoop() {
        if (meshRetryJob?.isActive == true) return
        meshRetryJob = scope.launch {
            while (true) {
                delay(MESH_RETRY_INTERVAL_MS)
                if (!isMeshStarted) {
                    continue
                }
                val status = _meshStatus.value
                if (connectedEndpoints.isNotEmpty()) {
                    updateMeshStatus()
                    continue
                }
                
                // Always try to keep advertising and discovering active
                if (!isAdvertising) {
                    Log.i(TAG, "Mesh retry: restarting advertising (status=$status)")
                    startAdvertising()
                }
                if (!isDiscovering) {
                    Log.i(TAG, "Mesh retry: restarting discovery (status=$status)")
                    startDiscovery()
                }
                
                // Clear error state if we've restarted successfully
                if (status == MeshStatus.ERROR && (isAdvertising || isDiscovering)) {
                    updateMeshStatus()
                }
            }
        }
    }

    fun stopMesh() {
        isMeshStarted = false
        meshRetryJob?.cancel()
        meshRetryJob = null
        stopDutyRotation()
        stopVoteCleanup()
        stopClockSync()
        stopAdvertising()
        stopDiscovery()
        disconnectAll()
        pendingVoteSessions.clear()
        orphanVotes.clear()
        orphanVoteTimestamps.clear()
        peerClockOffsets.clear()
        _consensusState.value = ConsensusSnapshot()
    }

    fun stopAdvertising() {
        advertisingStartInFlight = false
        if (!isAdvertising) return
        runCatching {
            connectionsClient.stopAdvertising()
        }.onFailure { e ->
            Log.e(TAG, "Failed to stop advertising", e)
        }
        isAdvertising = false
        Log.d(TAG, "Stopped advertising")
        updateMeshStatus()
    }

    fun stopDiscovery() {
        discoveryStartInFlight = false
        if (!isDiscovering) return
        runCatching {
            connectionsClient.stopDiscovery()
        }.onFailure { e ->
            Log.e(TAG, "Failed to stop discovery", e)
        }
        isDiscovering = false
        Log.d(TAG, "Stopped discovery")
        updateMeshStatus()
    }

    fun disconnectAll() {
        runCatching {
            connectionsClient.stopAllEndpoints()
        }.onFailure { e ->
            Log.e(TAG, "Failed to stop endpoints", e)
        }
        connectedEndpoints.clear()
        pendingEndpointNames.clear()
        connectingEndpointIds.clear()
        updatePeerCount()
        updateDutyAssignment()
        updateMeshStatus()
        Log.d(TAG, "Disconnected from all endpoints")
    }

    fun broadcastAlert(zone: String) {
        publishLocalAlert(PAYLOAD_THREAT_DETECTED, zone)
    }

    fun broadcastEvacuate(route: String) {
        publishLocalAlert(PAYLOAD_EVACUATE, route)
    }

    fun broadcastAllClear() {
        publishLocalAlert(PAYLOAD_ALL_CLEAR)
    }

    fun getConnectedPeerIds(): List<String> {
        return connectedEndpoints.keys.toList()
    }

    fun publishIncidentReport(report: IncidentReportEvent) {
        if (!cloudRelayClient.isEnabled) {
            Log.d(TAG, "Incident report not sent; cloud relay disabled")
            return
        }

        val assignment = _dutyAssignment.value
        val messageId = "report:${createMessageId()}"
        val cloudReport = CloudIncidentReport(
            protocolVersion = 1,
            deviceId = localEndpointName,
            messageId = messageId,
            observedAtMs = report.submittedAtMs,
            connectedPeerCount = connectedEndpoints.size,
            leaderNodeId = assignment.leaderNodeId,
            dutyEpoch = assignment.epoch,
            appState = report.appState.name,
            safetyStatus = report.safetyStatus.name,
            injuredCount = report.injuredCount,
            companionsCount = report.companionsCount,
            roomNumber = report.roomNumber,
            note = report.notes,
            latitude = report.latitude.takeIf { report.hasUserLocation() },
            longitude = report.longitude.takeIf { report.hasUserLocation() },
            locationLabel = report.locationLabel,
            relativeLocation = report.relativeLocation,
            threatLatitude = report.threatLatitude,
            threatLongitude = report.threatLongitude,
            sessionId = report.threatSessionId
        )

        scope.launch(Dispatchers.IO) {
            when (val result = cloudRelayClient.publishIncidentReport(cloudReport)) {
                CloudRelayResult.Delivered -> {
                    Log.i(TAG, "Cloud incident report delivered $messageId")
                }
                CloudRelayResult.Disabled -> {
                    Log.d(TAG, "Incident report skipped; cloud relay disabled")
                }
                is CloudRelayResult.Failed -> {
                    Log.w(TAG, "Cloud incident report failed $messageId: ${result.reason}", result.throwable)
                }
            }
        }
    }

    private fun IncidentReportEvent.hasUserLocation(): Boolean {
        return latitude != 0.0 || longitude != 0.0
    }

    fun publishConfirmedResponseTrigger(
        sessionId: String,
        confirmedByNodes: List<String>,
        latitude: Double,
        longitude: Double,
        timestamp: Long = System.currentTimeMillis(),
        estimate: SourceEstimateInfo? = null
    ) {
        val trigger = ResponseTrigger(
            sessionId = sessionId,
            confirmedByNodes = confirmedByNodes,
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            estimate = estimate
        )
        val payload = responseTriggerPayload(trigger)

        markMessageSeen("response:$sessionId")
        rememberLatestAlertState(payload)
        sendPayloadToAllEndpoints(payload)
        publishResponseTriggerToCloud(trigger, sourceEndpointId = null, force = true)
        Log.i(TAG, "Published confirmed response trigger session=$sessionId confirmedBy=$confirmedByNodes")
    }

    private fun publishLocalAlert(type: String, body: String? = null) {
        val payload = buildString {
            append(type)
            append("|")
            append(createMessageId())
            if (body != null) {
                append("|")
                append(body)
            }
        }
        val alert = parseAlert(payload) ?: return
        markMessageSeen(alert.dedupeKey)
        rememberLatestAlertState(payload)
        sendPayloadToAllEndpoints(payload)
        emitIncoming(payload)
        publishToCloudIfAssigned(alert, sourceEndpointId = null)
    }

    private fun handleIncomingPayload(sourceEndpointId: String, message: String) {
        when {
            message.startsWith(PAYLOAD_WAKE_CLASSIFY) -> {
                handleWakeClassify(sourceEndpointId, message)
                return
            }
            message.startsWith(PAYLOAD_CLASSIFY_VOTE) -> {
                handleClassifyVote(sourceEndpointId, message)
                return
            }
            message.startsWith(PAYLOAD_SENTINEL_DISARM) -> {
                handleSentinelDisarm(sourceEndpointId, message)
                return
            }
            message.startsWith(PAYLOAD_RESPONSE_TRIGGER) -> {
                handleResponseTrigger(sourceEndpointId, message)
                return
            }
            // Clock sync is strictly point-to-point: never relayed, never deduped,
            // because an offset is only meaningful for the direct link it measured.
            message.startsWith(PAYLOAD_TIME_PING) -> {
                handleTimePing(sourceEndpointId, message)
                return
            }
            message.startsWith(PAYLOAD_TIME_PONG) -> {
                handleTimePong(message)
                return
            }
        }

        val alert = parseAlert(message)
        if (alert == null) {
            emitIncoming(message)
            return
        }

        if (!markMessageSeen(alert.dedupeKey)) {
            Log.d(TAG, "Dropping duplicate mesh alert ${alert.dedupeKey}")
            return
        }

        rememberLatestAlertState(message)
        emitIncoming(message)
        sendPayloadToAllEndpoints(message, excludeEndpointId = sourceEndpointId)
        publishToCloudIfAssigned(alert, sourceEndpointId)
    }

    private fun sendPayloadToAllEndpoints(message: String, excludeEndpointId: String? = null) {
        val targets = connectedEndpoints.keys
            .filter { endpointId -> endpointId != excludeEndpointId }
            .toList()

        if (targets.isEmpty()) {
            if (excludeEndpointId == null) {
                Log.w(TAG, "No connected peers to broadcast to")
            } else {
                Log.d(TAG, "No downstream peers to relay payload")
            }
            return
        }

        targets.forEach { endpointId ->
            sendPayloadToEndpoint(endpointId, message, reason = "broadcast")
        }
    }

    private fun sendPayloadToEndpoint(
        endpointId: String,
        message: String,
        attempt: Int = 0,
        reason: String
    ) {
        if (!connectedEndpoints.containsKey(endpointId)) {
            return
        }

        val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
        runCatching {
            connectionsClient.sendPayload(endpointId, payload)
        }.onSuccess { task ->
            task.addOnSuccessListener {
                Log.d(TAG, "Payload $reason to $endpointId: $message")
            }.addOnFailureListener { e ->
                handleSendFailure(endpointId, message, attempt, reason, e)
            }
        }.onFailure { e ->
            handleSendFailure(endpointId, message, attempt, reason, e)
        }
    }

    private fun handleSendFailure(
        endpointId: String,
        message: String,
        attempt: Int,
        reason: String,
        throwable: Throwable
    ) {
        Log.e(TAG, "Failed payload $reason to $endpointId on attempt ${attempt + 1}", throwable)
        if (attempt < SEND_RETRY_ATTEMPTS && connectedEndpoints.containsKey(endpointId)) {
            scope.launch {
                delay(SEND_RETRY_DELAY_MS)
                sendPayloadToEndpoint(
                    endpointId = endpointId,
                    message = message,
                    attempt = attempt + 1,
                    reason = "$reason-retry"
                )
            }
        }
    }

    private fun replayLatestAlertState(endpointId: String) {
        val payload = synchronized(latestAlertStateLock) { latestAlertStatePayload } ?: return
        Log.d(TAG, "Replaying latest alert state to $endpointId")
        sendPayloadToEndpoint(endpointId, payload, reason = "state-replay")
    }

    private fun rememberLatestAlertState(message: String) {
        synchronized(latestAlertStateLock) {
            latestAlertStatePayload = message
        }
    }

    private fun publishToCloudIfAssigned(alert: MeshAlert, sourceEndpointId: String?) {
        val assignment = _dutyAssignment.value
        if (!cloudRelayClient.isEnabled || !assignment.cloudRelayDuty) {
            return
        }

        val sourceNodeId = sourceEndpointId?.let { endpointId ->
            connectedEndpoints[endpointId] ?: pendingEndpointNames[endpointId] ?: endpointId
        } ?: localEndpointName

        val envelope = CloudRelayEnvelope(
            protocolVersion = 1,
            deviceId = localEndpointName,
            messageId = alert.messageId ?: alert.dedupeKey,
            alertType = alert.type,
            body = alert.body,
            payload = alert.raw,
            sourceNodeId = sourceNodeId,
            observedAtMs = System.currentTimeMillis(),
            connectedPeerCount = connectedEndpoints.size,
            leaderNodeId = assignment.leaderNodeId,
            dutyEpoch = assignment.epoch
        )

        scope.launch(Dispatchers.IO) {
            when (val result = cloudRelayClient.publishAlert(envelope)) {
                CloudRelayResult.Delivered -> {
                    Log.d(TAG, "Cloud relay delivered ${envelope.messageId}")
                }

                CloudRelayResult.Disabled -> {
                    // Disabled clients are a normal local-only operating mode.
                }

                is CloudRelayResult.Failed -> {
                    Log.w(TAG, "Cloud relay failed for ${envelope.messageId}: ${result.reason}", result.throwable)
                }
            }
        }
    }

    private fun startDutyRotation() {
        if (dutyRotationJob?.isActive == true) {
            return
        }
        dutyRotationJob = scope.launch {
            while (true) {
                updateDutyAssignment()
                delay(DUTY_REFRESH_MS)
            }
        }
    }

    private fun stopDutyRotation() {
        dutyRotationJob?.cancel()
        dutyRotationJob = null
        updateDutyAssignment()
    }

    private fun updateDutyAssignment() {
        val peers = connectedEndpoints.map { (endpointId, nodeId) ->
            LeaderDutyCoordinator.MeshPeer(endpointId = endpointId, nodeId = nodeId)
        }
        val previous = _dutyAssignment.value
        val next = dutyCoordinator.assign(peers)
        _dutyAssignment.value = next
        _sentinelDutyActive.value = next.audioSentinelDuty

        if (previous.epoch != next.epoch ||
            previous.leaderNodeId != next.leaderNodeId ||
            previous.memberNodeIds != next.memberNodeIds ||
            previous.audioSentinelDuty != next.audioSentinelDuty
        ) {
            Log.i(
                TAG,
                "Duty assignment epoch=${next.epoch} leader=${next.leaderNodeId} " +
                    "localLeader=${next.isLocalLeader} audioSentinel=${next.audioSentinelDuty} " +
                    "members=${next.memberNodeIds.size}"
            )
        }
    }

    private fun updatePeerCount() {
        _connectedPeerCount.value = connectedEndpoints.size
    }

    private fun updateMeshStatus() {
        _meshStatus.value = when {
            connectedEndpoints.isNotEmpty() -> MeshStatus.CONNECTED
            isDiscovering -> MeshStatus.DISCOVERING
            isAdvertising -> MeshStatus.ADVERTISING
            else -> MeshStatus.IDLE
        }
    }

    private fun shouldRequestConnection(endpointId: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = connectionAttemptTimestamps[endpointId]
        if (previous != null && now - previous < CONNECTION_REQUEST_THROTTLE_MS) {
            return false
        }
        connectionAttemptTimestamps[endpointId] = now
        return true
    }

    private fun shouldInitiateConnection(remoteEndpointName: String, endpointId: String): Boolean {
        val remoteStableName = remoteEndpointName.ifBlank { endpointId }
        if (remoteStableName == localEndpointName) {
            return false
        }

        // Both devices advertise and discover. A stable ordering prevents both
        // sides from sending simultaneous connection requests to each other.
        return localEndpointName < remoteStableName
    }

    private fun emitIncoming(message: String) {
        if (!_incomingPayloads.tryEmit(message)) {
            scope.launch {
                _incomingPayloads.emit(message)
            }
        }
    }

    private fun createMessageId(): String {
        return "${System.currentTimeMillis()}-$localNodeId-${Random.nextInt(1000, 9999)}"
    }

    private fun parseAlert(message: String): MeshAlert? {
        if (!message.startsWith(PAYLOAD_ALERT_PREFIX)) {
            return null
        }
        val parts = message.split("|", limit = 3)
        val type = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val messageId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val body = parts.getOrNull(2)
        val dedupeKey = messageId ?: "legacy:${message.hashCode()}"
        return MeshAlert(
            raw = message,
            type = type,
            messageId = messageId,
            body = body,
            dedupeKey = dedupeKey
        )
    }

    private fun markMessageSeen(dedupeKey: String): Boolean {
        val now = System.currentTimeMillis()
        pruneSeenMessages(now)
        return seenMessageIds.putIfAbsent(dedupeKey, now) == null
    }

    private fun pruneSeenMessages(now: Long) {
        if (seenMessageIds.size < MAX_SEEN_MESSAGES) {
            return
        }

        val cutoff = now - SEEN_MESSAGE_TTL_MS
        seenMessageIds.entries.removeIf { it.value < cutoff }

        val overflow = seenMessageIds.size - MAX_SEEN_MESSAGES
        if (overflow <= 0) {
            return
        }

        seenMessageIds.entries
            .sortedBy { it.value }
            .take(overflow)
            .forEach { entry ->
                seenMessageIds.remove(entry.key, entry.value)
            }
    }

    private data class MeshAlert(
        val raw: String,
        val type: String,
        val messageId: String?,
        val body: String?,
        val dedupeKey: String
    )

    data class WakeClassifyRequest(
        val sessionId: String,
        val sourceNodeId: String,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long
    )

    data class VoteSession(
        val sessionId: String,
        val startedAtMs: Long,
        val sourceNodeId: String,
        val latitude: Double,
        val longitude: Double,
        val votes: ConcurrentHashMap<String, ClassifyVote> = ConcurrentHashMap()
    ) {
        fun confirmedCount(): Int = votes.values.count { it.isGunshot }
        fun totalVotes(): Int = votes.size

        /** Gunshot confirmations from nodes other than the one that raised the alarm. */
        fun independentConfirmations(): Int =
            votes.values.count { it.isGunshot && it.nodeId != sourceNodeId }

        /**
         * Locate the source from every node that independently confirmed the shot.
         *
         * Delegates to [Triangulation], which multilaterates from arrival times when
         * enough phones report a synchronised clock and falls back to a
         * confidence-weighted centroid otherwise. Returns null when no confirming
         * node reported usable coordinates.
         */
        fun estimateSource(): SourceEstimateInfo? {
            val observations = votes.values
                .filter { it.isGunshot }
                .map { vote ->
                    Triangulation.Observation(
                        nodeId = vote.nodeId,
                        latitude = vote.latitude,
                        longitude = vote.longitude,
                        altitude = vote.altitude,
                        confidence = vote.confidence,
                        detectedAtMs = vote.detectedAtMs,
                        timingUncertaintyMs = vote.timingUncertaintyMs
                    )
                }

            val fix = Triangulation.solve(observations) ?: return null
            return SourceEstimateInfo(
                latitude = fix.latitude,
                longitude = fix.longitude,
                altitude = fix.altitude,
                floorOffset = fix.floorOffset,
                method = fix.method,
                contributingNodes = fix.contributingNodes,
                spreadMeters = fix.spreadMeters,
                timingResidualMs = fix.timingResidualMs
            )
        }
    }

    data class ClassifyVote(
        val nodeId: String,
        val isGunshot: Boolean,
        val confidence: Float,
        val timestamp: Long,
        val latitude: Double = Double.NaN,
        val longitude: Double = Double.NaN,
        val altitude: Double = Double.NaN,
        /**
         * When this node's microphone heard the impulse, converted to *our* clock.
         * [Triangulation.NO_TIMING] when the peer's clock is not synchronised, in
         * which case the vote still counts toward consensus but not toward the
         * TDoA solve.
         */
        val detectedAtMs: Long = Triangulation.NO_TIMING,
        /** How well [detectedAtMs] is known, in ms (half the measured round trip). */
        val timingUncertaintyMs: Double = Double.NaN
    )

    data class ResponseTrigger(
        val sessionId: String,
        val confirmedByNodes: List<String>,
        /** The raising node's own position; used only when triangulation found nothing. */
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        /** Triangulated source, or null when no confirming node reported a fix. */
        val estimate: SourceEstimateInfo? = null
    ) {
        val estimatedLatitude: Double get() = estimate?.latitude ?: latitude
        val estimatedLongitude: Double get() = estimate?.longitude ?: longitude
    }

    fun setConfirmationThreshold(threshold: Int) {
        // A positive value pins the threshold (manual/testing). Zero or negative
        // restores AUTO mode, where the quorum scales with the connected fleet.
        confirmationThreshold = threshold.coerceAtLeast(0)
        Log.i(TAG, "Confirmation threshold set to ${if (confirmationThreshold == 0) "AUTO" else confirmationThreshold.toString()}")
    }

    fun getConfirmationThreshold(): Int = effectiveConfirmationThreshold()

    /**
     * How many independent gunshot confirmations are needed to trigger a response.
     *
     * A single false positive on one phone must never cascade into a fleet-wide alert
     * (the "herding" failure). So once any peers are present we require corroboration
     * from at least two distinct nodes, scaling up with fleet size but capped so a
     * large building doesn't need unanimity. A solo device (no peers) falls back to a
     * local-only trigger of 1 since it has nothing to corroborate against.
     */
    private fun requiredConfirmations(): Int {
        val peers = connectedEndpoints.size
        if (peers == 0) return 1
        val scaled = Math.ceil((peers + 1) * QUORUM_FRACTION).toInt()
        return scaled.coerceIn(2, MAX_REQUIRED_CONFIRMATIONS)
    }

    private fun effectiveConfirmationThreshold(): Int {
        val override = confirmationThreshold
        return if (override >= 1) override else requiredConfirmations()
    }

    /**
     * Raise a candidate detection: ask every peer to classify its *own* microphone
     * audio for this moment and vote.
     *
     * @param confidence this node's own classifier score. It is recorded as-is rather
     *   than as a perfect 1.0, so the raising node cannot dominate the weighted fix —
     *   the node most likely to be wrong is the one that fired first.
     * @param detectedAtMs when our microphone heard the impulse (local clock), which
     *   peers convert onto their own clocks for the TDoA solve.
     */
    fun broadcastWakeClassify(
        latitude: Double,
        longitude: Double,
        confidence: Float = 1.0f,
        altitude: Double = Double.NaN,
        detectedAtMs: Long = System.currentTimeMillis()
    ) {
        val sessionId = createMessageId()
        val payload = buildString {
            append(PAYLOAD_WAKE_CLASSIFY)
            append("|")
            append(sessionId)
            append("|")
            append(localEndpointName)
            append("|")
            append(latitude)
            append("|")
            append(longitude)
            append("|")
            append(System.currentTimeMillis())
            append("|")
            append(detectedAtMs)
            append("|")
            append(altitude)
            append("|")
            append(confidence)
        }

        val session = VoteSession(
            sessionId = sessionId,
            startedAtMs = System.currentTimeMillis(),
            sourceNodeId = localEndpointName,
            latitude = latitude,
            longitude = longitude
        )
        session.votes[localEndpointName] = ClassifyVote(
            nodeId = localEndpointName,
            isGunshot = true,
            confidence = confidence,
            timestamp = System.currentTimeMillis(),
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            detectedAtMs = detectedAtMs,
            timingUncertaintyMs = LOCAL_TIMING_UNCERTAINTY_MS
        )
        pendingVoteSessions[sessionId] = session
        publishConsensus(session, active = true)

        markMessageSeen("wake:$sessionId")
        sendPayloadToAllEndpoints(payload)
        Log.i(TAG, "Broadcast WAKE_CLASSIFY sessionId=$sessionId lat=$latitude lon=$longitude conf=$confidence")

        checkVoteThreshold(sessionId)
    }

    fun submitClassifyVote(
        sessionId: String,
        isGunshot: Boolean,
        confidence: Float,
        latitude: Double = Double.NaN,
        longitude: Double = Double.NaN,
        altitude: Double = Double.NaN,
        detectedAtMs: Long = Triangulation.NO_TIMING
    ) {
        val payload = buildString {
            append(PAYLOAD_CLASSIFY_VOTE)
            append("|")
            append(sessionId)
            append("|")
            append(localEndpointName)
            append("|")
            append(if (isGunshot) "1" else "0")
            append("|")
            append(confidence)
            append("|")
            append(System.currentTimeMillis())
            append("|")
            append(latitude)
            append("|")
            append(longitude)
            append("|")
            append(altitude)
            append("|")
            append(detectedAtMs)
        }

        markMessageSeen("vote:$sessionId:$localEndpointName")
        sendPayloadToAllEndpoints(payload)
        Log.d(TAG, "Submitted vote for session=$sessionId gunshot=$isGunshot conf=$confidence loc=($latitude,$longitude)")

        pendingVoteSessions[sessionId]?.let { session ->
            session.votes[localEndpointName] = ClassifyVote(
                nodeId = localEndpointName,
                isGunshot = isGunshot,
                confidence = confidence,
                timestamp = System.currentTimeMillis(),
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                // Our own detection needs no clock conversion.
                detectedAtMs = detectedAtMs,
                timingUncertaintyMs = if (detectedAtMs == Triangulation.NO_TIMING) {
                    Double.NaN
                } else {
                    LOCAL_TIMING_UNCERTAINTY_MS
                }
            )
            publishConsensus(session, active = true)
            checkVoteThreshold(sessionId)
        }
    }

    fun disarmSentinel() {
        dutyCoordinator.disarmSentinel()
        updateDutyAssignment()

        val messageId = createMessageId()
        val payload = buildString {
            append(PAYLOAD_SENTINEL_DISARM)
            append("|")
            append(messageId)
            append("|")
            append(localEndpointName)
        }
        markMessageSeen("disarm:$messageId")
        sendPayloadToAllEndpoints(payload)
        Log.i(TAG, "Sentinel disarmed and handoff broadcast")
    }

    fun isSentinelDutyActive(): Boolean = _dutyAssignment.value.audioSentinelDuty

    private fun handleWakeClassify(sourceEndpointId: String, message: String) {
        val parts = message.split("|")
        if (parts.size < 6) return

        val sessionId = parts[1]
        val sourceNodeId = parts[2]
        val latitude = parts[3].toDoubleOrNull() ?: return
        val longitude = parts[4].toDoubleOrNull() ?: return
        val timestamp = parts[5].toLongOrNull() ?: System.currentTimeMillis()

        if (!markMessageSeen("wake:$sessionId")) {
            Log.d(TAG, "Dropping duplicate WAKE_CLASSIFY $sessionId")
            return
        }

        val session = pendingVoteSessions.computeIfAbsent(sessionId) {
            VoteSession(
                sessionId = sessionId,
                startedAtMs = System.currentTimeMillis(),
                sourceNodeId = sourceNodeId,
                latitude = latitude,
                longitude = longitude
            )
        }

        // The raising node's detection is itself a vote, and carries the position and
        // arrival time its own microphone measured — without it the raiser would be
        // missing from the triangulation it started.
        val raiserDetectedAtMs = parts.getOrNull(6)?.toLongOrNull() ?: Triangulation.NO_TIMING
        val raiserAltitude = parts.getOrNull(7)?.toDoubleOrNull() ?: Double.NaN
        val raiserConfidence = parts.getOrNull(8)?.toFloatOrNull() ?: 1.0f
        val raiserTiming = toLocalClock(sourceNodeId, raiserDetectedAtMs)
        session.votes[sourceNodeId] = ClassifyVote(
            nodeId = sourceNodeId,
            isGunshot = true,
            confidence = raiserConfidence,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            altitude = raiserAltitude,
            detectedAtMs = raiserTiming?.first ?: Triangulation.NO_TIMING,
            timingUncertaintyMs = raiserTiming?.second ?: Double.NaN
        )
        applyOrphanVotes(sessionId, session)
        publishConsensus(session, active = true)

        sendPayloadToAllEndpoints(message, excludeEndpointId = sourceEndpointId)

        val request = WakeClassifyRequest(
            sessionId = sessionId,
            sourceNodeId = sourceNodeId,
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp
        )
        scope.launch {
            _wakeClassifyRequests.emit(request)
        }

        Log.i(TAG, "Received WAKE_CLASSIFY session=$sessionId from $sourceNodeId")
    }

    private fun handleClassifyVote(sourceEndpointId: String, message: String) {
        val parts = message.split("|")
        if (parts.size < 6) return

        val sessionId = parts[1]
        val voterNodeId = parts[2]
        val isGunshot = parts[3] == "1"
        val confidence = parts[4].toFloatOrNull() ?: 0f
        val timestamp = parts[5].toLongOrNull() ?: System.currentTimeMillis()
        // Coordinates are optional (older peers omit them).
        val voterLat = parts.getOrNull(6)?.toDoubleOrNull() ?: Double.NaN
        val voterLon = parts.getOrNull(7)?.toDoubleOrNull() ?: Double.NaN
        val voterAlt = parts.getOrNull(8)?.toDoubleOrNull() ?: Double.NaN
        // The peer stamped this on *its* clock; only usable once mapped onto ours.
        val voterDetectedAtMs = parts.getOrNull(9)?.toLongOrNull() ?: Triangulation.NO_TIMING
        val voterTiming = toLocalClock(voterNodeId, voterDetectedAtMs)

        val dedupeKey = "vote:$sessionId:$voterNodeId"
        if (!markMessageSeen(dedupeKey)) {
            Log.d(TAG, "Dropping duplicate vote from $voterNodeId for $sessionId")
            return
        }

        sendPayloadToAllEndpoints(message, excludeEndpointId = sourceEndpointId)

        val vote = ClassifyVote(
            nodeId = voterNodeId,
            isGunshot = isGunshot,
            confidence = confidence,
            timestamp = timestamp,
            latitude = voterLat,
            longitude = voterLon,
            altitude = voterAlt,
            detectedAtMs = voterTiming?.first ?: Triangulation.NO_TIMING,
            timingUncertaintyMs = voterTiming?.second ?: Double.NaN
        )

        val session = pendingVoteSessions[sessionId]
        if (session == null) {
            storeOrphanVote(sessionId, vote)
            return
        }

        session.votes[voterNodeId] = vote
        publishConsensus(session, active = true)

        Log.d(
            TAG,
            "Vote recorded: session=$sessionId voter=$voterNodeId gunshot=$isGunshot " +
                "(${session.confirmedCount()}/${effectiveConfirmationThreshold()} needed)"
        )

        checkVoteThreshold(sessionId)
    }

    private fun handleSentinelDisarm(sourceEndpointId: String, message: String) {
        val parts = message.split("|")
        if (parts.size < 3) return

        val messageId = parts[1]
        val sourceNode = parts[2]

        if (!markMessageSeen("disarm:$messageId")) return

        sendPayloadToAllEndpoints(message, excludeEndpointId = sourceEndpointId)
        dutyCoordinator.forceSentinelHandoff()
        updateDutyAssignment()
        Log.i(TAG, "Sentinel disarm received from $sourceNode, forcing handoff")
    }

    private fun storeOrphanVote(sessionId: String, vote: ClassifyVote) {
        orphanVotes.computeIfAbsent(sessionId) { ConcurrentHashMap() }[vote.nodeId] = vote
        orphanVoteTimestamps[orphanVoteKey(sessionId, vote.nodeId)] = System.currentTimeMillis()
        Log.d(TAG, "Buffered vote for unknown session=$sessionId voter=${vote.nodeId}")
    }

    private fun applyOrphanVotes(sessionId: String, session: VoteSession) {
        val votes = orphanVotes.remove(sessionId) ?: return
        votes.values.forEach { vote ->
            session.votes[vote.nodeId] = vote
            orphanVoteTimestamps.remove(orphanVoteKey(sessionId, vote.nodeId))
        }
        Log.d(TAG, "Applied ${votes.size} buffered vote(s) for session=$sessionId")
        checkVoteThreshold(sessionId)
    }

    private fun clearOrphanVotes(sessionId: String) {
        orphanVotes.remove(sessionId)?.keys?.forEach { nodeId ->
            orphanVoteTimestamps.remove(orphanVoteKey(sessionId, nodeId))
        }
    }

    private fun orphanVoteKey(sessionId: String, nodeId: String): String = "$sessionId:$nodeId"

    private fun checkVoteThreshold(sessionId: String) {
        val session = pendingVoteSessions[sessionId] ?: return

        val confirmedCount = session.confirmedCount()
        val threshold = effectiveConfirmationThreshold()
        val hasPeers = connectedEndpoints.isNotEmpty()
        // Anti-herding: once other nodes exist, the raising node's own vote is never
        // sufficient — at least one independent node must also confirm on its own audio.
        val independentOk = !hasPeers || session.independentConfirmations() >= 1
        Log.d(
            TAG,
            "Vote check: session=$sessionId confirmed=$confirmedCount " +
                "independent=${session.independentConfirmations()} threshold=$threshold peers=${connectedEndpoints.size}"
        )

        if (confirmedCount >= threshold && independentOk) {
            val confirmedNodes = session.votes.entries
                .filter { it.value.isGunshot }
                .map { it.key }

            val trigger = ResponseTrigger(
                sessionId = sessionId,
                confirmedByNodes = confirmedNodes,
                latitude = session.latitude,
                longitude = session.longitude,
                timestamp = System.currentTimeMillis(),
                estimate = session.estimateSource()
            )

            scope.launch {
                _responseTriggered.emit(trigger)
            }

            markMessageSeen("response:$sessionId")
            broadcastResponseTrigger(trigger)
            publishResponseTriggerToCloud(trigger, sourceEndpointId = null)
            publishConsensus(session, active = false)
            pendingVoteSessions.remove(sessionId)
            clearOrphanVotes(sessionId)
            val estimate = trigger.estimate
            Log.w(
                TAG,
                "RESPONSE TRIGGERED! session=$sessionId confirmedBy=$confirmedNodes " +
                    "est=(${trigger.estimatedLatitude},${trigger.estimatedLongitude}) " +
                    "method=${estimate?.method ?: "none"} " +
                    "spread=${"%.1f".format(estimate?.spreadMeters ?: 0.0)}m " +
                    "floor=${estimate?.floorOffset ?: 0} " +
                    "residual=${"%.1f".format(estimate?.timingResidualMs ?: Double.NaN)}ms"
            )
        }
    }

    private fun responseTriggerPayload(trigger: ResponseTrigger): String {
        return buildString {
            append(PAYLOAD_RESPONSE_TRIGGER)
            append("|")
            append(trigger.sessionId)
            append("|")
            append(trigger.latitude)
            append("|")
            append(trigger.longitude)
            append("|")
            append(trigger.confirmedByNodes.joinToString(","))
            append("|")
            append(trigger.timestamp)
            append("|")
            append(trigger.estimatedLatitude)
            append("|")
            append(trigger.estimatedLongitude)
            append("|")
            append(trigger.estimate?.altitude ?: Double.NaN)
            append("|")
            append(trigger.estimate?.floorOffset ?: 0)
            append("|")
            append(trigger.estimate?.method ?: Triangulation.METHOD_SINGLE)
            append("|")
            append(trigger.estimate?.spreadMeters ?: 0.0)
            append("|")
            append(trigger.estimate?.timingResidualMs ?: Double.NaN)
            append("|")
            append(trigger.estimate?.contributingNodes ?: 0)
        }
    }

    private fun broadcastResponseTrigger(trigger: ResponseTrigger) {
        val payload = responseTriggerPayload(trigger)
        rememberLatestAlertState(payload)
        sendPayloadToAllEndpoints(payload)
    }

    private fun handleResponseTrigger(sourceEndpointId: String?, message: String) {
        val parts = message.split("|")
        if (parts.size < 6) return

        val sessionId = parts[1]
        val latitude = parts[2].toDoubleOrNull() ?: return
        val longitude = parts[3].toDoubleOrNull() ?: return
        val confirmedNodes = parts[4].split(",").filter { it.isNotBlank() }
        val timestamp = parts[5].toLongOrNull() ?: System.currentTimeMillis()
        // Triangulated estimate fields are optional (older peers omit them).
        val estLat = parts.getOrNull(6)?.toDoubleOrNull()
        val estLon = parts.getOrNull(7)?.toDoubleOrNull()
        val estAlt = parts.getOrNull(8)?.toDoubleOrNull() ?: Double.NaN
        val floorOffset = parts.getOrNull(9)?.toIntOrNull() ?: 0
        val method = parts.getOrNull(10)?.takeIf { it.isNotBlank() } ?: Triangulation.METHOD_SINGLE
        val spreadMeters = parts.getOrNull(11)?.toDoubleOrNull() ?: 0.0
        val timingResidualMs = parts.getOrNull(12)?.toDoubleOrNull() ?: Double.NaN
        val contributingNodes = parts.getOrNull(13)?.toIntOrNull() ?: confirmedNodes.size

        val dedupeKey = "response:$sessionId"
        if (!markMessageSeen(dedupeKey)) return

        sendPayloadToAllEndpoints(message, excludeEndpointId = sourceEndpointId)

        val trigger = ResponseTrigger(
            sessionId = sessionId,
            confirmedByNodes = confirmedNodes,
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            estimate = if (estLat != null && estLon != null) {
                SourceEstimateInfo(
                    latitude = estLat,
                    longitude = estLon,
                    altitude = estAlt,
                    floorOffset = floorOffset,
                    method = method,
                    contributingNodes = contributingNodes,
                    spreadMeters = spreadMeters,
                    timingResidualMs = timingResidualMs
                )
            } else {
                null
            }
        )

        scope.launch {
            _responseTriggered.emit(trigger)
        }

        publishResponseTriggerToCloud(trigger, sourceEndpointId)
        publishConsensus(pendingVoteSessions[sessionId], active = false)
        pendingVoteSessions.remove(sessionId)
        clearOrphanVotes(sessionId)
        Log.w(TAG, "RESPONSE_TRIGGER received: session=$sessionId at ($latitude, $longitude)")
    }

    private fun publishResponseTriggerToCloud(
        trigger: ResponseTrigger,
        sourceEndpointId: String?,
        force: Boolean = false
    ) {
        val assignment = _dutyAssignment.value
        if (!cloudRelayClient.isEnabled || (!force && !assignment.cloudRelayDuty)) {
            return
        }

        val sourceNodeId = sourceEndpointId?.let { endpointId ->
            connectedEndpoints[endpointId] ?: pendingEndpointNames[endpointId] ?: endpointId
        } ?: localEndpointName

        val payload = responseTriggerPayload(trigger)

        val envelope = CloudRelayEnvelope(
            protocolVersion = 1,
            deviceId = localEndpointName,
            messageId = "response:${trigger.sessionId}",
            alertType = PAYLOAD_RESPONSE_TRIGGER,
            body = null,
            payload = payload,
            sourceNodeId = sourceNodeId,
            observedAtMs = System.currentTimeMillis(),
            connectedPeerCount = connectedEndpoints.size,
            leaderNodeId = assignment.leaderNodeId,
            dutyEpoch = assignment.epoch,
            sessionId = trigger.sessionId,
            latitude = trigger.estimatedLatitude,
            longitude = trigger.estimatedLongitude,
            confirmedByNodes = trigger.confirmedByNodes
        )

        scope.launch(Dispatchers.IO) {
            when (val result = cloudRelayClient.publishAlert(envelope)) {
                CloudRelayResult.Delivered -> {
                    Log.d(TAG, "Cloud response relay delivered ${trigger.sessionId}")
                }

                CloudRelayResult.Disabled -> {
                    // Disabled clients are a normal local-only operating mode.
                }

                is CloudRelayResult.Failed -> {
                    Log.w(TAG, "Cloud response relay failed for ${trigger.sessionId}: ${result.reason}", result.throwable)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clock synchronisation (NTP-style, over the mesh link)
    //
    // Triangulation compares arrival times across phones, and sound covers 0.343 m
    // per millisecond — so without a shared time base the arrival differences are
    // meaningless. Nearby gives us no common clock, so each node measures the offset
    // to each peer directly: ping at t1, peer stamps receive t2 and reply t3, we
    // stamp t4. Round-trip delay cancels out of the average, leaving
    //   offset = ((t2 - t1) + (t3 - t4)) / 2      (peer clock minus our clock)
    // with an uncertainty of about half the round trip. That is a few milliseconds
    // on a good link and tens of milliseconds on a poor one; the solver is told the
    // uncertainty and refuses to triangulate when it is too large to be worth it.
    // ─────────────────────────────────────────────────────────────────────────

    private data class ClockSample(
        /** Peer clock minus local clock, in milliseconds. */
        val offsetMs: Double,
        val rttMs: Double,
        val measuredAtMs: Long
    ) {
        /** Half the round trip: how far off the offset could plausibly be. */
        val uncertaintyMs: Double get() = rttMs / 2.0
    }

    private fun startClockSync() {
        if (clockSyncJob?.isActive == true) return
        clockSyncJob = scope.launch {
            while (true) {
                if (connectedEndpoints.isNotEmpty()) {
                    val pingId = createMessageId()
                    sendPayloadToAllEndpoints("$PAYLOAD_TIME_PING|$pingId|$localEndpointName|${System.currentTimeMillis()}")
                }
                pruneClockSamples()
                delay(CLOCK_SYNC_INTERVAL_MS)
            }
        }
    }

    private fun stopClockSync() {
        clockSyncJob?.cancel()
        clockSyncJob = null
    }

    private fun handleTimePing(sourceEndpointId: String, message: String) {
        val receivedAtMs = System.currentTimeMillis()
        val parts = message.split("|")
        if (parts.size < 4) return
        val pingId = parts[1]
        val requesterNodeId = parts[2]
        val sentAtMs = parts[3].toLongOrNull() ?: return

        sendPayloadToEndpoint(
            endpointId = sourceEndpointId,
            message = "$PAYLOAD_TIME_PONG|$pingId|$localEndpointName|$requesterNodeId|" +
                "$sentAtMs|$receivedAtMs|${System.currentTimeMillis()}",
            reason = "clock-pong"
        )
    }

    private fun handleTimePong(message: String) {
        val receivedAtMs = System.currentTimeMillis()
        val parts = message.split("|")
        if (parts.size < 7) return
        val responderNodeId = parts[2]
        val requesterNodeId = parts[3]
        if (requesterNodeId != localEndpointName) return // not our round trip

        val t1 = parts[4].toLongOrNull() ?: return
        val t2 = parts[5].toLongOrNull() ?: return
        val t3 = parts[6].toLongOrNull() ?: return
        val t4 = receivedAtMs

        val rttMs = ((t4 - t1) - (t3 - t2)).toDouble()
        if (rttMs < 0 || rttMs > MAX_CLOCK_RTT_MS) {
            Log.d(TAG, "Discarding clock sample from $responderNodeId: rtt=${rttMs}ms")
            return
        }
        val offsetMs = ((t2 - t1).toDouble() + (t3 - t4).toDouble()) / 2.0
        val sample = ClockSample(offsetMs, rttMs, receivedAtMs)

        // Keep the tightest recent measurement: a lower round trip means a lower
        // bound on how wrong the offset can be.
        val existing = peerClockOffsets[responderNodeId]
        val existingStale = existing == null || receivedAtMs - existing.measuredAtMs > CLOCK_SAMPLE_TTL_MS
        if (existingStale || rttMs < existing!!.rttMs) {
            peerClockOffsets[responderNodeId] = sample
            Log.d(TAG, "Clock sync $responderNodeId offset=${"%.1f".format(offsetMs)}ms rtt=${"%.1f".format(rttMs)}ms")
        }
    }

    private fun pruneClockSamples() {
        val cutoff = System.currentTimeMillis() - CLOCK_SAMPLE_TTL_MS
        peerClockOffsets.entries.removeIf { it.value.measuredAtMs < cutoff }
    }

    /**
     * Convert a peer's detection timestamp into our own clock, plus how well that
     * conversion is known. Returns null when we have no fresh offset for that peer —
     * an unsynchronised timestamp must never be fed to the solver as if it were good.
     */
    private fun toLocalClock(nodeId: String, remoteMs: Long): Pair<Long, Double>? {
        if (remoteMs == Triangulation.NO_TIMING) return null
        val sample = peerClockOffsets[nodeId] ?: return null
        if (System.currentTimeMillis() - sample.measuredAtMs > CLOCK_SAMPLE_TTL_MS) return null
        return (remoteMs - sample.offsetMs).roundToLong() to sample.uncertaintyMs
    }

    private fun publishConsensus(session: VoteSession?, active: Boolean) {
        _consensusState.value = if (session == null) {
            ConsensusSnapshot()
        } else {
            ConsensusSnapshot(
                sessionId = session.sessionId,
                active = active,
                confirmations = session.confirmedCount(),
                independentConfirmations = session.independentConfirmations(),
                totalVotes = session.totalVotes(),
                required = effectiveConfirmationThreshold(),
                startedAtMs = session.startedAtMs
            )
        }
    }

    private fun startVoteCleanup() {
        if (voteCleanupJob?.isActive == true) return
        voteCleanupJob = scope.launch {
            while (true) {
                delay(VOTE_WINDOW_MS)
                val now = System.currentTimeMillis()
                val expiredSessions = pendingVoteSessions.entries
                    .filter { now - it.value.startedAtMs > VOTE_WINDOW_MS * 2 }
                    .map { it.key }

                expiredSessions.forEach { sessionId ->
                    pendingVoteSessions.remove(sessionId)
                    clearOrphanVotes(sessionId)
                    if (_consensusState.value.sessionId == sessionId) {
                        // The vote failed to reach quorum: stand down rather than
                        // leaving a stale "pending" tally on screen.
                        _consensusState.value = _consensusState.value.copy(active = false)
                    }
                    Log.d(TAG, "Expired vote session: $sessionId")
                }

                val orphanCutoff = now - VOTE_WINDOW_MS * 2
                orphanVoteTimestamps.entries
                    .filter { it.value < orphanCutoff }
                    .forEach { entry ->
                        orphanVoteTimestamps.remove(entry.key, entry.value)
                        val sessionId = entry.key.substringBefore(":")
                        val voterNodeId = entry.key.substringAfter(":")
                        val votes = orphanVotes[sessionId] ?: return@forEach
                        votes.remove(voterNodeId)
                        if (votes.isEmpty()) {
                            orphanVotes.remove(sessionId, votes)
                        }
                    }
            }
        }
    }

    private fun stopVoteCleanup() {
        voteCleanupJob?.cancel()
        voteCleanupJob = null
    }

    private fun nearbyErrorDetails(throwable: Throwable): String {
        val code = nearbyStatusCode(throwable) ?: return throwable.javaClass.simpleName
        return "code=$code(${ConnectionsStatusCodes.getStatusCodeString(code)})"
    }

    private fun nearbyStatusCode(throwable: Throwable): Int? {
        return (throwable as? ApiException)?.statusCode
    }
}
