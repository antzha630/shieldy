# EchoShield (EchoNode) - Android MVP

EchoNode is an Android hackathon MVP for school threat response. It runs a foreground audio sensor, performs on-device gunshot-like audio inference, and propagates alerts across nearby Android devices using Google Nearby Connections.

## TL;DR

- **Platform:** Android-only MVP
- **Detection:** On-device YAMNet (`yamnet.tflite`) + activity gate
- **Mesh:** Nearby Connections `P2P_CLUSTER`
- **State UI:** `LISTENING`, `BARRICADE`, `EVACUATE`
- **Goal:** sub-second local trigger + rapid peer propagation
- **Team split:** see `WORKSTREAMS.md` for 3-way ownership map

## AI Handoff (For Other Agents)

If you are an AI agent picking up this repository:

- Start with `MainActivity.kt`, `MainViewModel.kt`, `AudioSensorService.kt`, `MeshNetworkManager.kt`.
- Detection logic runs in `AudioSensorService` and publishes via `SystemEventFlow`.
- Mesh propagation and dedupe IDs are in `MeshNetworkManager`.
- UI consumes `MainViewModel.UiState` in `Screens.kt`.
- Model assets are in `app/src/main/assets/`:
  - `yamnet.tflite`
  - `yamnet_class_map.csv`

### Current Detection Pipeline

1. `AudioRecord` captures PCM 16-bit mono at 44.1kHz.
2. Audio is downsampled to 16k and stored in a rolling 15600-sample buffer.
3. YAMNet inference runs periodically (gated by amplitude/activity).
4. If gunshot confidence crosses threshold, service emits `LOCAL_TRIGGER`.
5. ViewModel transitions to `BARRICADE` and broadcasts to mesh peers.

### Current Mesh Payload Format

State alerts:

- Threat: `ALERT:THREAT_DETECTED|<messageId>|<zone>`
- Evacuate: `ALERT:EVACUATE|<messageId>|<route>`
- Clear: `ALERT:ALL_CLEAR|<messageId>`

Consensus and localization (fields are append-only, so older peers still parse):

- Raise: `WAKE:CLASSIFY|<sessionId>|<nodeId>|<lat>|<lon>|<sentAtMs>|<detectedAtMs>|<altitude>|<confidence>`
- Vote: `VOTE:CLASSIFY|<sessionId>|<nodeId>|<0|1>|<confidence>|<sentAtMs>|<lat>|<lon>|<alt>|<detectedAtMs>`
- Trigger: `RESPONSE:TRIGGER|<sessionId>|<lat>|<lon>|<nodes>|<ts>|<estLat>|<estLon>|<estAlt>|<floorOffset>|<method>|<spreadM>|<residualMs>|<contributingNodes>`
- Sentinel: `SENTINEL:DISARM|<messageId>|<nodeId>`

Clock sync (point-to-point; never relayed or deduped, since an offset is only
meaningful for the link that measured it):

- `TIME:PING|<pingId>|<nodeId>|<t1>`
- `TIME:PONG|<pingId>|<responderId>|<requesterId>|<t1>|<t2>|<t3>`

`detectedAtMs` is when that node's **microphone heard the impulse**, not when it sent
the message; it is converted onto the receiver's clock using the measured offset. This
is what makes arrival-time triangulation possible — see
`docs/GUNSHOT_ACOUSTICS_INDOORS.md`.

### Known Constraints

- Nearby on emulator is unreliable. Use **real Android devices** for mesh demos.
- Detection is pretrained YAMNet class score gating, not yet custom fine-tuned school-specific classifier.
- Triangulation accuracy is bounded by phone clock sync, not by the solver: ~7 m of
  ranging error per 20 ms of clock offset. Treat a fix as room-or-corridor level, not
  the sub-metre figure a fixed wired sensor array achieves.
- iOS joins through the relay bus (`/v1/mesh/*`) rather than Nearby, which is
  Android-only. The web client at `/app` is a real voting sensor node, but its
  detector is an impulsive-transient detector, not a trained classifier, and mobile
  Safari suspends audio when the tab is backgrounded. See
  `docs/OPIUS_CLIENT_AND_IOS.md`.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        EchoNode App                         │
├─────────────────────────────────────────────────────────────┤
│  MainActivity                                                │
│  ├── Runtime permissions                                     │
│  └── Compose root + screen routing                           │
├─────────────────────────────────────────────────────────────┤
│  MainViewModel                                               │
│  ├── UiState + app transitions                               │
│  ├── Observes SystemEventFlow                                │
│  └── Observes MeshNetworkManager                             │
├─────────────────────────────────────────────────────────────┤
│  AudioSensorService (Foreground)                             │
│  ├── AudioRecord capture                                     │
│  ├── YAMNet inference via GunshotClassifier                  │
│  └── Emits LOCAL_TRIGGER                                     │
├─────────────────────────────────────────────────────────────┤
│  MeshNetworkManager                                          │
│  ├── Nearby P2P_CLUSTER advertise/discover                   │
│  ├── Broadcast + receive payloads                            │
│  └── Relay with message-id dedupe                            │
└─────────────────────────────────────────────────────────────┘
```

## Features

### Detection
- Foreground microphone service for continuous monitoring
- Activity-gated ML inference for battery-aware operation
- Impulse-aware gating: peak level, peak-to-RMS crest factor, and **microphone
  clipping** (a close shot rails a phone mic; severe clipping also relaxes the ML
  threshold, since the distortion it causes would otherwise lose a real detection)
- 4-second retrospective audio buffer so a phone can classify the moment a *peer*
  reports hearing something, not whatever it hears when the message arrives
- Real-time telemetry in UI:
  - Audio amplitude and activity gate
  - ML top label and gunshot confidence score
  - Microphone clipping percentage

### Consensus and localization
- **Anti-herding quorum**: confirmations required scale with fleet size, and the
  raising node alone is never sufficient once peers exist
- **Triangulation**: TDoA multilateration from arrival times over an NTP-style mesh
  clock sync, falling back to a confidence-weighted centroid when the timing cannot
  support an honest fix
- **Within-building metrics**: relative floor offset from the altitude spread across
  confirming phones
- **First aid**: bystander guidance (bleeding first) in the app and the web client

### Alerting
1. **LISTENING**: dark screen + active mesh indicator
2. **BARRICADE**: high-contrast red emergency instruction view
3. **EVACUATE**: high-contrast green route guidance view

### Peer Mesh
- Nearby Connections with automatic peer discovery
- Alert rebroadcast to simulate viral propagation
- Message ID cache to prevent infinite relay loops

## Build and Run

```bash
cd shieldy
./gradlew assembleDebug
```

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

Install from terminal (optional):

```bash
./gradlew installDebug
```

## Required Permissions

| Permission | Purpose |
|------------|---------|
| `RECORD_AUDIO` | local audio detection |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Nearby discovery requirement |
| `BLUETOOTH_*` | P2P mesh links |
| `FOREGROUND_SERVICE_MICROPHONE` | persistent background monitoring |
| `POST_NOTIFICATIONS` | foreground service notification |

## Test Plan (Recommended)

### Baseline Mesh Test
1. Install `app-debug.apk` on 2 real Android devices.
2. Grant all permissions.
3. Keep both apps open for 10-20 seconds.
4. Tap `SIMULATE THREAT` on one device.
5. Verify both devices switch to `BARRICADE`.

### ML Trigger Test
1. On dashboard, open `OPTIONS`.
2. Tune activity threshold slider for environment.
3. Produce sharp impulse near microphone.
4. Watch ML line (`top label` + confidence) and verify trigger behavior.

## Project Structure

```
app/src/main/java/com/echoshield/echonode/
├── MainActivity.kt                # permissions + Compose entry
├── EchoShieldApp.kt               # Application
├── data/
│   ├── MeshNetworkManager.kt      # Nearby mesh + relay dedupe
│   └── SystemEventFlow.kt         # Shared event/state flows
├── service/
│   ├── AudioSensorService.kt      # Foreground audio + detection loop
│   └── GunshotClassifier.kt       # YAMNet TFLite wrapper
├── core/
│   ├── Triangulation.kt           # TDoA multilateration + centroid fallback
│   ├── FirstAid.kt                # Bystander first-aid guidance
│   └── contracts/EchoContracts.kt # Gateway interfaces + UI state
├── viewmodel/
│   └── MainViewModel.kt           # App state transitions
└── ui/
    ├── Screens.kt                 # LISTENING/BARRICADE/EVACUATE screens
    └── theme/Theme.kt             # Material 3 styling
```

Assets:

```
app/src/main/assets/
├── yamnet.tflite
└── yamnet_class_map.csv
```

## Roadmap

- Replace YAMNet direct class gating with fine-tuned gunshot head trained on domain data.
- Add backend consensus and push relay (for reliable Android+iOS mixed fleets).
- Add zone mapping and Silent Escort routing by room/hallway.
- Add incident logs and admin console.

## License

Hackathon project. Demo use only.
