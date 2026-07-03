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
