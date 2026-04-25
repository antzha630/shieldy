package com.echoshield.echonode.data

import android.content.Context
import android.util.Log
import com.echoshield.echonode.comms.CloudRelayClient
import com.echoshield.echonode.comms.CloudRelayEnvelope
import com.echoshield.echonode.comms.CloudRelayResult
import com.echoshield.echonode.comms.DutyAssignment
import com.echoshield.echonode.comms.LeaderDutyCoordinator
import com.echoshield.echonode.comms.RetrofitCloudRelayClient
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

        const val PAYLOAD_ALERT_PREFIX = "ALERT:"
        const val PAYLOAD_THREAT_DETECTED = "ALERT:THREAT_DETECTED"
        const val PAYLOAD_ALL_CLEAR = "ALERT:ALL_CLEAR"
        const val PAYLOAD_EVACUATE = "ALERT:EVACUATE"

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

    private val localNodeId: String = UUID.randomUUID().toString().take(8)
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

    private var isAdvertising = false
    private var isDiscovering = false
    private var dutyRotationJob: Job? = null

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
                    Log.e(TAG, "Failed to accept connection with $endpointId", e)
                    _meshStatus.value = MeshStatus.ERROR
                }
            }.onFailure { e ->
                connectingEndpointIds.remove(endpointId)
                Log.e(TAG, "Failed to accept connection with $endpointId", e)
                _meshStatus.value = MeshStatus.ERROR
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
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    pendingEndpointNames.remove(endpointId)
                    Log.w(TAG, "Connection rejected by: $endpointId")
                    updateMeshStatus()
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    pendingEndpointNames.remove(endpointId)
                    Log.e(TAG, "Connection error with: $endpointId")
                    _meshStatus.value = MeshStatus.ERROR
                }

                else -> {
                    pendingEndpointNames.remove(endpointId)
                    Log.w(TAG, "Connection failed with status ${result.status.statusCode}: $endpointId")
                    updateMeshStatus()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from: $endpointId")
            connectedEndpoints.remove(endpointId)
            pendingEndpointNames.remove(endpointId)
            connectingEndpointIds.remove(endpointId)
            updatePeerCount()
            updateDutyAssignment()
            updateMeshStatus()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: ${info.endpointName} ($endpointId)")
            pendingEndpointNames[endpointId] = info.endpointName

            if (connectedEndpoints.containsKey(endpointId) || connectingEndpointIds.contains(endpointId)) {
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
                    Log.e(TAG, "Failed to request connection to $endpointId", e)
                    updateMeshStatus()
                }
            }.onFailure { e ->
                connectingEndpointIds.remove(endpointId)
                Log.e(TAG, "Failed to request connection to $endpointId", e)
                _meshStatus.value = MeshStatus.ERROR
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

    fun startAdvertising() {
        if (isAdvertising) {
            Log.d(TAG, "Already advertising")
            return
        }

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        runCatching {
            connectionsClient.startAdvertising(
                localEndpointName,
                SERVICE_ID,
                connectionLifecycleCallback,
                advertisingOptions
            )
        }.onSuccess { task ->
            task.addOnSuccessListener {
                Log.d(TAG, "Started advertising as: $localEndpointName")
                isAdvertising = true
                updateMeshStatus()
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to start advertising", e)
                isAdvertising = false
                _meshStatus.value = MeshStatus.ERROR
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to start advertising", e)
            isAdvertising = false
            _meshStatus.value = MeshStatus.ERROR
        }
    }

    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Already discovering")
            return
        }

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        runCatching {
            connectionsClient.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                discoveryOptions
            )
        }.onSuccess { task ->
            task.addOnSuccessListener {
                Log.d(TAG, "Started discovery")
                isDiscovering = true
                updateMeshStatus()
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to start discovery", e)
                isDiscovering = false
                _meshStatus.value = MeshStatus.ERROR
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to start discovery", e)
            isDiscovering = false
            _meshStatus.value = MeshStatus.ERROR
        }
    }

    fun startMesh() {
        startDutyRotation()
        startAdvertising()
        startDiscovery()
    }

    fun stopMesh() {
        stopDutyRotation()
        stopAdvertising()
        stopDiscovery()
        disconnectAll()
    }

    fun stopAdvertising() {
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
            Log.w(TAG, "No connected peers to broadcast to")
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
                    if (connectedEndpoints.isEmpty()) {
                        _meshStatus.value = MeshStatus.ERROR
                    }
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

        if (previous.epoch != next.epoch ||
            previous.leaderNodeId != next.leaderNodeId ||
            previous.memberNodeIds != next.memberNodeIds
        ) {
            Log.i(
                TAG,
                "Duty assignment epoch=${next.epoch} leader=${next.leaderNodeId} " +
                    "localLeader=${next.isLocalLeader} members=${next.memberNodeIds.size}"
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
}
