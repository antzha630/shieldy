import http from "node:http";
import crypto from "node:crypto";

const PORT = Number.parseInt(process.env.PORT || "8787", 10);
const HOST = process.env.HOST || "0.0.0.0";
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
const GEMINI_API_KEY = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY || "";
const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-1.5-flash";

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
    authorityMessages: [],
    location: null,
    dispatchRecommended: false,
    policeBrief: "No incident data yet.",
    medicalBrief: "No medical notes received yet.",
    recommendedAction: "Monitor for peer confirmation.",
    dispatchNotifiedAt: null,
    liveUpdates: [],
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

  const latitude = numberOrNull(envelope.latitude) ?? numberOrNull(parsed.latitude);
  const longitude = numberOrNull(envelope.longitude) ?? numberOrNull(parsed.longitude);
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

function upsertIncidentReport(report) {
  const observedAtMs = numberOrNull(report.observedAtMs) || Date.now();
  const incident = incidentForReport(report, observedAtMs);
  const messageKey = `report:${report.messageId}:${report.deviceId || "unknown"}`;
  const isDuplicate = seenMessages.has(messageKey);

  if (isDuplicate) {
    return { incident, note: null, duplicate: true };
  }

  seenMessages.add(messageKey);
  addUnique(incident.devices, report.deviceId);
  addUnique(incident.leaders, report.leaderNodeId);

  const userLatitude = numberOrNull(report.latitude);
  const userLongitude = numberOrNull(report.longitude);
  const threatLatitude = numberOrNull(report.threatLatitude);
  const threatLongitude = numberOrNull(report.threatLongitude);

  if (threatLatitude !== null && threatLongitude !== null) {
    incident.location = { latitude: threatLatitude, longitude: threatLongitude };
  } else if (!incident.location && userLatitude !== null && userLongitude !== null) {
    incident.location = { latitude: userLatitude, longitude: userLongitude };
  }

  const note = {
    id: crypto.randomUUID(),
    receivedAt: nowIso(),
    messageId: report.messageId,
    deviceId: report.deviceId || null,
    appState: report.appState || "UNKNOWN",
    safetyStatus: report.safetyStatus || "UNKNOWN",
    injuredCount: Number(report.injuredCount) || 0,
    companionsCount: Number(report.companionsCount) || 0,
    roomNumber: report.roomNumber || "",
    note: report.note || "",
    latitude: userLatitude,
    longitude: userLongitude,
    locationLabel: report.locationLabel || "",
    relativeLocation: report.relativeLocation || "",
    threatLatitude,
    threatLongitude,
    sessionId: report.sessionId || null,
    connectedPeerCount: Number(report.connectedPeerCount) || 0
  };

  incident.notes.push(note);
  incident.observations.push({
    receivedAt: nowIso(),
    messageId: report.messageId,
    alertType: "INCIDENT:REPORT",
    payload: report.note || "",
    deviceId: report.deviceId || null,
    sourceNodeId: report.deviceId || null,
    connectedPeerCount: Number(report.connectedPeerCount) || 0,
    leaderNodeId: report.leaderNodeId || null,
    dutyEpoch: report.dutyEpoch || null
  });

  incident.lastObservedAtMs = Math.max(incident.lastObservedAtMs, observedAtMs);
  incident.updatedAt = nowIso();
  incident.recommendedAction = recommendationFor(incident);
  incident.policeBrief = policeBriefFor(incident);
  incident.medicalBrief = medicalBriefFor(incident);
  incidents.set(incident.id, incident);

  return { incident, note, duplicate: false };
}

function incidentForReport(report, observedAtMs) {
  if (report.sessionId) {
    const id = `session-${report.sessionId}`;
    const incident = incidents.get(id) || createIncident(id, observedAtMs);
    incidents.set(id, incident);
    return incident;
  }

  const latest = latestActiveIncident();
  if (latest) return latest;

  const id = `report-${report.messageId || crypto.randomUUID()}`;
  const incident = createIncident(id, observedAtMs);
  incidents.set(id, incident);
  return incident;
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

function addAuthorityMessage(incident, input) {
  const message = {
    id: crypto.randomUUID(),
    at: nowIso(),
    sender: String(input.sender || "Dispatcher").slice(0, 80),
    role: String(input.role || "authority").slice(0, 40),
    message: String(input.message || "").trim().slice(0, 2000)
  };

  if (!message.message) {
    return null;
  }

  incident.authorityMessages.push(message);
  incident.updatedAt = nowIso();
  return message;
}

function generateHeuristicAuthorityReply(incident, userMessage) {
  const message = String(userMessage || "").toLowerCase();
  const roomHint = incident.notes.at(-1)?.roomNumber || "your current area";
  const injuredCount = incident.notes.reduce((sum, note) => sum + (Number(note.injuredCount) || 0), 0);

  if (message.includes("injured") || message.includes("bleeding") || message.includes("hurt")) {
    return `EMS acknowledged. Keep pressure on wounds if safe, avoid moving critical injuries, and stay in ${roomHint}. Report any change in injured count.`;
  }
  if (message.includes("where") || message.includes("go") || message.includes("route") || message.includes("evacuate")) {
    return `Route guidance update: ${incident.recommendedAction} If movement is unsafe, shelter in ${roomHint} until the next update.`;
  }
  if (message.includes("shooter") || message.includes("gun") || message.includes("shots")) {
    return "Police acknowledges report. Maintain silence, lock doors if possible, and avoid line-of-sight with hallways/windows.";
  }
  if (message.includes("safe") || message.includes("clear")) {
    return "Status received. Continue to hold position and submit updates every 30-60 seconds until all-clear is confirmed.";
  }
  return "Dispatch received your message. Keep sharing location/safety/injury updates; responders are using this feed for live coordination.";
}

function clearIncidentNotifications(incident) {
  incident.authorityMessages = [];
  incident.liveUpdates = [];
  incident.notificationAttempts = [];
  incident.dispatchNotifiedAt = null;
  incident.updatedAt = nowIso();
}

function incidentSnapshotForAgent(incident) {
  const latestNotes = incident.notes.slice(-5).map((note) => ({
    safetyStatus: note.safetyStatus,
    injuredCount: note.injuredCount,
    companionsCount: note.companionsCount,
    roomNumber: note.roomNumber,
    note: note.note
  }));
  const latestObservations = incident.observations.slice(-8).map((obs) => ({
    type: obs.alertType,
    payload: obs.payload,
    peers: obs.connectedPeerCount,
    at: obs.receivedAt
  }));
  const latestChat = incident.authorityMessages.slice(-8).map((message) => ({
    sender: message.sender,
    role: message.role,
    message: message.message,
    at: message.at
  }));

  return {
    incidentId: incident.id,
    status: incident.status,
    recommendedAction: incident.recommendedAction,
    policeBrief: incident.policeBrief,
    medicalBrief: incident.medicalBrief,
    location: incident.location,
    confirmedByNodes: incident.confirmedByNodes,
    notes: latestNotes,
    observations: latestObservations,
    authorityMessages: latestChat
  };
}

function buildAgentContextPrompt(incident) {
  const snapshot = incidentSnapshotForAgent(incident);

  return [
    "You are EchoShield Responder, a concise emergency coordination assistant.",
    "Use incident data below. Prioritize immediate safety and practical next actions.",
    "Never claim law enforcement is physically present unless data says so.",
    "Keep response <= 120 words. Bullet points are okay.",
    "",
    "INCIDENT DATA (JSON):",
    JSON.stringify(snapshot)
  ].join("\n");
}

async function generateAgentReply(incident, userMessage) {
  if (!GEMINI_API_KEY) {
    return generateHeuristicAuthorityReply(incident, userMessage);
  }

  const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(GEMINI_MODEL)}:generateContent?key=${encodeURIComponent(GEMINI_API_KEY)}`;
  const systemPrompt = buildAgentContextPrompt(incident);
  const userPrompt = `USER MESSAGE:\n${String(userMessage || "").trim()}`;

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        contents: [
          { role: "user", parts: [{ text: systemPrompt }] },
          { role: "user", parts: [{ text: userPrompt }] }
        ],
        generationConfig: {
          temperature: 0.25,
          topP: 0.9,
          maxOutputTokens: 220
        }
      })
    });

    if (!response.ok) {
      throw new Error(`Gemini HTTP ${response.status}`);
    }

    const payload = await response.json();
    const text = payload?.candidates?.[0]?.content?.parts
      ?.map((part) => part?.text || "")
      .join("\n")
      .trim();

    if (!text) {
      throw new Error("Gemini returned empty text");
    }
    return text.slice(0, 1800);
  } catch (error) {
    console.warn("[relay] Gemini reply failed, falling back to heuristic:", error.message);
    return generateHeuristicAuthorityReply(incident, userMessage);
  }
}

function generateHeuristicLiveUpdates(incident) {
  const updates = [];
  updates.push(`Status: ${incident.status}.`);
  updates.push(incident.recommendedAction);
  updates.push(incident.policeBrief);
  if (incident.medicalBrief && incident.medicalBrief !== "No medical notes received yet.") {
    updates.push(incident.medicalBrief);
  }
  const latestNote = incident.notes.at(-1)?.note?.trim();
  if (latestNote) {
    updates.push(`Latest field note: ${latestNote}`);
  }
  return updates
    .map((line) => String(line || "").trim())
    .filter(Boolean)
    .slice(0, 6);
}

function parseLiveUpdatesResponse(rawText) {
  if (!rawText) return [];
  const trimmed = rawText.trim();
  try {
    const parsed = JSON.parse(trimmed);
    if (Array.isArray(parsed)) {
      return parsed.map((x) => String(x || "").trim()).filter(Boolean).slice(0, 6);
    }
  } catch {
    // Fall through to line parsing.
  }
  return trimmed
    .split("\n")
    .map((line) => line.replace(/^[-*\d.\s]+/, "").trim())
    .filter(Boolean)
    .slice(0, 6);
}

async function generateAgentLiveUpdates(incident) {
  if (!GEMINI_API_KEY) {
    return generateHeuristicLiveUpdates(incident);
  }

  const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(GEMINI_MODEL)}:generateContent?key=${encodeURIComponent(GEMINI_API_KEY)}`;
  const prompt = [
    "You are generating the EchoShield map live-updates feed.",
    "From the incident JSON below, output ONLY a JSON array of 3-6 short strings.",
    "Each string should be concise, actionable, and prioritized by urgency.",
    "Do not include markdown, labels, bullets, or explanation outside JSON.",
    "",
    "INCIDENT DATA (JSON):",
    JSON.stringify(incidentSnapshotForAgent(incident))
  ].join("\n");

  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [{ text: prompt }] }],
        generationConfig: {
          temperature: 0.2,
          topP: 0.9,
          maxOutputTokens: 220
        }
      })
    });
    if (!response.ok) {
      throw new Error(`Gemini HTTP ${response.status}`);
    }
    const payload = await response.json();
    const rawText = payload?.candidates?.[0]?.content?.parts
      ?.map((part) => part?.text || "")
      .join("\n")
      .trim();
    const parsed = parseLiveUpdatesResponse(rawText);
    if (parsed.length) return parsed;
    throw new Error("Gemini returned no usable live updates");
  } catch (error) {
    console.warn("[relay] Gemini live-updates failed, fallback used:", error.message);
    return generateHeuristicLiveUpdates(incident);
  }
}

async function refreshIncidentLiveUpdates(incident) {
  const updates = await generateAgentLiveUpdates(incident);
  incident.liveUpdates = updates;
  incident.updatedAt = nowIso();
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
    authorityMessageCount: incident.authorityMessages.length,
    liveUpdates: incident.liveUpdates || [],
    observations: incident.observations.slice(-25),
    notes: incident.notes.slice(-25),
    authorityMessages: incident.authorityMessages.slice(-50)
  };
}

function logAlertReceipt(envelope, incident, duplicate) {
  const lat = numberOrNull(envelope.latitude);
  const lon = numberOrNull(envelope.longitude);
  console.log([
    duplicate ? "[relay] duplicate alert" : "[relay] alert",
    `type=${envelope.alertType}`,
    `incident=${incident.id}`,
    `device=${envelope.deviceId || "unknown"}`,
    `peers=${envelope.connectedPeerCount || 0}`,
    lat !== null && lon !== null ? `shot=${lat.toFixed(6)},${lon.toFixed(6)}` : null,
    envelope.sessionId ? `session=${envelope.sessionId}` : null
  ].filter(Boolean).join(" "));
}

function logIncidentReport(report, incident, note, duplicate) {
  const userLat = numberOrNull(report.latitude);
  const userLon = numberOrNull(report.longitude);
  const threatLat = numberOrNull(report.threatLatitude);
  const threatLon = numberOrNull(report.threatLongitude);
  console.log([
    duplicate ? "[relay] duplicate report" : "[relay] report",
    `incident=${incident.id}`,
    `device=${report.deviceId || "unknown"}`,
    `state=${report.appState || "UNKNOWN"}`,
    `safety=${report.safetyStatus || "UNKNOWN"}`,
    `injured=${Number(report.injuredCount) || 0}`,
    `companions=${Number(report.companionsCount) || 0}`,
    report.roomNumber ? `room=${JSON.stringify(report.roomNumber)}` : null,
    userLat !== null && userLon !== null ? `user=${userLat.toFixed(6)},${userLon.toFixed(6)}` : null,
    threatLat !== null && threatLon !== null ? `shot=${threatLat.toFixed(6)},${threatLon.toFixed(6)}` : null,
    report.sessionId ? `session=${report.sessionId}` : null,
    note?.note ? `note=${JSON.stringify(note.note)}` : null
  ].filter(Boolean).join(" "));
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
  await refreshIncidentLiveUpdates(incident);
  logAlertReceipt(envelope, incident, duplicate);
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

async function handleIncidentReport(req, res) {
  if (!authenticate(req)) {
    json(res, 401, { ok: false, error: "Unauthorized" });
    return;
  }

  let report;
  try {
    report = await readJson(req);
  } catch (error) {
    json(res, 400, { ok: false, error: `Invalid JSON: ${error.message}` });
    return;
  }

  if (!report || !report.messageId || !report.deviceId) {
    json(res, 422, { ok: false, error: "Expected incident report with messageId and deviceId." });
    return;
  }

  const { incident, note, duplicate } = upsertIncidentReport(report);
  await refreshIncidentLiveUpdates(incident);
  logIncidentReport(report, incident, note, duplicate);
  json(res, duplicate ? 200 : 202, {
    ok: true,
    duplicate,
    incidentId: incident.id,
    noteId: note?.id || null,
    status: incident.status,
    policeBrief: incident.policeBrief,
    medicalBrief: incident.medicalBrief,
    recommendedAction: incident.recommendedAction
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
  await refreshIncidentLiveUpdates(incident);
  incidents.set(incident.id, incident);

  json(res, 202, {
    ok: true,
    incidentId: incident.id,
    noteId: stored.id,
    policeBrief: incident.policeBrief,
    medicalBrief: incident.medicalBrief
  });
}

async function handleAuthorityMessage(req, res, incidentId) {
  if (!authenticate(req)) {
    json(res, 401, { ok: false, error: "Unauthorized" });
    return;
  }

  const incident = incidents.get(incidentId);
  if (!incident) {
    json(res, 404, { ok: false, error: "Incident not found" });
    return;
  }

  let input;
  try {
    input = await readJson(req);
  } catch (error) {
    json(res, 400, { ok: false, error: `Invalid JSON: ${error.message}` });
    return;
  }

  const message = addAuthorityMessage(incident, {
    sender: input.sender || "Demo Dispatcher",
    role: input.role || "authority",
    message: input.message
  });

  if (!message) {
    json(res, 422, { ok: false, error: "Message is required" });
    return;
  }

  // Demo two-way chat: when app sends a user message, auto-generate a responder-style reply.
  if ((input.role || "").toLowerCase() === "user") {
    const reply = await generateAgentReply(incident, input.message);
    addAuthorityMessage(incident, {
        sender: "EchoShield Responder",
        role: "assistant",
        message: reply
    });
  }

  await refreshIncidentLiveUpdates(incident);
  incidents.set(incident.id, incident);
  json(res, 202, {
    ok: true,
    incidentId: incident.id,
    message,
    authorityMessages: incident.authorityMessages.slice(-50)
  });
}

async function handleIncidentClearNotifications(req, res, incidentId) {
  if (!authenticate(req)) {
    json(res, 401, { ok: false, error: "Unauthorized" });
    return;
  }
  const incident = incidents.get(incidentId);
  if (!incident) {
    json(res, 404, { ok: false, error: "Incident not found" });
    return;
  }
  clearIncidentNotifications(incident);
  incidents.set(incident.id, incident);
  json(res, 200, {
    ok: true,
    incidentId: incident.id,
    authorityMessageCount: incident.authorityMessages.length,
    notificationAttempts: incident.notificationAttempts.length
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
        <form method="post" action="/v1/incidents/${encodeURIComponent(incident.id)}/clear-notifications">
          <button type="submit">Clear Notifications</button>
        </form>
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
    button { border: 0; border-radius: 8px; background: #b42318; color: white; padding: 9px 12px; font-weight: 700; cursor: pointer; margin-top: 8px; }
  </style>
</head>
<body>
  <main>
    <h1>EchoShield Relay Console</h1>
    <p>${incidents.size} incident(s) tracked. Refreshes every 5 seconds. <a href="/dispatch">Open dispatch chat</a></p>
    ${rows || "<p>No incidents yet.</p>"}
  </main>
</body>
</html>`;
}

function dispatchHtml(selectedIncidentId = null) {
  const sortedIncidents = [...incidents.values()].sort((a, b) => b.lastObservedAtMs - a.lastObservedAtMs);
  const selected = selectedIncidentId
    ? incidents.get(selectedIncidentId)
    : latestActiveIncident() || sortedIncidents[0] || null;

  const incidentOptions = sortedIncidents.map((incident) => `
    <a class="${selected?.id === incident.id ? "selected" : ""}" href="/dispatch?incident=${encodeURIComponent(incident.id)}">
      <strong>${escapeHtml(incident.status)}</strong>
      <span>${escapeHtml(incident.id)}</span>
    </a>
  `).join("");

  const chatRows = selected?.authorityMessages.map((message) => `
    <div class="message ${escapeHtml(message.role)}">
      <div class="meta">${escapeHtml(message.sender)} · ${escapeHtml(message.role)} · ${escapeHtml(message.at)}</div>
      <p>${escapeHtml(message.message)}</p>
    </div>
  `).join("") || "";

  const notesRows = selected?.notes.slice(-6).map((note) => `
    <li><strong>${escapeHtml(note.safetyStatus)}</strong> ${escapeHtml(note.roomNumber || "unknown room")} · injured ${note.injuredCount}: ${escapeHtml(note.note || "No note")}</li>
  `).join("") || "";

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>EchoShield Dispatch Console</title>
  <style>
    :root { color-scheme: light; }
    body { margin: 0; font-family: system-ui, sans-serif; background: #eef1f5; color: #121820; }
    header { background: #121820; color: white; padding: 16px 24px; display: flex; justify-content: space-between; align-items: center; }
    header a { color: #9ad0ff; }
    main { display: grid; grid-template-columns: 280px 1fr 360px; min-height: calc(100vh - 64px); }
    nav { border-right: 1px solid #d5dae3; background: white; padding: 16px; }
    nav a { display: block; color: #121820; text-decoration: none; padding: 12px; border-radius: 8px; margin-bottom: 8px; border: 1px solid #e3e6ec; }
    nav a.selected { border-color: #1b73e8; background: #eaf2ff; }
    nav span { display: block; color: #5c6777; font-size: 12px; margin-top: 4px; overflow-wrap: anywhere; }
    section, aside { padding: 18px; }
    .panel { background: white; border: 1px solid #dfe4ec; border-radius: 10px; padding: 16px; margin-bottom: 14px; }
    .chat { height: 54vh; overflow: auto; background: #f8fafc; border-radius: 10px; padding: 12px; border: 1px solid #e3e6ec; }
    .message { background: white; border: 1px solid #dfe4ec; border-radius: 10px; padding: 10px 12px; margin-bottom: 10px; }
    .message.authority { border-left: 5px solid #1b73e8; }
    .message.medical { border-left: 5px solid #1c9b57; }
    .message.system { border-left: 5px solid #7b61ff; }
    .meta { color: #5c6777; font-size: 12px; margin-bottom: 6px; }
    textarea, input { box-sizing: border-box; width: 100%; border: 1px solid #ccd3de; border-radius: 8px; padding: 10px; font: inherit; }
    textarea { min-height: 90px; resize: vertical; }
    button { border: 0; border-radius: 8px; background: #1b73e8; color: white; padding: 11px 14px; font-weight: 700; cursor: pointer; }
    button.secondary { background: #455469; }
    .brief { line-height: 1.45; }
    .empty { color: #5c6777; }
    @media (max-width: 980px) { main { grid-template-columns: 1fr; } nav { border-right: 0; border-bottom: 1px solid #d5dae3; } }
  </style>
</head>
<body>
  <header>
    <strong>EchoShield Simulated Dispatch</strong>
    <a href="/dashboard">Relay dashboard</a>
  </header>
  <main>
    <nav>
      <h2>Incidents</h2>
      ${incidentOptions || '<p class="empty">No incidents yet.</p>'}
    </nav>
    <section>
      ${selected ? `
        <div class="panel">
          <h1>${escapeHtml(selected.status)} <small>${escapeHtml(selected.id)}</small></h1>
          <p class="brief"><strong>Police brief:</strong> ${escapeHtml(selected.policeBrief)}</p>
          <p class="brief"><strong>Medical:</strong> ${escapeHtml(selected.medicalBrief)}</p>
          <p class="brief"><strong>Action:</strong> ${escapeHtml(selected.recommendedAction)}</p>
          <p>
            <button type="button" id="clearNotificationsBtn">Clear Notifications for Incident</button>
          </p>
        </div>
        <div class="chat" id="chat">
          ${chatRows || '<p class="empty">No authority messages yet.</p>'}
        </div>
        <div class="panel">
          <h2>Send Authority Message</h2>
          <form id="messageForm">
            <input id="sender" value="Demo Dispatcher" aria-label="Sender">
            <p>
              <textarea id="message" placeholder="Type simulated police/EMS guidance..."></textarea>
            </p>
            <button type="submit">Send to Incident Log</button>
            <button class="secondary" type="button" data-template="Police units are en route. Maintain shelter guidance until scene is secured.">Police En Route</button>
            <button class="secondary" type="button" data-template="EMS staging nearby. Report injured counts and exact rooms when safe.">EMS Staging</button>
          </form>
        </div>
      ` : '<div class="panel"><h1>No incident selected</h1><p class="empty">Post a demo RESPONSE:TRIGGER packet to create one.</p></div>'}
    </section>
    <aside>
      <div class="panel">
        <h2>Latest User Notes</h2>
        <ul>${notesRows || '<li class="empty">No user notes yet.</li>'}</ul>
      </div>
      <div class="panel">
        <h2>Demo Script</h2>
        <ol>
          <li>Trigger confirmed response from phones.</li>
          <li>Show police/medical brief auto-generated here.</li>
          <li>Type as dispatcher to simulate authority coordination.</li>
          <li>Open mobile route guidance for N/E/S/W movement.</li>
        </ol>
      </div>
    </aside>
  </main>
  <script>
    const incidentId = ${JSON.stringify(selected?.id || null)};
    const chat = document.getElementById("chat");
    if (chat) chat.scrollTop = chat.scrollHeight;

    document.querySelectorAll("[data-template]").forEach((button) => {
      button.addEventListener("click", () => {
        document.getElementById("message").value = button.dataset.template;
      });
    });

    const form = document.getElementById("messageForm");
    if (form && incidentId) {
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const message = document.getElementById("message").value.trim();
        if (!message) return;
        await fetch("/v1/incidents/" + encodeURIComponent(incidentId) + "/authority-messages", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            sender: document.getElementById("sender").value || "Demo Dispatcher",
            role: "authority",
            message
          })
        });
        window.location.reload();
      });
    }

    const clearBtn = document.getElementById("clearNotificationsBtn");
    if (clearBtn && incidentId) {
      clearBtn.addEventListener("click", async () => {
        const ok = window.confirm("Clear all notifications/messages for this incident?");
        if (!ok) return;
        await fetch("/v1/incidents/" + encodeURIComponent(incidentId) + "/clear-notifications", {
          method: "POST"
        });
        window.location.reload();
      });
    }
  </script>
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

  if (req.method === "GET" && pathname === "/dispatch") {
    text(res, 200, dispatchHtml(url.searchParams.get("incident")), "text/html; charset=utf-8");
    return;
  }

  if (req.method === "POST" && pathname === "/v1/mesh/alerts") {
    await handleAlert(req, res);
    return;
  }

  if (req.method === "POST" && pathname === "/v1/incidents/reports") {
    await handleIncidentReport(req, res);
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

  const authorityMessageMatch = pathname.match(/^\/v1\/incidents\/([^/]+)\/authority-messages$/);
  if (req.method === "POST" && authorityMessageMatch) {
    await handleAuthorityMessage(req, res, decodeURIComponent(authorityMessageMatch[1]));
    return;
  }

  const clearNotificationsMatch = pathname.match(/^\/v1\/incidents\/([^/]+)\/clear-notifications$/);
  if (req.method === "POST" && clearNotificationsMatch) {
    await handleIncidentClearNotifications(req, res, decodeURIComponent(clearNotificationsMatch[1]));
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

server.listen(PORT, HOST, () => {
  console.log(`EchoShield relay listening on http://${HOST}:${PORT}`);
  if (!API_KEY) {
    console.log("ECHOSHIELD_RELAY_API_KEY is not set; write endpoints are open for local demo.");
  }
});
