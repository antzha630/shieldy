# EchoShield (EchoNode) - Android MVP

A distributed threat detection and response system for school safety. This MVP demonstrates a peer-to-peer mesh network using Google Nearby Connections for coordinated emergency response.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        EchoNode App                         │
├─────────────────────────────────────────────────────────────┤
│  MainActivity                                                │
│  ├── Permission Handling (Audio, Location, Bluetooth)       │
│  └── Compose UI Navigation                                   │
├─────────────────────────────────────────────────────────────┤
│  MainViewModel                                               │
│  ├── AppState (LISTENING | BARRICADE | EVACUATE)            │
│  ├── SystemEventFlow Observer                                │
│  └── MeshNetworkManager Observer                             │
├─────────────────────────────────────────────────────────────┤
│  AudioSensorService (Foreground)                             │
│  ├── AudioRecord (44.1kHz, Mono, 16-bit)                    │
│  ├── RMS Amplitude Calculation                               │
│  └── Threshold Detection → SystemEventFlow                   │
├─────────────────────────────────────────────────────────────┤
│  MeshNetworkManager                                          │
│  ├── Nearby Connections (P2P_CLUSTER Strategy)              │
│  ├── Auto-discovery & Connection                             │
│  └── Alert Broadcasting (THREAT_DETECTED, EVACUATE, CLEAR)  │
└─────────────────────────────────────────────────────────────┘
```

## Features

### Sensor Mesh
- Real-time audio monitoring via foreground service
- RMS-based amplitude detection with configurable threshold
- Automatic peer discovery and connection via Nearby Connections

### Alert States
1. **LISTENING** - Normal monitoring state with pulsing green indicator
2. **BARRICADE** - Red screen with lockdown instructions (threat in zone)
3. **EVACUATE** - Green screen with escape route (safe to evacuate)

### P2P Communication
- Uses Google Nearby Connections API (P2P_CLUSTER strategy)
- Automatic mesh formation with nearby devices
- Broadcast alerts propagate to all connected peers

## Building

```bash
# Clone and navigate to project
cd shieldy

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

## Required Permissions

| Permission | Purpose |
|------------|---------|
| `RECORD_AUDIO` | Threat audio detection |
| `ACCESS_FINE_LOCATION` | Nearby device discovery |
| `BLUETOOTH_*` | P2P mesh network |
| `FOREGROUND_SERVICE_MICROPHONE` | Background monitoring |
| `POST_NOTIFICATIONS` | Service notification |

## Testing the MVP

1. Install on 2+ Android devices
2. Grant all permissions when prompted
3. Devices will auto-discover and connect
4. Use "SIMULATE THREAT" button to trigger alert
5. Alert will propagate to all connected peers
6. Toggle between BARRICADE/EVACUATE modes
7. Use "ALL CLEAR" to reset

## Project Structure

```
app/src/main/java/com/echoshield/echonode/
├── MainActivity.kt              # Entry point, permission handling
├── EchoShieldApp.kt             # Application class
├── data/
│   ├── MeshNetworkManager.kt    # Nearby Connections P2P mesh
│   └── SystemEventFlow.kt       # Cross-component event bus
├── service/
│   └── AudioSensorService.kt    # Foreground audio monitoring
├── viewmodel/
│   └── MainViewModel.kt         # UI state management
└── ui/
    ├── Screens.kt               # Compose UI screens
    └── theme/
        └── Theme.kt             # Material 3 dark theme
```

## Hackathon Notes

This MVP swaps the production TFLite audio classification model for a simple amplitude threshold detector. The mesh consensus is simulated via Nearby Connections broadcast.

For production:
- Replace amplitude threshold with TFLite gunshot/scream classifier
- Add location-based zone routing for "Silent Escort"
- Implement secure alert verification protocol
- Add admin dashboard for school security

## License

Hackathon Project - For demonstration purposes only.
