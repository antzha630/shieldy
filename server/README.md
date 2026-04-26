# EchoShield Relay Server

Small zero-dependency Node server for the hackathon cloud path.

It receives the Android cloud relay envelope at `POST /v1/mesh/alerts`, dedupes packets, builds an incident record, and exposes a responder console.

## Run

```bash
cd server
npm start
```

Open:

- `http://localhost:8787/health`
- `http://localhost:8787/dashboard`
- `http://localhost:8787/dispatch`
- `http://localhost:8787/v1/incidents`

Optional bearer token:

```bash
ECHOSHIELD_RELAY_API_KEY=devsecret npm start
```

## Automated Dispatch Notification

For the hackathon demo, the server can notify a configured contact when an incident reaches `CONFIRMED_RESPONSE`.

SMS through Twilio:

```bash
TWILIO_ACCOUNT_SID=AC... \
TWILIO_AUTH_TOKEN=... \
TWILIO_FROM=+15551234567 \
DISPATCH_SMS_TO=+15557654321 \
npm start
```

Email through SendGrid:

```bash
SENDGRID_API_KEY=SG... \
SENDGRID_FROM=alerts@yourdomain.com \
DISPATCH_EMAIL_TO=demo-responder@example.com \
npm start
```

If neither provider is configured, the dashboard still creates a dispatch-ready police/medical brief and records that notification was skipped.

## Simulated Authority Chat

For the safest and fastest hackathon demo, use the built-in dispatch simulator instead of real SMS/email.

Open:

```text
http://localhost:8787/dispatch
```

When a `RESPONSE:TRIGGER` packet arrives, the server automatically seeds the incident with simulated authority messages:

- EchoShield Dispatch opens the incident.
- Police Dispatcher assigns a response unit.
- EMS Coordinator requests injured counts and room/location details.
- Police Dispatcher gives shelter/route guidance.

Judges can type as a dispatcher in the browser, and messages are appended to the incident log through:

```text
POST /v1/incidents/:id/authority-messages
```

## Android Config

Add this to app `local.properties` for a real phone on the same Wi-Fi:

```properties
CLOUD_RELAY_URL=http://<your-computer-lan-ip>:8787/
CLOUD_RELAY_API_KEY=devsecret
```

Leave `CLOUD_RELAY_URL` empty to keep the app local/P2P only.

## Demo Packet

```bash
curl -X POST http://localhost:8787/v1/mesh/alerts \
  -H 'content-type: application/json' \
  -d '{
    "protocolVersion": 1,
    "deviceId": "EchoNode-demo-a",
    "messageId": "response-demo-1",
    "alertType": "RESPONSE:TRIGGER",
    "body": null,
    "payload": "RESPONSE:TRIGGER|demo-session|37.7749|-122.4194|EchoNode-demo-a,EchoNode-demo-b|1710000000000",
    "sourceNodeId": "EchoNode-demo-a",
    "observedAtMs": 1710000000000,
    "connectedPeerCount": 2,
    "leaderNodeId": "EchoNode-demo-a",
    "dutyEpoch": 1,
    "sessionId": "demo-session",
    "latitude": 37.7749,
    "longitude": -122.4194,
    "confirmedByNodes": ["EchoNode-demo-a", "EchoNode-demo-b"]
  }'
```

## Endpoints

- `POST /v1/mesh/alerts`: accepts important mobile relay packets.
- `POST /v1/incidents/reports`: accepts phone UI safety/room/note/location reports and logs them to the terminal.
- `GET /v1/incidents`: returns all tracked incidents.
- `GET /v1/incidents/latest`: latest active incident.
- `GET /v1/incidents/:id`: one incident.
- `POST /v1/incidents/:id/notes`: future law/medical note intake.
- `POST /v1/incidents/:id/authority-messages`: simulated authority chat.
- `GET /dashboard`: simple auto-refresh responder console.
- `GET /dispatch`: simulated police/EMS console and chat.

This is intentionally not a production emergency-services integration. It is a hackathon-safe stub that creates the exact object a real dispatch integration or agentic summarizer would consume.
