# Workstream Ownership Guide

This file defines conflict-minimizing ownership so three teammates can work in parallel.

## Folder Ownership

- `app/src/main/java/com/echoshield/echonode/sensor/`
  - Owner: Sensor Team
  - Scope: audio capture strategy, ML inference, battery-aware trigger gating
- `app/src/main/java/com/echoshield/echonode/comms/`
  - Owner: Comms Team
  - Scope: Nearby behavior, leader election, backend relay
- `app/src/main/java/com/echoshield/echonode/experience/`
  - Owner: Experience Team
  - Scope: app orchestration logic, UX state machine, feature flows
- `app/src/main/java/com/echoshield/echonode/ui/`
  - Owner: Experience Team
  - Scope: Compose screens only
- `app/src/main/java/com/echoshield/echonode/core/contracts/`
  - Shared contract zone
  - Changes require team sync first
- `app/src/main/java/com/echoshield/echonode/core/AppContainer.kt`
  - Integration owner only
  - Wire concrete implementations to contracts

## Contract-First Rule

Before implementing in each silo, agree on contract changes in:

- `core/contracts/EchoContracts.kt`

Do not directly import another team's internal classes. Use contract interfaces only.

## Minimal Shared Touch Points

- `MainActivity.kt` - UI/integration owner
- `viewmodel/MainViewModel.kt` - integration facade for existing UI
- `app/build.gradle.kts` - one dependency gatekeeper per PR

## Parallel Workflow

1. Propose contract change in `EchoContracts.kt`.
2. Merge contract PR first.
3. Each team builds in its folder only.
4. Integration owner updates `AppContainer.kt` after each stream lands.

## Suggested Branch Names

- `sensor/<feature-name>`
- `comms/<feature-name>`
- `experience/<feature-name>`
- `integration/<feature-name>`
