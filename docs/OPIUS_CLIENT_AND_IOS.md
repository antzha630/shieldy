# Opius User Client & iOS Path

## Opius web client (`GET /app`)

The Node relay now serves a mobile-framed user-facing client — the "Opius" app from
the prototype — at **`/app`** (alias `/opius`). It is a single self-contained page
(`opiusAppHtml()` in `server/src/server.js`) with three tabs, wired to the live relay
endpoints instead of mock state:

| Tab | What it shows | Backend |
| --- | --- | --- |
| **Home** | Listening/Active-Alert status, safety summary, **first-aid steps**, live updates | polls `GET /v1/incidents/latest` every 5 s |
| **Chat** | Assistant conversation + quick replies (I'm safe / I need help / injured / where's the shooter) | `POST /v1/incidents/:id/authority-messages` (role `user`) |
| **Update** | Share status form: building / floor / room / people / injured / critical | `POST /v1/incidents/:id/notes` |

It reuses the existing incident model: live updates, `firstAid` guidance, zones (as
building options), notes (status reports), and authority messages (chat). It renders on
any phone browser and is installable as a PWA-style home-screen app.

**Why this is the cross-platform / iOS client.** The README notes that iOS parity
"requires backend relay architecture." This web client *is* that relay client: it works
on iOS Safari and Android Chrome today with no app-store step, and talks to the same
incident state the Android mesh feeds into.

Auth note: write endpoints honor `ECHOSHIELD_RELAY_API_KEY` when set. For a public user
client you would front these with a lightweight per-user token rather than the shared
relay key; the demo leaves them open when no key is configured.

## The web client as a sensor node (built)

The Opius client is not only a viewer. With **listening mode** on, the page joins the
mesh bus as a voting sensor, which is what makes an iPhone a participant in detection
rather than a spectator:

| Step | Mechanism |
| --- | --- |
| Join the bus | `EventSource` on `GET /v1/mesh/stream`; the relay returns a per-device `busToken` in its `hello` frame |
| Share a clock | five round trips against `GET /v1/time`, keeping the lowest-latency sample (offset, plus `rtt / 2` as its uncertainty) |
| Listen | `getUserMedia` → `ScriptProcessorNode` (1024 frames), with echo cancellation, noise suppression and AGC **off** so impulses are not flattened |
| Detect | peak level, crest factor (peak/RMS), rise over a running background level, and sample clipping |
| Timestamp | the loudest sample's index inside the buffer, converted to the server clock |
| Raise | `POST /v1/mesh/detections` (rate-limited to one per 6 s) |
| Vote | on `wake_classify`, reports whether *this* phone heard an impulse in the last 3 s, with position, altitude and arrival time |
| Respond | `response_trigger` / `location_refined` surface immediately instead of waiting for the 5 s poll |

**Honest limits — stated in the UI as well as here.**

- This is an **impulsive-transient detector, not a trained gunshot classifier.** It has
  no equivalent of the Android node's YAMNet head. It is built to *corroborate* what
  other phones report; a fleet of only web nodes would be markedly more false-positive
  prone than one with Android sensors in it. The anti-herding quorum is what keeps that
  safe: a single web node can never trigger a response on its own.
- **Timing is coarser than Android's.** The clock offset is known to about `rtt / 2`
  against the relay, plus half a buffer (~12 ms at 44.1 kHz). At 0.343 m/ms that is
  several metres of ranging error, and the server refuses to triangulate at all when
  the uncertainty exceeds 60 ms.
- **Listening mode is explicit and user-visible**, never silent. That is both the
  honest design and the only pattern Apple permits. The status pill reads "Alerts on"
  rather than "Listening" until the microphone is actually running.
- **Backgrounding.** Mobile Safari suspends `AudioContext` when the tab is
  backgrounded, so a web node listens while the page is open and in front. Continuous
  background listening is what the native target below is for.

## Native iOS listening mode (design, not yet built)

A true native iOS sensor node (background gunshot listening, like the Android
`AudioSensorService`) is a separate app target, not part of this repo. The valid Apple
pattern is a **user-visible continuous listening mode** (not stealth/always-on):

1. User opens the app and grants microphone permission (`NSMicrophoneUsageDescription`).
2. User explicitly starts "listening mode".
3. App starts `AVAudioSession` + `AVAudioEngine` tap for capture.
4. To keep capturing when backgrounded/locked, declare the `audio` value in
   `UIBackgroundModes`.

On-device classification would run the same YAMNet-class model via Core ML / TFLite, and
detections would publish to this relay (the `/v1/mesh/alerts` and
`/v1/incidents/reports` endpoints the Android client already uses), so an iOS node slots
into the existing consensus + triangulation pipeline without server changes.
