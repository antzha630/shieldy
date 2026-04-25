# Workstream 3 - Experience + UI Team

## Owner Profile

- **Skill level needed:** Medium (Compose + state flow integration)
- Why: this stream owns UX speed, usability under stress, and incident data capture screens.

## Folder Ownership

- Owns:
  - `app/src/main/java/com/echoshield/echonode/experience/`
  - `app/src/main/java/com/echoshield/echonode/ui/`
  - `app/src/main/java/com/echoshield/echonode/viewmodel/`
- Must not edit without approval:
  - `app/src/main/java/com/echoshield/echonode/sensor/`
  - `app/src/main/java/com/echoshield/echonode/comms/`
  - `app/src/main/java/com/echoshield/echonode/data/`
  - `app/src/main/java/com/echoshield/echonode/MainActivity.kt` (unless designated owner)
  - `app/src/main/java/com/echoshield/echonode/core/AppContainer.kt`

## Primary Goal

Build clear emergency UX flows and capture useful responder input while consuming orchestration state.

## Contracts You Must Respect

- `app/src/main/java/com/echoshield/echonode/core/contracts/EchoContracts.kt`
  - `EchoUiState`
  - `AppState`
- Consume state from orchestrator/viewmodel, avoid direct sensor/comms coupling.

## Deliverables

- Add screens:
  - location confirmation,
  - safety check (status buttons),
  - notes/voice notes input.
- Keep barricade/evacuate visuals high-contrast and immediate.
- Add smooth transitions and preserve existing debug controls.

## Done Criteria

- Compiles with `./gradlew :app:compileDebugKotlin`.
- All primary states are reachable with mock/manual triggers.
- Inputs from new screens are stored in UI state and ready for backend handoff.

## Prompt for Agent 3

```text
You are the Experience+UI agent for EchoShield Android.

Read and follow:
- WORKSTREAM_3_EXPERIENCE_UI.md
- WORKSTREAMS.md
- app/src/main/java/com/echoshield/echonode/core/contracts/EchoContracts.kt

Your ownership:
- app/src/main/java/com/echoshield/echonode/experience/
- app/src/main/java/com/echoshield/echonode/ui/
- app/src/main/java/com/echoshield/echonode/viewmodel/

Task:
Implement the next UX slice:
1) Location Confirmation screen,
2) Safety Check screen,
3) Notes/voice-note incident capture screen.
Wire these into current app state/navigation without changing sensor/comms internals.

Constraints:
- Do not modify sensor/comms/data packages.
- Do not change MainActivity.kt or AppContainer.kt unless explicitly assigned.
- If a contract change is needed, propose exact EchoContracts.kt diff only.

Output:
- Compose screen implementations
- state wiring in experience/viewmodel
- demo flow checklist
```
