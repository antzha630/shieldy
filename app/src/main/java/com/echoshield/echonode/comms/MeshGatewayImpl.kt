package com.echoshield.echonode.comms

import android.content.Context
import com.echoshield.echonode.core.contracts.MeshGateway
import com.echoshield.echonode.core.contracts.MeshStatus
import com.echoshield.echonode.data.MeshNetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MeshGatewayImpl(context: Context) : MeshGateway {
    private val meshManager = MeshNetworkManager.getInstance(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val incomingAlerts: SharedFlow<String> = meshManager.incomingPayloads
    override val connectedPeers: StateFlow<Int> = meshManager.connectedPeerCount

    private val _meshStatus = MutableStateFlow(MeshStatus.IDLE)
    override val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

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
    }

    override fun startMesh() = meshManager.startMesh()

    override fun stopMesh() = meshManager.stopMesh()

    override fun broadcastThreat(zone: String) = meshManager.broadcastAlert(zone)

    override fun broadcastEvacuate(route: String) = meshManager.broadcastEvacuate(route)

    override fun broadcastAllClear() = meshManager.broadcastAllClear()
}
