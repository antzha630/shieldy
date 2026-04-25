package com.echoshield.echonode.data

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.SupervisorJob
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

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val localEndpointName: String = "EchoNode-${UUID.randomUUID().toString().take(8)}"
    
    private val connectedEndpoints = ConcurrentHashMap<String, String>()
    private val seenMessageIds = ConcurrentHashMap<String, Long>()
    
    private val _incomingPayloads = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingPayloads: SharedFlow<String> = _incomingPayloads.asSharedFlow()
    
    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount.asStateFlow()
    
    private val _meshStatus = MutableStateFlow(MeshStatus.IDLE)
    val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()
    
    private var isAdvertising = false
    private var isDiscovering = false

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
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "Accepted connection with $endpointId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to accept connection with $endpointId", e)
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected to: $endpointId")
                    connectedEndpoints[endpointId] = endpointId
                    updatePeerCount()
                    _meshStatus.value = MeshStatus.CONNECTED
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by: $endpointId")
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e(TAG, "Connection error with: $endpointId")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from: $endpointId")
            connectedEndpoints.remove(endpointId)
            updatePeerCount()
            if (connectedEndpoints.isEmpty()) {
                _meshStatus.value = if (isAdvertising || isDiscovering) MeshStatus.DISCOVERING else MeshStatus.IDLE
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: ${info.endpointName} ($endpointId)")
            
            if (!connectedEndpoints.containsKey(endpointId)) {
                connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                    .addOnSuccessListener {
                        Log.d(TAG, "Requested connection to $endpointId")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to request connection to $endpointId", e)
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes ->
                    val message = String(bytes, StandardCharsets.UTF_8)
                    Log.d(TAG, "Received payload from $endpointId: $message")
                    
                    scope.launch {
                        _incomingPayloads.emit(message)
                    }

                    relayPayloadIfNeeded(sourceEndpointId = endpointId, message = message)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    Log.d(TAG, "Payload transfer to $endpointId succeeded")
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    Log.e(TAG, "Payload transfer to $endpointId failed")
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    // Transfer in progress
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    Log.w(TAG, "Payload transfer to $endpointId canceled")
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

        connectionsClient.startAdvertising(
            localEndpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Started advertising as: $localEndpointName")
            isAdvertising = true
            _meshStatus.value = MeshStatus.ADVERTISING
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start advertising", e)
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

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Started discovery")
            isDiscovering = true
            _meshStatus.value = MeshStatus.DISCOVERING
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start discovery", e)
            _meshStatus.value = MeshStatus.ERROR
        }
    }

    fun startMesh() {
        startAdvertising()
        startDiscovery()
    }

    fun stopMesh() {
        stopAdvertising()
        stopDiscovery()
        disconnectAll()
    }

    fun stopAdvertising() {
        if (isAdvertising) {
            connectionsClient.stopAdvertising()
            isAdvertising = false
            Log.d(TAG, "Stopped advertising")
        }
    }

    fun stopDiscovery() {
        if (isDiscovering) {
            connectionsClient.stopDiscovery()
            isDiscovering = false
            Log.d(TAG, "Stopped discovery")
        }
    }

    fun disconnectAll() {
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        updatePeerCount()
        _meshStatus.value = MeshStatus.IDLE
        Log.d(TAG, "Disconnected from all endpoints")
    }

    fun broadcastAlert(zone: String) {
        val payload = "$PAYLOAD_THREAT_DETECTED|${createMessageId()}|$zone"
        markMessageSeen(extractMessageId(payload))
        sendPayloadToAllEndpoints(payload)

        scope.launch {
            _incomingPayloads.emit(payload)
        }
    }

    fun broadcastEvacuate(zone: String) {
        val payload = "$PAYLOAD_EVACUATE|${createMessageId()}|$zone"
        markMessageSeen(extractMessageId(payload))
        sendPayloadToAllEndpoints(payload)
        scope.launch {
            _incomingPayloads.emit(payload)
        }
    }

    fun broadcastAllClear() {
        val payload = "$PAYLOAD_ALL_CLEAR|${createMessageId()}"
        markMessageSeen(extractMessageId(payload))
        sendPayloadToAllEndpoints(payload)
        scope.launch {
            _incomingPayloads.emit(payload)
        }
    }

    private fun relayPayloadIfNeeded(sourceEndpointId: String, message: String) {
        if (!message.startsWith(PAYLOAD_ALERT_PREFIX)) return
        val messageId = extractMessageId(message) ?: return
        if (!markMessageSeen(messageId)) return

        // Virus-like propagation: forward new alerts to peers except the source endpoint.
        sendPayloadToAllEndpoints(message, excludeEndpointId = sourceEndpointId)
    }

    private fun sendPayloadToAllEndpoints(message: String, excludeEndpointId: String? = null) {
        if (connectedEndpoints.isEmpty()) {
            Log.w(TAG, "No connected peers to broadcast to")
            return
        }

        val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
        connectedEndpoints.keys
            .filter { endpointId -> endpointId != excludeEndpointId }
            .forEach { endpointId ->
                connectionsClient.sendPayload(endpointId, payload)
                    .addOnSuccessListener {
                        Log.d(TAG, "Payload broadcast to $endpointId: $message")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed payload broadcast to $endpointId", e)
                    }
            }
    }

    private fun createMessageId(): String {
        return "${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}"
    }

    private fun extractMessageId(message: String): String? {
        val parts = message.split("|")
        return parts.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun markMessageSeen(messageId: String?): Boolean {
        if (messageId.isNullOrBlank()) return false
        val previous = seenMessageIds.putIfAbsent(messageId, System.currentTimeMillis())
        if (seenMessageIds.size > 500) {
            val cutoff = System.currentTimeMillis() - 5 * 60 * 1000
            seenMessageIds.entries.removeIf { it.value < cutoff }
        }
        return previous == null
    }

    private fun updatePeerCount() {
        _connectedPeerCount.value = connectedEndpoints.size
    }

    fun getConnectedPeerIds(): List<String> {
        return connectedEndpoints.keys.toList()
    }
}
