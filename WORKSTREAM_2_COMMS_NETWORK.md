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

## Primary Goal

Harden communication path:

1. reliable Nearby mesh propagation,
2. leader rotation / duty assignment protocol,
3. optional cloud relay fallback for cross-device reliability.

## Contracts You Must Respect

- `app/src/main/java/com/echoshield/echonode/core/contracts/EchoContracts.kt`
  - `MeshGateway`
  - `MeshStatus`
- Keep payload compatibility with existing orchestrator behavior.

## Deliverables

- Leader election abstraction and periodic rotation strategy.
- Deduped rebroadcast behavior with bounded memory.
- Cloud sync client scaffolding (Retrofit/OkHttp) isolated in comms layer.
- Clear error/status exposure through `MeshGateway.meshStatus`.

## Done Criteria

- Compiles with `./gradlew :app:compileDebugKotlin`.
- Two real phones can exchange threat + clear messages.
- Comms failures degrade gracefully (no app crash, status updates still flow).

## Prompt for Agent 2

```text
You are the Comms+Networking agent for EchoShield Android.

Read and follow:
- WORKSTREAM_2_COMMS_NETWORK.md
- WORKSTREAMS.md
- app/src/main/java/com/echoshield/echonode/core/contracts/EchoContracts.kt

Your ownership:
- app/src/main/java/com/echoshield/echonode/comms/
- app/src/main/java/com/echoshield/echonode/data/ (network internals only)

Task:
Improve mesh + backend communication:
1) implement leader rotation/duty assignment strategy,
2) harden rebroadcast and dedupe reliability,
3) add cloud relay scaffolding (Retrofit/OkHttp), isolated from UI.

Constraints:
- Do not modify sensor/experience/ui packages.
- Do not change MainActivity.kt or AppContainer.kt.
- If contract changes are necessary, propose exact EchoContracts.kt diff only.
- Preserve current payload semantics and emergency latency.

Output:
- Code changes
- protocol summary
- device-to-device test plan
```
