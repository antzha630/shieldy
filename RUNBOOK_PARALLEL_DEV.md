# Parallel Dev Runbook (3 Agents / 3 Teammates)

This runbook keeps merge conflicts low while three streams ship in parallel.

## Streams

- Sensor/ML: `WORKSTREAM_1_SENSOR_ML.md`
- Comms/Network: `WORKSTREAM_2_COMMS_NETWORK.md`
- Experience/UI: `WORKSTREAM_3_EXPERIENCE_UI.md`

## One-Time Setup (Everyone)

```bash
git checkout main
git pull origin main
```

Create a feature branch by stream:

```bash
# examples
git checkout -b sensor/two-tier-gating
git checkout -b comms/leader-rotation
git checkout -b experience/safety-check-flow
```

## Ownership Boundaries

- Sensor edits only sensor/service internals and sensor contract consumption.
- Comms edits only comms/data networking internals and comms contract consumption.
- Experience edits only experience/ui/viewmodel presentation wiring.
- Integration owner handles:
  - `AppContainer.kt`
  - `MainActivity.kt`
  - dependency merges in `build.gradle.kts`

## Contract-First Workflow

All cross-team APIs live in:

- `app/src/main/java/com/echoshield/echonode/core/contracts/EchoContracts.kt`

Rule:

1. propose contract diff in tiny PR,
2. merge contract PR first,
3. each stream implements against merged contract.

## Daily Merge Sequence

Use this order each day:

1. merge contract-only PRs,
2. merge Sensor PR,
3. merge Comms PR,
4. merge Experience PR,
5. integration owner final glue PR.

Between each merge:

```bash
git checkout main
git pull origin main
```

## PR Size Rule

- Keep PRs under ~400 changed lines when possible.
- Large tasks should be split into:
  - contract PR
  - implementation PR
  - cleanup/tuning PR

## Dependency Rule

If any stream needs a new dependency:

1. post request in team chat,
2. integration owner adds dependency on `main`,
3. everyone rebases/pulls immediately.

## Rebase Habit

Before opening PR:

```bash
git fetch origin
git rebase origin/main
./gradlew :app:compileDebugKotlin
```

## Conflict Escape Hatch

If two streams must touch same file:

1. do not both edit simultaneously,
2. create a short-lived integration branch,
3. pair-merge the file once,
4. cherry-pick minimal commits back to stream branches.

## Demo Safety Checklist (Pre-Hackathon Demo)

- `./gradlew :app:compileDebugKotlin` passes.
- Build debug APK and install on 2 real Android phones.
- Verify:
  - manual threat propagation,
  - ML trigger path,
  - barricade/evacuate flow,
  - all-clear reset.
