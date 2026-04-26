package com.echoshield.echonode.comms

class LeaderDutyCoordinator(
    private val localNodeId: String,
    private val rotationWindowMs: Long = DEFAULT_ROTATION_WINDOW_MS,
    private val sentinelRotationWindowMs: Long = DEFAULT_SENTINEL_ROTATION_WINDOW_MS
) {
    companion object {
        private const val DEFAULT_ROTATION_WINDOW_MS = 60_000L
        private const val DEFAULT_SENTINEL_ROTATION_WINDOW_MS = 30_000L
        const val DEFAULT_SENTINEL_DISARM_MS = 30_000L
        
        // HACKATHON MODE: All phones always act as sentinels (no rotation)
        // Set to false to re-enable battery-saving sentinel rotation
        const val ALL_PHONES_ARE_SENTINELS = true
    }

    @Volatile
    private var sentinelDisarmedUntilMs: Long = 0L

    @Volatile
    private var sentinelEpochOffset: Int = 0

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

        val sentinelEpoch = if (sentinelRotationWindowMs > 0L) {
            nowMs / sentinelRotationWindowMs
        } else 0L

        val effectiveSentinelIndex = ((sentinelEpoch + sentinelEpochOffset) % safeMembers.size).toInt()
        val sentinelNodeId = safeMembers[effectiveSentinelIndex]

        val isSentinelDisarmed = nowMs < sentinelDisarmedUntilMs
        // In hackathon mode, all phones are always sentinels
        val isLocalSentinel = if (ALL_PHONES_ARE_SENTINELS) {
            true
        } else {
            sentinelNodeId == localNodeId && !isSentinelDisarmed
        }

        val duties = buildSet {
            add(MeshDuty.MESH_RELAY)
            if (isLocalLeader) {
                add(MeshDuty.CLOUD_RELAY)
            }
            if (isLocalSentinel) {
                add(MeshDuty.AUDIO_SENTINEL)
            }
        }

        return DutyAssignment(
            epoch = epoch,
            sentinelEpoch = sentinelEpoch,
            localNodeId = localNodeId,
            leaderNodeId = leaderNodeId,
            sentinelNodeId = if (isSentinelDisarmed) null else sentinelNodeId,
            memberNodeIds = safeMembers,
            duties = duties,
            sentinelDisarmed = isSentinelDisarmed
        )
    }

    fun disarmSentinel(durationMs: Long = DEFAULT_SENTINEL_DISARM_MS) {
        sentinelDisarmedUntilMs = System.currentTimeMillis() + durationMs
        sentinelEpochOffset++
    }

    fun forceSentinelHandoff() {
        sentinelEpochOffset++
    }

    fun isSentinelDisarmed(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs < sentinelDisarmedUntilMs
    }

    data class MeshPeer(
        val endpointId: String,
        val nodeId: String
    )
}

data class DutyAssignment(
    val epoch: Long,
    val sentinelEpoch: Long = 0L,
    val localNodeId: String,
    val leaderNodeId: String,
    val sentinelNodeId: String? = null,
    val memberNodeIds: List<String>,
    val duties: Set<MeshDuty>,
    val sentinelDisarmed: Boolean = false
) {
    val isLocalLeader: Boolean = localNodeId == leaderNodeId
    val isLocalSentinel: Boolean = MeshDuty.AUDIO_SENTINEL in duties
    val cloudRelayDuty: Boolean = MeshDuty.CLOUD_RELAY in duties
    val audioSentinelDuty: Boolean = MeshDuty.AUDIO_SENTINEL in duties
}

enum class MeshDuty {
    MESH_RELAY,
    CLOUD_RELAY,
    AUDIO_SENTINEL
}
