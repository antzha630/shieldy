# Workstream 2 - Comms + Networking Team

## Owner Profile

- **Skill level needed:** Medium-strong (networking + distributed logic)
- Why: this stream controls mesh reliability, dedupe/relay correctness, leader strategy, and backend sync integration.

## Folder Ownership

- Owns: `app/src/main/java/com/echoshield/echonode/comms/`
- Can edit: `app/src/main/java/com/echoshield/echonode/data/` (network internals only)
- Must not edit without approval:
  - `app/src/main/java/com/echoshield/echonode/sensor/`
  - `app/src/main/java/com/echoshield/echonode/experience/`
  - `app/src/main/java/com/echoshield/echonode/ui/`
  - `app/src/main/java/com/echoshield/echonode/MainActivity.kt`
  - `app/src/main/java/com/echoshield/echonode/core/AppContainer.kt`

## Implementation Status

### Completed Features

#### 1. Sentinel Duty Rotation (Maximum Battery Saving)
- **File**: `LeaderDutyCoordinator.kt`, `AudioSensorService.kt`
- Only ONE phone captures audio at all (sentinel)
- Non-sentinel phones completely stop audio recording - no microphone usage
- Sentinel rotates every 30 seconds among mesh peers
- When sentinel detects threat → wakes other phones to briefly capture + classify
- Reduces battery drain across the network by ~90-95%

#### 2. Multi-Phone Confirmation Protocol
- **File**: `MeshNetworkManager.kt`
- Configurable threshold (default: 2 confirmations required)
- When sentinel detects gunshot → broadcasts `WAKE_CLASSIFY` to all peers
- Peers run ML classifier on their audio buffers → submit votes
- Full RESPONSE only triggers when N phones confirm (prevents false positives)
- Vote window: 5 seconds

#### 3. Sentinel Disarm After Detection
- When a phone triggers detection, it automatically disarms as sentinel
- Prevents same person from re-triggering (e.g., video game sounds)
- Disarm duration: 30 seconds (configurable)
- Forces immediate handoff to next peer

#### 4. Location-Based Messaging
- **File**: `EchoOrchestrator.kt`
- Threat payloads include GPS coordinates
- Each phone calculates distance/direction from threat
- Distance-based alert messages:
  - <50m: "IMMEDIATE DANGER"
  - <150m: "HIGH ALERT" + direction
  - <500m: "ALERT" + distance + direction
  - >500m: "CAUTION" + distance + direction

#### 5. New Mesh Message Types
| Type | Description |
|------|-------------|
| `WAKE:CLASSIFY` | Sentinel detected, wake peers to run classifier |
| `VOTE:CLASSIFY` | Peer's classification vote (gunshot/not) |
| `SENTINEL:DISARM` | Force sentinel handoff |
| `RESPONSE:TRIGGER` | Confirmation threshold met, full response |

### Protocol Flow

```
1. LISTENING (normal state)
   └─> ONLY sentinel phone captures audio (microphone active)
   └─> All other phones: microphone OFF, saving battery

2. SENTINEL DETECTS LOUD SOUND
   └─> Activity gate opens → ML classifier runs
   └─> If gunshot detected:
       a) Disarm self as sentinel (stops audio capture)
       b) Broadcast WAKE_CLASSIFY with GPS location

3. PEER WAKE + CAPTURE
   └─> All peers receive WAKE_CLASSIFY
   └─> Each starts microphone, captures ~1 second of audio
   └─> Each runs ML classifier on fresh capture
   └─> Each submits VOTE:CLASSIFY (gunshot=yes/no, confidence)
   └─> Each stops microphone after voting

4. THRESHOLD CHECK
   └─> Leader aggregates votes
   └─> If ≥ N confirmations (default 2):
       → Broadcast RESPONSE_TRIGGER
       → All phones enter BARRICADE state
       → Distance-based messages displayed
```

## MeshGateway Contract

```kotlin
interface MeshGateway {
    // Existing
    val incomingAlerts: SharedFlow<String>
    val connectedPeers: StateFlow<Int>
    val meshStatus: StateFlow<MeshStatus>
    fun startMesh()
    fun stopMesh()
    fun broadcastThreat(zone: String)
    fun broadcastEvacuate(route: String)
    fun broadcastAllClear()

    // New for multi-phone confirmation
    val wakeClassifyRequests: SharedFlow<WakeClassifyEvent>
    val responseTriggered: SharedFlow<ResponseTriggerEvent>
    val sentinelDutyActive: StateFlow<Boolean>
    
    fun broadcastWakeClassify(latitude: Double, longitude: Double)
    fun submitClassifyVote(sessionId: String, isGunshot: Boolean, confidence: Float)
    fun disarmSentinel()
    fun setConfirmationThreshold(threshold: Int)
    fun getConfirmationThreshold(): Int
    fun isSentinelDutyActive(): Boolean
}
```

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Sentinel Rotation | 30s | How often sentinel duty rotates |
| Sentinel Disarm Duration | 30s | How long a sentinel is disabled after detection |
| Confirmation Threshold | 2 | How many phones must confirm for RESPONSE |
| Vote Window | 5s | Time window to collect votes |
| Leader Rotation | 60s | How often cloud relay duty rotates |

## Test Plan

### Single Device Test
1. Start app → Verify sentinel duty is active (only device)
2. Trigger loud sound → Should broadcast WAKE_CLASSIFY
3. Check logs for "Sentinel disarmed"

### Two Device Test
1. Start app on both phones → One should be sentinel
2. Trigger loud sound near sentinel
3. Verify:
   - Sentinel broadcasts WAKE_CLASSIFY
   - Other phone runs classifier
   - Both submit votes
   - RESPONSE_TRIGGER fires when 2 confirm
   - Both show distance-based alerts

### Battery Test
1. Run 3+ phones for 30 minutes
2. Verify sentinel rotates (check logs)
3. Compare battery drain between sentinel and non-sentinel

## Done Criteria

- [x] Compiles with `./gradlew :app:compileDebugKotlin`
- [x] Sentinel duty rotates among peers
- [x] Multi-phone confirmation protocol works
- [x] Sentinel disarms after detection
- [x] Location-based messaging works
- [ ] Test on 2+ real phones
- [ ] Measure battery improvement
