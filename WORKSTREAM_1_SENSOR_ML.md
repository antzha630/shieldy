# Workstream 1 - Sensor + ML Team

## Owner Profile

- **Skill level needed:** **Strongest coding ability on team**
- Why: this stream handles real-time audio pipelines, model inference, threading, battery/performance tradeoffs, and trigger correctness.

## Folder Ownership

- Owns: `app/src/main/java/com/echoshield/echonode/sensor/`
- Can edit: `app/src/main/java/com/echoshield/echonode/service/` (implementation internals only)
- Must not edit without approval:
  - `app/src/main/java/com/echoshield/echonode/comms/`
  - `app/src/main/java/com/echoshield/echonode/experience/`
  - `app/src/main/java/com/echoshield/echonode/ui/`
  - `app/src/main/java/com/echoshield/echonode/MainActivity.kt`
  - `app/src/main/java/com/echoshield/echonode/core/AppContainer.kt`

## Primary Goal

Implement robust two-tier detection:

1. low-power activity gate (cheap signal threshold),
2. expensive ML verification (YAMNet/custom model),
3. emit reliable local trigger events with low false positives.

## Contracts You Must Respect

- `app/src/main/java/com/echoshield/echonode/core/contracts/EchoContracts.kt`
  - `SensorGateway`
  - `DetectionTelemetry`
- Local trigger event path must keep working through gateway telemetry/events.

## Deliverables

- Improve/replace `SensorGatewayImpl` internals to support:
  - calibration profile(s),
  - configurable gate + confidence threshold,
  - cooldown/debounce logic,
  - telemetry fields for debugging.
- Keep `AudioSensorService` foreground-safe and permission-safe.
- Document tuning defaults in code comments.

## Done Criteria

- Compiles with `./gradlew :app:compileDebugKotlin`.
- On Pixel device:
  - normal speech does not constantly trigger,
  - a sharp test impulse can trigger once and cool down,
  - telemetry updates in UI.

## Prompt for Agent 1

```text
You are the Sensor+ML agent for EchoShield Android.

Read and follow:
- WORKSTREAM_1_SENSOR_ML.md
- WORKSTREAMS.md
- app/src/main/java/com/echoshield/echonode/core/contracts/EchoContracts.kt

Your ownership:
- app/src/main/java/com/echoshield/echonode/sensor/
- app/src/main/java/com/echoshield/echonode/service/ (internals only)

Task:
Implement a production-minded two-tier detector:
1) low-power activity gate,
2) ML confirmation (YAMNet currently; design so custom model can swap in),
3) emit local trigger events through SensorGateway contract.

Constraints:
- Do not modify comms/experience/ui packages.
- Do not change MainActivity.kt or AppContainer.kt.
- If a contract change is needed, propose exact EchoContracts.kt diff only.
- Keep battery and false-positive reduction as primary goals.

Output:
- Code changes
- brief tuning rationale
- test steps on Pixel
```
