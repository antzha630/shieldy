package com.echoshield.echonode.comms

class LeaderDutyCoordinator(
    private val localNodeId: String,
    private val rotationWindowMs: Long = DEFAULT_ROTATION_WINDOW_MS
) {
    companion object {
        private const val DEFAULT_ROTATION_WINDOW_MS = 60_000L
    }

    fun assign(
        peers: Collection<MeshPeer>,
        nowMs: Long = System.currentTimeMillis()
    ): DutyAssignment {
        val members = (peers.map { it.nodeId } + localNodeId)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val safeMembers = members.ifEmpty { listOf(localNodeId) }
        val epoch = if (rotationWindowMs > 0L) nowMs / rotationWindowMs else 0L
        val leaderIndex = (epoch % safeMembers.size).toInt()
        val leaderNodeId = safeMembers[leaderIndex]
        val isLocalLeader = leaderNodeId == localNodeId

        return DutyAssignment(
            epoch = epoch,
            localNodeId = localNodeId,
            leaderNodeId = leaderNodeId,
            memberNodeIds = safeMembers,
            duties = if (isLocalLeader) {
                setOf(MeshDuty.MESH_RELAY, MeshDuty.CLOUD_RELAY)
            } else {
                setOf(MeshDuty.MESH_RELAY)
            }
        )
    }

    data class MeshPeer(
        val endpointId: String,
        val nodeId: String
    )
}

data class DutyAssignment(
    val epoch: Long,
    val localNodeId: String,
    val leaderNodeId: String,
    val memberNodeIds: List<String>,
    val duties: Set<MeshDuty>
) {
    val isLocalLeader: Boolean = localNodeId == leaderNodeId
    val cloudRelayDuty: Boolean = MeshDuty.CLOUD_RELAY in duties
}

enum class MeshDuty {
    MESH_RELAY,
    CLOUD_RELAY
}
