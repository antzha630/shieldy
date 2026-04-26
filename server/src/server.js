import http from "node:http";
import crypto from "node:crypto";

const PORT = Number.parseInt(process.env.PORT || "8787", 10);
const API_KEY = process.env.ECHOSHIELD_RELAY_API_KEY || "";
const INCIDENT_TTL_MS = Number.parseInt(process.env.INCIDENT_TTL_MS || "900000", 10);
const JSON_LIMIT_BYTES = 1024 * 1024;

const TWILIO_ACCOUNT_SID = process.env.TWILIO_ACCOUNT_SID || "";
const TWILIO_AUTH_TOKEN = process.env.TWILIO_AUTH_TOKEN || "";
const TWILIO_FROM = process.env.TWILIO_FROM || "";
const DISPATCH_SMS_TO = process.env.DISPATCH_SMS_TO || "";

const SENDGRID_API_KEY = process.env.SENDGRID_API_KEY || "";
const SENDGRID_FROM = process.env.SENDGRID_FROM || "";
const DISPATCH_EMAIL_TO = process.env.DISPATCH_EMAIL_TO || "";

const incidents = new Map();
const seenMessages = new Set();

function nowIso() {
  return new Date().toISOString();
}

function json(res, status, body) {
  const encoded = JSON.stringify(body, null, 2);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  });
  res.end(encoded);
}

function text(res, status, body, contentType = "text/plain; charset=utf-8") {
  res.writeHead(status, {
    "content-type": contentType,
    "cache-control": "no-store"
  });
  res.end(body);
}

function authenticate(req) {
  if (!API_KEY) return true;
  const authorization = req.headers.authorization || "";
  return authorization === `Bearer ${API_KEY}`;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];

    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > JSON_LIMIT_BYTES) {
        reject(new Error("JSON body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      if (!raw.trim()) {
        resolve({});
        return;
      }

      try {
        resolve(JSON.parse(raw));
      } catch (error) {
        reject(error);
      }
    });

    req.on("error", reject);
  });
}

function parsePayload(payload = "") {
  const parts = String(payload).split("|");
  const type = parts[0] || "";

  if (type === "RESPONSE:TRIGGER") {
    return {
      type,
      sessionId: parts[1] || null,
      latitude: numberOrNull(parts[2]),
      longitude: numberOrNull(parts[3]),
      confirmedByNodes: splitNodes(parts[4]),
      timestamp: numberOrNull(parts[5])
    };
  }

  return {
    type,
    messageId: parts[1] || null,
    body: parts[2] || null
  };
}

function numberOrNull(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function splitNodes(value) {
  if (!value) return [];
  return String(value)
    .split(",")
    .map((node) => node.trim())
    .filter(Boolean);
}

function listValue(value) {
  if (Array.isArray(value)) return value;
  if (value === null || value === undefined || value === "") return [];
  return [String(value)];
}

function incidentIdFor(envelope, parsed) {
  if (envelope.sessionId || parsed.sessionId) {
    return `session-${envelope.sessionId || parsed.sessionId}`;
  }

  if (envelope.alertType === "ALERT:ALL_CLEAR") {
    const latest = latestActiveIncident();
    return latest?.id || `message-${envelope.messageId || crypto.randomUUID()}`;
  }

  const latest = latestActiveIncident();
  const observedAt = numberOrNull(envelope.observedAtMs) || Date.now();
  if (latest && observedAt - latest.lastObservedAtMs <= INCIDENT_TTL_MS) {
    return latest.id;
  }

  return `message-${envelope.messageId || crypto.randomUUID()}`;
}

function latestActiveIncident() {
  return [...incidents.values()]
    .filter((incident) => incident.status !== "CLEARED")
    .sort((a, b) => b.lastObservedAtMs - a.lastObservedAtMs)[0] || null;
}

function createIncident(id, observedAtMs) {
  return {
    id,
    status: "DETECTED",
    createdAt: nowIso(),
    updatedAt: nowIso(),
    firstObservedAtMs: observedAtMs,
    lastObservedAtMs: observedAtMs,
    zones: [],
    routes: [],
    devices: [],
    leaders: [],
    confirmedByNodes: [],
    observations: [],
    notes: [],
    location: null,
    dispatchRecommended: false,
    policeBrief: "No incident data yet.",
    medicalBrief: "No medical notes received yet.",
    recommendedAction: "Monitor for peer confirmation.",
    dispatchNotifiedAt: null,
    notificationAttempts: []
  };
}

function upsertIncident(envelope) {
  const parsed = parsePayload(envelope.payload);
  const observedAtMs = numberOrNull(envelope.observedAtMs) || Date.now();
  const id = incidentIdFor(envelope, parsed);
  const incident = incidents.get(id) || createIncident(id, observedAtMs);
  const messageKey = `${envelope.messageId || parsed.messageId || crypto.randomUUID()}:${envelope.deviceId || "unknown"}`;
  const isDuplicate = seenMessages.has(messageKey);

  if (!isDuplicate) {
    seenMessages.add(messageKey);
    incident.observations.push({
      receivedAt: nowIso(),
      messageId: envelope.messageId || parsed.messageId || null,
      alertType: envelope.alertType || parsed.type || "UNKNOWN",
      payload: envelope.payload || "",
      deviceId: envelope.deviceId || null,
      sourceNodeId: envelope.sourceNodeId || null,
      connectedPeerCount: envelope.connectedPeerCount || 0,
      leaderNodeId: envelope.leaderNodeId || null,
      dutyEpoch: envelope.dutyEpoch || null
    });
  }

  addUnique(incident.devices, envelope.deviceId);
  addUnique(incident.devices, envelope.sourceNodeId);
  addUnique(incident.leaders, envelope.leaderNodeId);
  addUnique(incident.zones, envelope.body && envelope.alertType === "ALERT:THREAT_DETECTED" ? envelope.body : null);
  addUnique(incident.routes, envelope.body && envelope.alertType === "ALERT:EVACUATE" ? envelope.body : null);

  const latitude = numberOrNull(envelope.latitude) ?? parsed.latitude;
  const longitude = numberOrNull(envelope.longitude) ?? parsed.longitude;
  if (latitude !== null && longitude !== null) {
    incident.location = { latitude, longitude };
  }

  const confirmedByNodes = [
    ...splitNodes(listValue(envelope.confirmedByNodes).join(",")),
    ...splitNodes(listValue(parsed.confirmedByNodes).join(","))
  ];
  confirmedByNodes.forEach((node) => addUnique(incident.confirmedByNodes, node));

  applyStatus(incident, envelope.alertType || parsed.type);
  incident.lastObservedAtMs = Math.max(incident.lastObservedAtMs, observedAtMs);
  incident.updatedAt = nowIso();
  incident.dispatchRecommended = incident.status === "CONFIRMED_RESPONSE";
  incident.recommendedAction = recommendationFor(incident);
  incident.policeBrief = policeBriefFor(incident);
  incident.medicalBrief = medicalBriefFor(incident);

  incidents.set(id, incident);
  scheduleDispatchNotification(incident);
  return { incident, duplicate: isDuplicate };
}

function applyStatus(incident, alertType) {
  if (alertType === "ALERT:ALL_CLEAR") {
    incident.status = "CLEARED";
    return;
  }

  if (alertType === "ALERT:EVACUATE") {
    incident.status = "EVACUATE";
    return;
  }

  if (alertType === "RESPONSE:TRIGGER") {
    incident.status = "CONFIRMED_RESPONSE";
    return;
  }

  if (alertType === "ALERT:THREAT_DETECTED" && incident.status === "DETECTED") {
    incident.status = "DETECTED";
  }
}

function addUnique(list, value) {
  if (!value) return;
  if (!list.includes(value)) list.push(value);
}

function recommendationFor(incident) {
  if (incident.status === "CONFIRMED_RESPONSE") {
    return "Dispatch law enforcement/EMS, push area guidance, continue receiving mesh observations.";
  }
  if (incident.status === "EVACUATE") {
    return `Evacuation route active${incident.routes[0] ? `: ${incident.routes[0]}` : ""}.`;
  }
  if (incident.status === "CLEARED") {
    return "Incident cleared by mesh all-clear packet.";
  }
  return "Potential threat detected. Wait for peer confirmation before dispatch escalation.";
}

function policeBriefFor(incident) {
  const location = incident.location
    ? `${incident.location.latitude.toFixed(6)}, ${incident.location.longitude.toFixed(6)}`
    : "location unavailable";
  const zones = incident.zones.length ? incident.zones.join(", ") : "zone unavailable";
  const confirmed = incident.confirmedByNodes.length || 0;
  return [
    `EchoShield incident ${incident.id}: ${incident.status}.`,
    `Observed by ${incident.devices.length} device(s), confirmed by ${confirmed} node(s).`,
    `Location: ${location}. Zone: ${zones}.`,
    incident.routes.length ? `Route guidance: ${incident.routes.join(", ")}.` : "",
    incident.notes.length ? `Latest note: ${incident.notes[incident.notes.length - 1].note || "no freeform note"}.` : ""
  ].filter(Boolean).join(" ");
}

function medicalBriefFor(incident) {
  const injured = incident.notes.reduce((sum, note) => sum + (Number(note.injuredCount) || 0), 0);
  const companions = incident.notes.reduce((sum, note) => sum + (Number(note.companionsCount) || 0), 0);
  if (!incident.notes.length) return "No medical notes received yet.";
  return `Reports mention ${injured} injured person(s) and ${companions} companion(s) near users who submitted notes.`;
}

function dispatchMessageFor(incident) {
  return [
    "ECHOSHIELD AUTOMATED ALERT",
    incident.policeBrief,
    `Recommended action: ${incident.recommendedAction}`,
    `Medical: ${incident.medicalBrief}`,
    "Hackathon demo notice: verify before treating as an official emergency dispatch."
  ].join("\n");
}

function scheduleDispatchNotification(incident) {
  if (incident.status !== "CONFIRMED_RESPONSE" || incident.dispatchNotifiedAt) {
    return;
  }

  if (!hasDispatchChannel()) {
    incident.notificationAttempts.push({
      channel: "none",
      status: "skipped",
      at: nowIso(),
      detail: "No SMS or email dispatch environment variables configured."
    });
    return;
  }

  incident.dispatchNotifiedAt = nowIso();
  sendDispatchNotification(incident).catch((error) => {
    incident.notificationAttempts.push({
      channel: "dispatch",
      status: "failed",
      at: nowIso(),
      detail: error.message
    });
    incident.dispatchNotifiedAt = null;
  });
}

function hasDispatchChannel() {
  return hasTwilioConfig() || hasSendGridConfig();
}

function hasTwilioConfig() {
  return Boolean(TWILIO_ACCOUNT_SID && TWILIO_AUTH_TOKEN && TWILIO_FROM && DISPATCH_SMS_TO);
}

function hasSendGridConfig() {
  return Boolean(SENDGRID_API_KEY && SENDGRID_FROM && DISPATCH_EMAIL_TO);
}

async function sendDispatchNotification(incident) {
  const message = dispatchMessageFor(incident);
  const attempts = [];

  if (hasTwilioConfig()) {
    attempts.push(await sendTwilioSms(message));
  }

  if (hasSendGridConfig()) {
    attempts.push(await sendSendGridEmail(incident, message));
  }

  incident.notificationAttempts.push(...attempts);
  incident.updatedAt = nowIso();
}

async function sendTwilioSms(message) {
  const url = `https://api.twilio.com/2010-04-01/Accounts/${encodeURIComponent(TWILIO_ACCOUNT_SID)}/Messages.json`;
  const body = new URLSearchParams({
    From: TWILIO_FROM,
    To: DISPATCH_SMS_TO,
    Body: message.slice(0, 1500)
  });
  const authorization = Buffer.from(`${TWILIO_ACCOUNT_SID}:${TWILIO_AUTH_TOKEN}`).toString("base64");

  const response = await fetch(url, {
    method: "POST",
    headers: {
      authorization: `Basic ${authorization}`,
      "content-type": "application/x-www-form-urlencoded"
    },
    body
  });

  const responseBody = await response.text();
  return {
    channel: "sms",
    status: response.ok ? "sent" : "failed",
    at: nowIso(),
    destination: DISPATCH_SMS_TO,
    providerStatus: response.status,
    detail: response.ok ? "Twilio accepted SMS" : responseBody.slice(0, 500)
  };
}

async function sendSendGridEmail(incident, message) {
  const response = await fetch("https://api.sendgrid.com/v3/mail/send", {
    method: "POST",
    headers: {
      authorization: `Bearer ${SENDGRID_API_KEY}`,
      "content-type": "application/json"
    },
    body: JSON.stringify({
      personalizations: [{ to: [{ email: DISPATCH_EMAIL_TO }] }],
      from: { email: SENDGRID_FROM },
      subject: `EchoShield alert: ${incident.status} ${incident.id}`,
      content: [{ type: "text/plain", value: message }]
    })
  });

  const responseBody = await response.text();
  return {
    channel: "email",
    status: response.ok ? "sent" : "failed",
    at: nowIso(),
    destination: DISPATCH_EMAIL_TO,
    providerStatus: response.status,
    detail: response.ok ? "SendGrid accepted email" : responseBody.slice(0, 500)
  };
}

function publicIncident(incident) {
  return {
    ...incident,
    observationCount: incident.observations.length,
    observations: incident.observations.slice(-25),
    notes: incident.notes.slice(-25)
  };
}

async function handleAlert(req, res) {
  if (!authenticate(req)) {
    json(res, 401, { ok: false, error: "Unauthorized" });
    return;
  }

  let envelope;
  try {
    envelope = await readJson(req);
  } catch (error) {
    json(res, 400, { ok: false, error: `Invalid JSON: ${error.message}` });
    return;
  }

  if (!envelope || !envelope.payload || !envelope.alertType) {
    json(res, 422, { ok: false, error: "Expected cloud relay envelope with alertType and payload." });
    return;
  }

  const { incident, duplicate } = upsertIncident(envelope);
  json(res, duplicate ? 200 : 202, {
    ok: true,
    duplicate,
    incidentId: incident.id,
    status: incident.status,
    dispatchRecommended: incident.dispatchRecommended,
    recommendedAction: incident.recommendedAction,
    policeBrief: incident.policeBrief
  });
}

async function handleNote(req, res, incidentId) {
  if (!authenticate(req)) {
    json(res, 401, { ok: false, error: "Unauthorized" });
    return;
  }

  const incident = incidents.get(incidentId);
  if (!incident) {
    json(res, 404, { ok: false, error: "Incident not found" });
    return;
  }

  let note;
  try {
    note = await readJson(req);
  } catch (error) {
    json(res, 400, { ok: false, error: `Invalid JSON: ${error.message}` });
    return;
  }

  const stored = {
    id: crypto.randomUUID(),
    receivedAt: nowIso(),
    deviceId: note.deviceId || null,
    safetyStatus: note.safetyStatus || "UNKNOWN",
    injuredCount: Number(note.injuredCount) || 0,
    companionsCount: Number(note.companionsCount) || 0,
    roomNumber: note.roomNumber || "",
    note: note.note || "",
    latitude: numberOrNull(note.latitude),
    longitude: numberOrNull(note.longitude)
  };

  incident.notes.push(stored);
  incident.updatedAt = nowIso();
  incident.medicalBrief = medicalBriefFor(incident);
  incident.policeBrief = policeBriefFor(incident);
  incidents.set(incident.id, incident);

  json(res, 202, {
    ok: true,
    incidentId: incident.id,
    noteId: stored.id,
    policeBrief: incident.policeBrief,
    medicalBrief: incident.medicalBrief
  });
}

function dashboardHtml() {
  const rows = [...incidents.values()]
    .sort((a, b) => b.lastObservedAtMs - a.lastObservedAtMs)
    .map((incident) => `
      <article>
        <h2>${escapeHtml(incident.status)} <code>${escapeHtml(incident.id)}</code></h2>
        <p><strong>Action:</strong> ${escapeHtml(incident.recommendedAction)}</p>
        <p><strong>Police:</strong> ${escapeHtml(incident.policeBrief)}</p>
        <p><strong>Medical:</strong> ${escapeHtml(incident.medicalBrief)}</p>
        <p><strong>Devices:</strong> ${incident.devices.length} | <strong>Observations:</strong> ${incident.observations.length}</p>
      </article>
    `).join("\n");

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="refresh" content="5">
  <title>EchoShield Relay</title>
  <style>
    body { margin: 0; font-family: system-ui, sans-serif; background: #f7f7f4; color: #161616; }
    main { max-width: 980px; margin: 0 auto; padding: 24px; }
    article { background: white; border: 1px solid #ddd; border-radius: 8px; padding: 16px; margin: 16px 0; }
    code { font-size: 0.8em; color: #555; }
  </style>
</head>
<body>
  <main>
    <h1>EchoShield Relay Console</h1>
    <p>${incidents.size} incident(s) tracked. Refreshes every 5 seconds.</p>
    ${rows || "<p>No incidents yet.</p>"}
  </main>
</body>
</html>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function route(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const pathname = url.pathname.replace(/\/+$/, "") || "/";

  if (req.method === "GET" && pathname === "/health") {
    json(res, 200, { ok: true, service: "echoshield-relay", time: nowIso() });
    return;
  }

  if (req.method === "GET" && (pathname === "/" || pathname === "/dashboard")) {
    text(res, 200, dashboardHtml(), "text/html; charset=utf-8");
    return;
  }

  if (req.method === "POST" && pathname === "/v1/mesh/alerts") {
    await handleAlert(req, res);
    return;
  }

  if (req.method === "GET" && pathname === "/v1/incidents") {
    json(res, 200, { ok: true, incidents: [...incidents.values()].map(publicIncident) });
    return;
  }

  if (req.method === "GET" && pathname === "/v1/incidents/latest") {
    const latest = latestActiveIncident() || [...incidents.values()].sort((a, b) => b.lastObservedAtMs - a.lastObservedAtMs)[0];
    json(res, latest ? 200 : 404, latest ? { ok: true, incident: publicIncident(latest) } : { ok: false, error: "No incidents" });
    return;
  }

  const noteMatch = pathname.match(/^\/v1\/incidents\/([^/]+)\/notes$/);
  if (req.method === "POST" && noteMatch) {
    await handleNote(req, res, decodeURIComponent(noteMatch[1]));
    return;
  }

  const incidentMatch = pathname.match(/^\/v1\/incidents\/([^/]+)$/);
  if (req.method === "GET" && incidentMatch) {
    const incident = incidents.get(decodeURIComponent(incidentMatch[1]));
    json(res, incident ? 200 : 404, incident ? { ok: true, incident: publicIncident(incident) } : { ok: false, error: "Incident not found" });
    return;
  }

  json(res, 404, { ok: false, error: "Not found" });
}

const server = http.createServer((req, res) => {
  route(req, res).catch((error) => {
    console.error(error);
    json(res, 500, { ok: false, error: "Internal server error" });
  });
});

server.listen(PORT, () => {
  console.log(`EchoShield relay listening on http://localhost:${PORT}`);
  if (!API_KEY) {
    console.log("ECHOSHIELD_RELAY_API_KEY is not set; write endpoints are open for local demo.");
  }
});
