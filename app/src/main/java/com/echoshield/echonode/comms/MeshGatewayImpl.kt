package com.echoshield.echonode.comms

import android.content.Context
import com.echoshield.echonode.core.contracts.IncidentReportEvent
import com.echoshield.echonode.core.contracts.MeshGateway
import com.echoshield.echonode.core.contracts.MeshStatus
import com.echoshield.echonode.core.contracts.ResponseTriggerEvent
import com.echoshield.echonode.core.contracts.WakeClassifyEvent
import com.echoshield.echonode.data.MeshNetworkManager
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

class MeshGatewayImpl(context: Context) : MeshGateway {
    private val meshManager = MeshNetworkManager.getInstance(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val incomingAlerts: SharedFlow<String> = meshManager.incomingPayloads
    override val connectedPeers: StateFlow<Int> = meshManager.connectedPeerCount

    private val _meshStatus = MutableStateFlow(MeshStatus.IDLE)
    override val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

    private val _wakeClassifyRequests = MutableSharedFlow<WakeClassifyEvent>(extraBufferCapacity = 16)
    override val wakeClassifyRequests: SharedFlow<WakeClassifyEvent> = _wakeClassifyRequests.asSharedFlow()

    private val _responseTriggered = MutableSharedFlow<ResponseTriggerEvent>(extraBufferCapacity = 8)
    override val responseTriggered: SharedFlow<ResponseTriggerEvent> = _responseTriggered.asSharedFlow()

    private val _sentinelDutyActive = MutableStateFlow(meshManager.sentinelDutyActive.value)
    override val sentinelDutyActive: StateFlow<Boolean> = _sentinelDutyActive.asStateFlow()

    init {
        scope.launch {
            meshManager.meshStatus.collect { status ->
                _meshStatus.value = when (status) {
                    MeshNetworkManager.MeshStatus.IDLE -> MeshStatus.IDLE
                    MeshNetworkManager.MeshStatus.ADVERTISING -> MeshStatus.ADVERTISING
                    MeshNetworkManager.MeshStatus.DISCOVERING -> MeshStatus.DISCOVERING
                    MeshNetworkManager.MeshStatus.CONNECTED -> MeshStatus.CONNECTED
                    MeshNetworkManager.MeshStatus.ERROR -> MeshStatus.ERROR
                }
            }
        }

        scope.launch {
            meshManager.wakeClassifyRequests.collect { request ->
                _wakeClassifyRequests.emit(
                    WakeClassifyEvent(
                        sessionId = request.sessionId,
                        sourceNodeId = request.sourceNodeId,
                        latitude = request.latitude,
                        longitude = request.longitude,
                        timestamp = request.timestamp
                    )
                )
            }
        }

        scope.launch {
            meshManager.responseTriggered.collect { trigger ->
                _responseTriggered.emit(
                    ResponseTriggerEvent(
                        sessionId = trigger.sessionId,
                        confirmedByNodes = trigger.confirmedByNodes,
                        latitude = trigger.latitude,
                        longitude = trigger.longitude,
                        timestamp = trigger.timestamp
                    )
                )
            }
        }

        scope.launch {
            meshManager.sentinelDutyActive.collect { active ->
                _sentinelDutyActive.value = active
            }
        }
    }

    override fun startMesh() = meshManager.startMesh()

    override fun stopMesh() = meshManager.stopMesh()

    override fun broadcastThreat(zone: String) = meshManager.broadcastAlert(zone)

    override fun broadcastEvacuate(route: String) = meshManager.broadcastEvacuate(route)

    override fun broadcastAllClear() = meshManager.broadcastAllClear()

    override fun broadcastWakeClassify(latitude: Double, longitude: Double) =
        meshManager.broadcastWakeClassify(latitude, longitude)

    override fun submitClassifyVote(sessionId: String, isGunshot: Boolean, confidence: Float) =
        meshManager.submitClassifyVote(sessionId, isGunshot, confidence)

    override fun submitIncidentReport(report: IncidentReportEvent) =
        meshManager.publishIncidentReport(report)

    override fun disarmSentinel() = meshManager.disarmSentinel()

    override fun setConfirmationThreshold(threshold: Int) =
        meshManager.setConfirmationThreshold(threshold)

    override fun getConfirmationThreshold(): Int = meshManager.getConfirmationThreshold()

    override fun isSentinelDutyActive(): Boolean = meshManager.isSentinelDutyActive()
}
