import http from "node:http";
import crypto from "node:crypto";
import { GoogleGenAI } from "@google/genai";

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
const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash";
const GEMINI_MODEL_FALLBACKS = [
  "gemini-2.5-flash",
  "gemini-2.5-flash-lite",
  "gemini-2.0-flash",
  "gemini-2.0-flash-001",
  "gemini-2.0-flash-lite"
];
const GEMINI_DEBUG = /^(1|true|yes|on)$/i.test(process.env.GEMINI_DEBUG || "");
const GEMINI_MODEL_BACKOFF_MS = Number.parseInt(process.env.GEMINI_MODEL_BACKOFF_MS || "60000", 10);

const incidents = new Map();
const seenMessages = new Set();
const geminiBackoffUntilByModel = new Map();
const geminiClient = GEMINI_API_KEY ? new GoogleGenAI({ apiKey: GEMINI_API_KEY }) : null;

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

function inferredConfirmationCountFromText(value) {
  const text = String(value || "");
  const match = text.match(/confirmed\s+by\s+(\d+)\s+(?:device|devices|node|nodes)/i);
  if (!match) return 0;
  const count = Number.parseInt(match[1], 10);
  return Number.isFinite(count) ? count : 0;
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

  const inferredConfirmedCount = Math.max(
    inferredConfirmationCountFromText(envelope.body),
    inferredConfirmationCountFromText(parsed.body),
    inferredConfirmationCountFromText(envelope.payload)
  );
  for (let index = incident.confirmedByNodes.length; index < inferredConfirmedCount; index += 1) {
    addUnique(incident.confirmedByNodes, `inferred-confirmed-${index + 1}`);
  }

  applyStatus(incident, envelope.alertType || parsed.type);
  if (inferredConfirmedCount >= 2 && incident.status !== "CLEARED") {
    incident.status = "CONFIRMED_RESPONSE";
  }
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
    return "Confirmed multi-node threat alert. Use live responder guidance for next steps.";
  }
  if (incident.status === "EVACUATE") {
    return incident.routes[0] ? `Evacuation route active: ${incident.routes[0]}.` : "Evacuation signal received. Await route details.";
  }
  if (incident.status === "CLEARED") {
    return "All-clear signal received from mesh.";
  }
  return "Initial threat detection received. Await corroboration.";
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

// Situation-aware first-aid guidance surfaced to users during an active incident.
// Grounded in "Stop the Bleed" / TECC lay-responder principles: personal safety
// first, then life-threatening bleeding control. Steps are ordered by priority and
// scoped to what an untrained bystander can safely do while sheltering.
function firstAidGuidance(incident) {
  const injured = (incident.notes || []).reduce(
    (sum, note) => sum + (Number(note.injuredCount) || 0),
    0
  );
  const active = incident.status !== "CLEARED";

  const steps = [];
  if (active) {
    steps.push({
      priority: "safety",
      title: "Protect yourself first",
      detail:
        "Do not move toward danger to reach an injured person. Only give aid if you are behind a locked or barricaded door, or the area is confirmed clear."
    });
  }

  // Massive hemorrhage is the leading preventable cause of death — lead with it.
  steps.push({
    priority: "bleeding",
    title: "Stop life-threatening bleeding",
    detail:
      "Press hard directly on the wound with a cloth or clothing and do not let up. For an arm or leg bleed you cannot control, apply a tourniquet 5-8 cm above the wound (not on a joint), tighten until bleeding stops, and note the time."
  });
  steps.push({
    priority: "airway",
    title: "Keep them breathing",
    detail:
      "If unconscious but breathing, roll them onto their side (recovery position). If not breathing and you are trained, begin CPR when it is safe to do so."
  });
  steps.push({
    priority: "shock",
    title: "Prevent shock",
    detail:
      "Keep the person still, lying down, and warm with a jacket or blanket. Reassure them and keep checking that bleeding stays controlled. Do not give food or water."
  });
  if (active) {
    steps.push({
      priority: "report",
      title: "Report while sheltering",
      detail:
        "Silence your phone, stay low and out of line-of-sight, and send your room/landmark and the number of injured so responders can prioritize."
    });
  }

  return {
    headline:
      injured > 0
        ? `${injured} injured reported — control bleeding first.`
        : "If someone is hurt, control life-threatening bleeding first.",
    disclaimer:
      "General guidance only, not a substitute for professional medical care. Call emergency services when safe.",
    steps
  };
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
  const confirmed = incident.confirmedByNodes.length || 0;
  const action = incident.recommendedAction || "Follow current route guidance and avoid threat lines.";

  if (message.includes("injured") || message.includes("bleeding") || message.includes("hurt")) {
    return `EMS acknowledged. Keep pressure on wounds if safe, avoid moving critical injuries, and stay in ${roomHint}. Report any change in injured count.`;
  }
  if (message.includes("where") || message.includes("go") || message.includes("route") || message.includes("evacuate")) {
    return `Route guidance update: ${incident.recommendedAction} If movement is unsafe, shelter in ${roomHint} until the next update.`;
  }
  if (message.includes("shooter") || message.includes("gun") || message.includes("shots")) {
    return "Threat report logged. Shelter in place now: lock or barricade doors, silence your phone, stay low, and avoid line-of-sight with hallways/windows. Send your room or landmark and injury count if safe.";
  }
  if (message.includes("safe") || message.includes("clear")) {
    return "Status received. Continue to hold position and submit updates every 30-60 seconds until all-clear is confirmed.";
  }
  return [
    `Update logged for incident ${incident.id.slice(-6)} (${incident.status}).`,
    `Current guidance: ${action}`,
    `Confirmed reports: ${confirmed}${injuredCount > 0 ? ` · reported injured: ${injuredCount}` : ""}.`,
    `If safe, send your exact room/landmark and movement blockers near ${roomHint}.`
  ].join(" ");
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
    dispatchRecommended: incident.dispatchRecommended,
    dispatchNotifiedAt: incident.dispatchNotifiedAt,
    deviceCount: incident.devices.length,
    confirmedByNodeCount: incident.confirmedByNodes.length,
    location: incident.location,
    zones: incident.zones,
    routes: incident.routes,
    notes: latestNotes,
    observations: latestObservations,
    authorityMessages: latestChat
  };
}

function buildAgentContextPrompt(incident) {
  const snapshot = incidentSnapshotForAgent(incident);

  return [
    "You are EchoShield Responder, a calm emergency coordination assistant.",
    "Use ONLY the incident data below. Do not invent shooter location, injuries, dispatch status, or official response.",
    "Do not use markdown, bold text, headings, all-caps warnings, emojis, or dramatic labels like URGENT.",
    "Do not say \"shooter nearby\"; say confirmed threat alert or reported threat location instead.",
    "When the data only comes from gunshot detection, say confirmed threat alert or reported shot origin, not confirmed shooter.",
    "Do not say police, law enforcement, EMS, or dispatchers have been dispatched unless dispatch notification data confirms it.",
    "If dispatch is only recommended, say dispatch recommended.",
    "Respond in your own words; do not parrot field names or canned recommendation text.",
    "Prioritize immediate safety, practical next actions, and one focused follow-up question.",
    "If user asks for unknown info, say what is missing and ask one focused follow-up question.",
    "Never claim law enforcement is physically present unless data says so.",
    "Keep response as 2-4 short plain-text sentences, <= 90 words.",
    "",
    "INCIDENT DATA (JSON):",
    JSON.stringify(snapshot)
  ].join("\n");
}

function isPromptEchoResponse(text) {
  const value = String(text || "").toLowerCase();
  if (!value) return true;
  return (
    value.includes("incident data (json)") ||
    value.includes("use only provided json data") ||
    value.includes("echoshield responder (concise emergency coordination assistant)") ||
    value.includes("* incidentid:") ||
    value.includes("constraint check")
  );
}

function sanitizeEchoedAgentText(rawText) {
  const stripped = String(rawText || "")
    // Strip code fences entirely.
    .replace(/```[a-zA-Z]*\n?/g, "")
    .replace(/```/g, "")
    // Strip leading filler preambles like "Here is the answer:", "Here is the JSON requested:",
    // "Here's a concise summary:", "Sure, here is...", "Okay, here's...".
    .replace(/^\s*(here(?:'s| is)|sure[,!]?\s*here(?:'s| is)|okay[,!]?\s*here(?:'s| is))[^\n]{0,80}:?\s*\n?/i, "")
    .trim();

  const lines = stripped
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  const filtered = lines.filter((line) => {
    const lower = line.toLowerCase();
    return !(
      lower.includes("incident data (json)") ||
      lower.includes("use only provided json data") ||
      lower.includes("echoshield responder (concise emergency coordination assistant)") ||
      lower.includes("constraint check") ||
      lower.startsWith("* incidentid:") ||
      lower.startsWith("* status:") ||
      lower.startsWith("* recommendedaction:") ||
      lower.startsWith("* policebrief:") ||
      lower.startsWith("* medicalbrief:") ||
      lower.startsWith("* location:") ||
      lower.startsWith("* observations")
    );
  });

  return filtered.join("\n").trim();
}

const AGENT_REPLY_RESPONSE_SCHEMA = {
  type: "object",
  properties: {
    message: {
      type: "string",
      description: "A calm plain-text emergency response. No markdown, headings, bullets, labels, or invented facts."
    }
  },
  required: ["message"]
};

function extractJsonObjectText(rawText) {
  const text = String(rawText || "").trim();
  if (!text) return null;
  if (text.startsWith("{") && text.endsWith("}")) return text;

  const fenced = text.match(/```(?:json)?\s*(\{[\s\S]*?\})\s*```/i);
  if (fenced) return fenced[1];

  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start >= 0 && end > start) {
    return text.slice(start, end + 1);
  }

  return null;
}

function stripResponderMarkdown(rawText) {
  return String(rawText || "")
    .replace(/```[\s\S]*?```/g, "")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/__([^_]+)__/g, "$1")
    .replace(/^[#>\s]+/gm, "")
    .replace(/^[-*\d.)\s]+/gm, "")
    .replace(/\s+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function parseAgentReplyResponse(rawText) {
  const jsonObjectText = extractJsonObjectText(rawText);
  if (jsonObjectText) {
    try {
      const parsed = JSON.parse(jsonObjectText);
      if (parsed && typeof parsed.message === "string") {
        return stripResponderMarkdown(parsed.message);
      }
    } catch {
      // Fall through to plain-text cleanup.
    }
  }

  return stripResponderMarkdown(sanitizeEchoedAgentText(rawText));
}

function agentReplyLooksBad(reply, incident, userMessage) {
  const text = String(reply || "").trim();
  const lower = text.toLowerCase();
  if (text.length < 40) return true;
  if (/[,:;]$/.test(text)) return true;
  if (/^(here is|here's)\b/i.test(text)) return true;
  if (isPromptEchoResponse(text)) return true;
  if (/\burgent\b/.test(lower)) return true;
  if (/\b(shooter nearby|shooter is nearby|nearby shooter)\b/.test(lower)) return true;
  if (/\b(police|law enforcement)\b.{0,24}\b(on scene|arrived|here)\b/.test(lower)) {
    return true;
  }
  if (!incident.dispatchNotifiedAt &&
      /\b(police|law enforcement|ems|dispatchers?)\b.{0,40}\b(have|has|were|was|are|is)\s+been\s+dispatched\b/.test(lower)) {
    return true;
  }
  return false;
}

function forceAssistantAnswerFallback(rawText, incident, userMessage) {
  const cleaned = parseAgentReplyResponse(rawText);
  if (!cleaned) return "";
  const lines = cleaned
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !/^(analysis|constraint|incident data|input|goal|json)\b/i.test(line))
    .slice(0, 6);
  const salvaged = lines.join("\n").trim().slice(0, 1800);
  if (!salvaged || agentReplyLooksBad(salvaged, incident, userMessage)) return "";
  return salvaged;
}

function orderedModelCandidates() {
  const seen = new Set();
  const ordered = [];
  [GEMINI_MODEL, ...GEMINI_MODEL_FALLBACKS].forEach((model) => {
    const value = String(model || "").trim();
    if (!value || seen.has(value)) return;
    seen.add(value);
    ordered.push(value);
  });
  return ordered;
}

async function generateWithModelFallback(requestBodyBuilder) {
  if (!geminiClient) {
    throw new Error("Gemini SDK client unavailable: missing GEMINI_API_KEY/GOOGLE_API_KEY");
  }

  const candidates = orderedModelCandidates();
  let lastError = null;

  for (const model of candidates) {
    const backoffUntil = geminiBackoffUntilByModel.get(model) || 0;
    if (backoffUntil > Date.now()) {
      const err = new Error(`Gemini model ${model} is rate-limit backed off for ${Math.ceil((backoffUntil - Date.now()) / 1000)}s`);
      err.status = 429;
      err.model = model;
      lastError = err;
      continue;
    }

    try {
      const requestBody = requestBodyBuilder();
      logGeminiRequest(model, requestBody);
      const payload = await geminiClient.models.generateContent({
        model,
        ...requestBody
      });
      logGeminiResponse(model, payload);
      return { model, payload };
    } catch (error) {
      const status = geminiErrorStatus(error);
      error.status = status || error.status;
      error.model = model;
      error.responsePreview = String(error?.message || "").slice(0, 240);
      lastError = error;
      if (status === 404 || String(error?.message || "").includes("404")) {
        // Model name unavailable for this API key/project; try next candidate.
        console.warn(`[relay] model unavailable, trying fallback: ${model}`);
        continue;
      }
      if (status === 429 || String(error?.message || "").includes("429")) {
        geminiBackoffUntilByModel.set(model, Date.now() + GEMINI_MODEL_BACKOFF_MS);
        console.warn(`[relay] model rate-limited, trying fallback: ${model}`);
        continue;
      }
      throw error;
    }
  }

  throw lastError || new Error("No usable Gemini/Gemma model available");
}

function geminiErrorStatus(error) {
  const direct = Number(error?.status || error?.code || error?.statusCode);
  if (Number.isFinite(direct) && direct > 0) return direct;
  const match = String(error?.message || "").match(/\b(4\d\d|5\d\d)\b/);
  return match ? Number.parseInt(match[1], 10) : 0;
}

function previewText(value, limit = 700) {
  return String(value || "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, limit);
}

function firstContentText(payload) {
  if (typeof payload?.text === "string") {
    return payload.text.trim();
  }
  return payload?.candidates?.[0]?.content?.parts
    ?.map((part) => part?.text || "")
    .join("\n")
    .trim() || "";
}

function firstFinishReason(payload) {
  return payload?.candidates?.[0]?.finishReason || "";
}

function contentUnionText(value) {
  if (!value) return "";
  if (typeof value === "string") return value;
  if (Array.isArray(value)) {
    return value.map(contentUnionText).filter(Boolean).join("\n");
  }
  if (Array.isArray(value.parts)) {
    return value.parts.map((part) => part?.text || "").filter(Boolean).join("\n");
  }
  return "";
}

function logGeminiRequest(model, body) {
  const config = body?.config || {};
  const systemText = contentUnionText(config.systemInstruction);
  const userText = contentUnionText(body?.contents);
  console.info([
    "[relay] Gemini request",
    `model=${model}`,
    `mime=${config.responseMimeType || "text/plain"}`,
    `schema=${config.responseJsonSchema ? "json_schema" : config.responseSchema ? "legacy_schema" : "none"}`,
    `system=${systemText ? "yes" : "no"}`,
    `promptChars=${userText.length}`
  ].join(" "));

  if (GEMINI_DEBUG) {
    console.info(`[relay] Gemini request prompt-preview model=${model}: ${previewText(userText)}`);
  }
}

function logGeminiResponse(model, payload) {
  const candidate = payload?.candidates?.[0] || {};
  const rawText = firstContentText(payload);
  console.info([
    "[relay] Gemini response",
    `model=${model}`,
    `finish=${candidate.finishReason || "unknown"}`,
    `parts=${candidate.content?.parts?.length || 0}`,
    `textChars=${rawText.length}`
  ].join(" "));

  if (GEMINI_DEBUG && rawText) {
    console.info(`[relay] Gemini response raw-preview model=${model}: ${previewText(rawText)}`);
  }
}

function classifyGeminiFailure(error, rawText = "", stage = "unknown") {
  const message = String(error?.message || "");
  const text = String(rawText || "");
  const status = Number(error?.status || 0);

  if (!GEMINI_API_KEY) return "missing_api_key";
  if (status === 401 || /401/.test(message)) return "auth_unauthorized";
  if (status === 403 || /403/.test(message)) return "auth_forbidden_or_quota";
  if (status === 404 || /404/.test(message)) return "model_not_found";
  if (status === 429 || /429/.test(message)) return "rate_limited";
  if (status >= 500 && status <= 599) return "gemini_server_error";
  if (/timed?\s*out|abort|network|fetch failed|ecconn|enotfound/i.test(message)) return "network_or_timeout";
  if (/echoed prompt/i.test(message) || isPromptEchoResponse(text) || isPromptLikeLiveUpdate(text)) return "prompt_echo";
  if (/empty/i.test(message) || !text.trim()) return "empty_response";
  if (/json/i.test(message) || /No valid JSON array/i.test(message)) return "json_parse_or_shape";
  return `unknown_${stage}`;
}

function logGeminiFailure(kind, incidentId, error, extra = {}) {
  const failure = classifyGeminiFailure(error, extra.rawText, extra.stage);
  const model = error?.model || extra.model || "unknown";
  const status = error?.status || "";
  const rawPreview = String(extra.rawText || error?.responsePreview || "").slice(0, 180);
  const reason = String(error?.message || "unknown");
  console.warn(
    `[relay] ${kind} failed incident=${incidentId || "unknown"} category=${failure} stage=${extra.stage || "unknown"} model=${model} status=${status} reason=${reason}`
  );
  if (rawPreview) {
    console.warn(`[relay] ${kind} raw-preview incident=${incidentId || "unknown"}: ${rawPreview}`);
  }
}

async function generateAgentReply(incident, userMessage) {
  if (!GEMINI_API_KEY) {
    return generateHeuristicAuthorityReply(incident, userMessage);
  }

  const systemPrompt = buildAgentContextPrompt(incident);
  const userPrompt = String(userMessage || "").trim();

  let lastRawText = "";
  try {
    const buildRequest = () => ({
        contents: [{ role: "user", parts: [{ text: userPrompt }] }],
        config: {
          systemInstruction: systemPrompt,
          temperature: 0.35,
          topP: 0.9,
          maxOutputTokens: 640,
          responseMimeType: "application/json",
          responseJsonSchema: AGENT_REPLY_RESPONSE_SCHEMA
        }
      });

    const { model, payload } = await generateWithModelFallback(buildRequest);
    const text = payload?.candidates?.[0]?.content?.parts
      ?.map((part) => part?.text || "")
      .join("\n")
      .trim();
    lastRawText = text;

    if (!text) {
      throw new Error("Gemini returned empty text");
    }

    const finishReason = firstFinishReason(payload);
    const cleanedText = parseAgentReplyResponse(text);
    if (finishReason !== "MAX_TOKENS" && cleanedText && !agentReplyLooksBad(cleanedText, incident, userPrompt)) {
      console.info(`[relay] Agent reply model=${model}`);
      return cleanedText.slice(0, 1800);
    }

    // One retry with stricter anti-echo instruction before heuristic fallback.
    const summary = incidentSnapshotForAgent(incident);
    const retryPrompt = [
      "Return only JSON with a message field.",
      "The message must be plain text: no markdown, no bold, no bullets, no URGENT label.",
      "Use only the incident facts. Do not invent shooter proximity or official response.",
      "Do not say police/EMS have been dispatched unless the summary explicitly says dispatch notification was sent.",
      "Give 2-4 short sentences for immediate safety and one missing-info question.",
      "",
      "INCIDENT FACTS:",
      JSON.stringify(summary),
      "",
      `USER MESSAGE: ${userPrompt}`
    ].join("\n");

    const retryResult = await generateWithModelFallback(() => ({
      contents: [{ role: "user", parts: [{ text: retryPrompt }] }],
      config: {
        systemInstruction: "You are a calm emergency assistant. Output only the requested JSON object.",
        temperature: 0.2,
        topP: 0.8,
        maxOutputTokens: 640,
        responseMimeType: "application/json",
        responseJsonSchema: AGENT_REPLY_RESPONSE_SCHEMA
      }
    }));

    const retryText = retryResult?.payload?.candidates?.[0]?.content?.parts
      ?.map((part) => part?.text || "")
      .join("\n")
      .trim();
    lastRawText = retryText || lastRawText;
    const retryFinishReason = firstFinishReason(retryResult.payload);
    const retryCleaned = parseAgentReplyResponse(retryText);
    if (retryFinishReason !== "MAX_TOKENS" && retryCleaned && !agentReplyLooksBad(retryCleaned, incident, userPrompt)) {
      console.info(`[relay] Agent reply model=${retryResult.model} retry=1`);
      return retryCleaned.slice(0, 1800);
    }

    // Last-ditch salvage: keep whatever user-facing lines remain after sanitization.
    const salvaged = retryFinishReason === "MAX_TOKENS"
      ? ""
      : forceAssistantAnswerFallback(retryText || text, incident, userPrompt);
    if (salvaged) {
      console.info(`[relay] Agent reply model=${retryResult.model} retry=1 salvaged=1`);
      return salvaged;
    }

    throw new Error("Model reply failed quality checks");
  } catch (error) {
    logGeminiFailure("Gemini reply", incident.id, error, { stage: "chat", rawText: lastRawText });
    console.warn("[relay] Gemini reply fallback=heuristic");
    return generateHeuristicAuthorityReply(incident, userMessage);
  }
}

function generateHeuristicLiveUpdates(incident) {
  const updates = [];
  const seen = new Set();
  const push = (value) => {
    const text = String(value || "").trim();
    if (!text) return;
    const key = text.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    updates.push(text);
  };

  if (incident.status === "CONFIRMED_RESPONSE") {
    const confirmed = incident.confirmedByNodes.length || incident.devices.length || 1;
    push(`Threat alert corroborated by ${confirmed} node${confirmed === 1 ? "" : "s"}.`);
  } else if (incident.status === "DETECTED") {
    push("Initial threat detection received; waiting for corroboration.");
  } else if (incident.status === "EVACUATE") {
    push(incident.routes[0] ? `Evacuation route reported: ${incident.routes[0]}.` : "Evacuation signal received; route details pending.");
  } else if (incident.status === "CLEARED") {
    push("All-clear signal received from the mesh.");
  }
  if (incident.location) {
    push(`Reported shot origin: ${incident.location.latitude.toFixed(5)}, ${incident.location.longitude.toFixed(5)}.`);
  }
  if (incident.zones.length) {
    push(`Threat zone: ${incident.zones.slice(0, 2).join(", ")}.`);
  }

  // Rank user crowd reports by likely urgency/actionability.
  const rankedUserReports = incident.authorityMessages
    .filter((m) => String(m.role || "").toLowerCase() === "user")
    .slice(-24)
    .map((m) => ({ message: String(m.message || "").trim(), at: m.at || "" }))
    .filter((m) => m.message.length > 0)
    .map((m) => ({ ...m, score: scoreLiveUpdateReport(m.message) }))
    .filter((m) => m.score > 0)
    .sort((a, b) => b.score - a.score);

  rankedUserReports.slice(0, 3).forEach((entry) => {
    push(`Field report: ${entry.message}`);
  });

  // Include latest structured status note if present.
  const latestNote = incident.notes.at(-1)?.note?.trim();
  if (latestNote) {
    push(`Status note: ${latestNote}`);
  }

  // Keep responder briefs lower in feed than direct reports.
  if (incident.medicalBrief && incident.medicalBrief !== "No medical notes received yet.") {
    push(incident.medicalBrief);
  }
  if (incident.status === "CONFIRMED_RESPONSE") {
    push("Shelter in place, stay low, silence your phone, and keep sending concrete updates if safe.");
  }

  return sanitizeLiveUpdates(updates, incident);
}

function scoreLiveUpdateReport(message) {
  const text = String(message || "").toLowerCase();
  if (!text) return 0;
  let score = 0;
  if (/\binjured|bleeding|wounded|shot|hurt\b/.test(text)) score += 5;
  if (/\bshooter|gun|shots|firearm|weapon\b/.test(text)) score += 5;
  if (/\bwhere|location|near|hall|stair|exit|room|floor|building\b/.test(text)) score += 4;
  if (/\btrapped|blocked|smoke|fire|crowd|panic|cannot\b/.test(text)) score += 3;
  if (/\bevacuate|run|hide|barricade|safe|unsafe\b/.test(text)) score += 2;
  if (text.length > 30) score += 1;
  return score;
}

function isPromptLikeLiveUpdate(value) {
  const text = String(value || "").trim().toLowerCase();
  if (!text) return true;

  return [
    "incident data",
    "incident json",
    "valid json",
    "json array",
    "output only",
    "short strings",
    "constraints:",
    "input:",
    "goal:",
    "concise, actionable",
    "urgency/confidence",
    "generate a live-updates feed",
    "echoshield map live-updates feed",
    "prioritized by urgency",
    "do not include markdown",
    "do not include",
    "here is the json",
    "json requested",
    "requested:",
    "```",
    "use only",
    "system_instruction",
    "generationconfig"
  ].some((marker) => text.includes(marker));
}

function liveUpdateLooksBad(text, incident = null) {
  const lower = String(text || "").toLowerCase();
  if (!incident?.dispatchNotifiedAt &&
      /\b(police|law enforcement|ems|dispatchers?)\b.{0,40}\b(dispatched|sent|notified|en route|responding)\b/.test(lower)) {
    return true;
  }
  const incidentEvidence = [
    ...listValue(incident?.routes),
    ...listValue(incident?.zones),
    ...(incident?.notes || []).map((note) => note.note),
    ...(incident?.authorityMessages || []).map((message) => message.message),
    ...(incident?.observations || []).map((obs) => obs.payload)
  ].join(" ").toLowerCase();
  if (/\b(exit|exits|route|routes)\b/.test(lower) &&
      /\b(blocked|compromised|closed|unsafe|avoid)\b/.test(lower) &&
      !/\b(exit|exits|route|routes|blocked|compromised|closed|unsafe)\b/.test(incidentEvidence)) {
    return true;
  }
  return false;
}

function sanitizeLiveUpdates(values, incident = null) {
  const updates = [];
  const seen = new Set();

  listValue(values).forEach((value) => {
    const text = String(value || "")
      .replace(/^["'\s]+|["'\s]+$/g, "")
      .replace(/^[-*\d.)\s]+/, "")
      .trim();
    if (!text || isPromptLikeLiveUpdate(text) || liveUpdateLooksBad(text, incident)) return;

    const key = text.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    updates.push(text.slice(0, 180));
  });

  return updates.slice(0, 6);
}

function extractJsonArrayText(rawText) {
  const text = String(rawText || "").trim();
  if (!text) return null;
  if (text.startsWith("[") && text.endsWith("]")) return text;

  const fenced = text.match(/```(?:json)?\s*(\[[\s\S]*?\])\s*```/i);
  if (fenced) return fenced[1];

  const start = text.indexOf("[");
  const end = text.lastIndexOf("]");
  if (start >= 0 && end > start) {
    return text.slice(start, end + 1);
  }

  return null;
}

function parseLiveUpdatesResponse(rawText) {
  const jsonArrayText = extractJsonArrayText(rawText);
  if (!jsonArrayText) {
    return parseLiveUpdatesFromPlainText(rawText);
  }

  try {
    const parsed = JSON.parse(jsonArrayText);
    if (Array.isArray(parsed)) {
      return sanitizeLiveUpdates(parsed);
    }
  } catch {
    // Recover partial/truncated JSON arrays by extracting quoted phrases.
    return parseLiveUpdatesFromPartialJson(rawText);
  }

  return parseLiveUpdatesFromPlainText(rawText);
}

function parseLiveUpdatesFromPartialJson(rawText) {
  const quoted = [];
  const text = String(rawText || "");
  const regex = /"([^"\n]{4,180})"/g;
  let match;
  while ((match = regex.exec(text)) !== null) {
    quoted.push(match[1]);
    if (quoted.length >= 8) break;
  }
  return sanitizeLiveUpdates(quoted);
}

function parseLiveUpdatesFromPlainText(rawText) {
  const lines = String(rawText || "")
    .split("\n")
    .map((line) => line.trim())
    .map((line) => line.replace(/^[-*\d.)\s]+/, "").trim())
    .filter(Boolean);
  return sanitizeLiveUpdates(lines);
}

function usableLiveUpdates(values, incident = null) {
  const updates = sanitizeLiveUpdates(values, incident);
  return updates.length >= 3 ? updates : [];
}

function authorityMessageLiveUpdates(incident, limit = 3) {
  return (incident.authorityMessages || [])
    .filter((message) => String(message.role || "").toLowerCase() === "user")
    .slice(-limit)
    .reverse()
    .map((message) => {
      const sender = String(message.sender || "User").trim() || "User";
      const text = String(message.message || "").trim();
      return text ? `Field report from ${sender}: ${text}` : "";
    })
    .filter(Boolean);
}

const LIVE_UPDATES_RESPONSE_SCHEMA = {
  type: "array",
  items: {
    type: "string",
    description: "A concise, user-facing emergency update. No bullets, markdown, JSON labels, or prompt text."
  }
};

async function generateAgentLiveUpdates(incident) {
  if (!GEMINI_API_KEY) {
    return generateHeuristicLiveUpdates(incident);
  }

  const snapshot = incidentSnapshotForAgent(incident);
  const prompt = [
    "Write a live incident feed as a JSON array of 3-6 short strings.",
    "Use natural, varied wording. Do not copy field names or canned status text.",
    "Prioritize concrete reports: injuries, reported threat direction/location, blocked exits, rooms, and confidence.",
    "Do not invent blocked exits, compromised exits, evacuation routes, or official responder movement.",
    "If the facts are sparse, say what is known and what is still unconfirmed.",
    "",
    "Incident facts:",
    JSON.stringify(snapshot)
  ].filter(Boolean).join("\n");

  const askModel = (userPrompt, maxOutputTokens = 640) => generateWithModelFallback(() => ({
        contents: [{ role: "user", parts: [{ text: userPrompt }] }],
        config: {
          systemInstruction: "Return only the requested JSON array of strings. Be concise, factual, and naturally worded. Do not invent official responder movement or blocked exits.",
          temperature: 0.35,
          topP: 0.9,
          maxOutputTokens,
          responseMimeType: "application/json",
          responseJsonSchema: LIVE_UPDATES_RESPONSE_SCHEMA
        }
      }));

  let lastRawText = "";
  try {
    const { model, payload } = await askModel(prompt, 640);
    const rawText = payload?.candidates?.[0]?.content?.parts
      ?.map((part) => part?.text || "")
      .join("\n")
      .trim();
    lastRawText = rawText;

    // Try to extract JSON array from response
    const finishReason = firstFinishReason(payload);
    const parsed = finishReason === "MAX_TOKENS" ? [] : usableLiveUpdates(parseLiveUpdatesResponse(rawText), incident);
    if (parsed.length) {
      console.info(`[relay] Live updates model=${model} count=${parsed.length}`);
      return parsed;
    }

    // One retry with a tighter prompt to avoid partial/echo output.
    const retryPrompt = [
      "Return EXACTLY 4 JSON strings.",
      "No intro text. No markdown. Only JSON array output.",
      "Use only concrete facts from incident data.",
      "If uncertain, include one brief uncertainty line.",
      "",
      "Incident JSON:",
      JSON.stringify(snapshot)
    ].join("\n");

    const retry = await askModel(retryPrompt, 640);
    const retryRawText = retry?.payload?.candidates?.[0]?.content?.parts
      ?.map((part) => part?.text || "")
      .join("\n")
      .trim();
    lastRawText = retryRawText || lastRawText;
    const retryFinishReason = firstFinishReason(retry.payload);
    const retryParsed = retryFinishReason === "MAX_TOKENS" ? [] : usableLiveUpdates(parseLiveUpdatesResponse(retryRawText), incident);
    if (retryParsed.length) {
      console.info(`[relay] Live updates model=${retry.model} retry=1 count=${retryParsed.length}`);
      return retryParsed;
    }

    // Last-ditch salvage from partial JSON fragments.
    const salvaged = retryFinishReason === "MAX_TOKENS"
      ? []
      : usableLiveUpdates(parseLiveUpdatesFromPartialJson(retryRawText || rawText), incident);
    if (salvaged.length) {
      console.info(`[relay] Live updates model=${retry.model} retry=1 salvaged=${salvaged.length}`);
      return salvaged;
    }

    if (isPromptEchoResponse(rawText) || isPromptLikeLiveUpdate(rawText) ||
        isPromptEchoResponse(retryRawText) || isPromptLikeLiveUpdate(retryRawText)) {
      throw new Error("Model echoed prompt instead of JSON array");
    }

    throw new Error(
      `No valid JSON array in response: ${String((retryRawText || rawText || "")).slice(0, 120)}`
    );
  } catch (error) {
    logGeminiFailure("Gemini live-updates", incident.id, error, {
      stage: "live_updates",
      rawText: lastRawText
    });
    console.warn("[relay] Gemini live-updates fallback=heuristic");
    return generateHeuristicLiveUpdates(incident);
  }
}

async function refreshIncidentLiveUpdates(incident) {
  const generatedUpdates = await generateAgentLiveUpdates(incident);
  const userMessageUpdates = authorityMessageLiveUpdates(incident, 3);
  incident.liveUpdates = sanitizeLiveUpdates([...userMessageUpdates, ...generatedUpdates], incident);
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
  const storedLiveUpdates = sanitizeLiveUpdates(incident.liveUpdates || [], incident);
  const effectiveLiveUpdates = storedLiveUpdates.length
    ? storedLiveUpdates
    : generateHeuristicLiveUpdates(incident);
  return {
    ...incident,
    observationCount: incident.observations.length,
    authorityMessageCount: incident.authorityMessages.length,
    firstAid: firstAidGuidance(incident),
    liveUpdates: effectiveLiveUpdates,
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
  if (!authenticateUserWrite(req)) {
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
  if (!authenticateUserWrite(req)) {
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
    liveUpdates: incident.liveUpdates,
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
        <button onclick="fetch('/v1/incidents/${encodeURIComponent(incident.id)}/clear-notifications', {method: 'POST', headers: {'authorization': 'Bearer ${API_KEY}'}}).then(() => window.location.reload())">Clear Notifications</button>
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

  const chatRows = selected?.authorityMessages.slice().reverse().map((message) => `
    <div class="message ${escapeHtml(message.role)}">
      <div class="meta">${escapeHtml(message.sender)} · ${escapeHtml(message.role)} · ${escapeHtml(message.at)}</div>
      <p>${escapeHtml(message.message)}</p>
    </div>
  `).join("") || "";

  const firstAid = selected ? firstAidGuidance(selected) : null;
  const firstAidRows = firstAid
    ? `<p class="brief"><strong>First aid:</strong> ${escapeHtml(firstAid.headline)}</p>
       <ol class="firstaid">
         ${firstAid.steps
           .map(
             (step) =>
               `<li><strong>${escapeHtml(step.title)}:</strong> ${escapeHtml(step.detail)}</li>`
           )
           .join("")}
       </ol>
       <p class="disclaimer">${escapeHtml(firstAid.disclaimer)}</p>`
    : "";

  const liveUpdateRows = (selected?.liveUpdates || [])
    .slice()
    .reverse()
    .map((update) => `<li>${escapeHtml(update)}</li>`)
    .join("");

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
          ${firstAidRows}
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
        <h2>Live Updates</h2>
        <ul>${liveUpdateRows || '<li class="empty">No live updates yet.</li>'}</ul>
      </div>
    </aside>
  </main>
  <script>
    const incidentId = ${JSON.stringify(selected?.id || null)};
    let lastKnownUpdatedAt = ${JSON.stringify(selected?.updatedAt || "")};

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
          headers: { 
            "content-type": "application/json",
            "authorization": "Bearer ${API_KEY}"
          },
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
          method: "POST",
          headers: { "authorization": "Bearer ${API_KEY}" }
        });
        window.location.reload();
      });
    }

    // Auto-refresh dispatch view when new server-side data arrives for this incident.
    if (incidentId) {
      window.setInterval(async () => {
        try {
          const response = await fetch("/v1/incidents/" + encodeURIComponent(incidentId), {
            headers: { "cache-control": "no-store" }
          });
          if (!response.ok) return;
          const payload = await response.json();
          const updatedAt = payload?.incident?.updatedAt || "";
          if (updatedAt && lastKnownUpdatedAt && updatedAt !== lastKnownUpdatedAt) {
            window.location.reload();
            return;
          }
          if (updatedAt) {
            lastKnownUpdatedAt = updatedAt;
          }
        } catch (_) {
          // Keep UI running even if one poll fails.
        }
      }, 2000);
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

// ─────────────────────────────────────────────────────────────────────────────
// Cross-platform mesh bus.
//
// Google Nearby Connections is Android-only, so iOS and Android cannot form a
// peer-to-peer mesh directly. This server-mediated bus is the shared transport both
// platforms speak over HTTP + Server-Sent Events: devices subscribe to a stream,
// raise candidate detections, and vote on each other's detections. The server runs
// the same anti-herding consensus as the Android mesh and triangulates the source,
// then broadcasts a response trigger to every connected device regardless of OS.
// ─────────────────────────────────────────────────────────────────────────────

const MESH_QUORUM_FRACTION = 0.34;          // fraction of the fleet that must confirm
const MESH_MAX_REQUIRED_CONFIRMATIONS = 5;  // cap so large fleets need not be unanimous
const MESH_FLOOR_HEIGHT_METERS = 3.5;       // nominal storey height for floor offset
const MESH_VOTE_WINDOW_MS = 6000;           // how long a detection session accepts votes
const SPEED_OF_SOUND_MPS = 343;             // for TDoA travel-time computation
// Arrivals spread wider than this cannot be one indoor acoustic event (sound crosses
// a large building in well under a second), so the clocks are not comparable.
const MESH_MAX_ARRIVAL_SPREAD_MS = 1500;
// 0.343 m per ms: 60 ms of clock error is already ~20 m of ranging error.
const MESH_MAX_TIMING_UNCERTAINTY_MS = 60;
// Below this, the confirming phones give the solve no geometric leverage.
const MESH_MIN_BASELINE_METERS = 4;

// deviceId -> { res, lastSeen, latitude, longitude, platform }
const meshDevices = new Map();
// sessionId -> { sessionId, sourceDeviceId, startedAtMs, votes: Map<deviceId, vote> }
const detectionSessions = new Map();

/**
 * Authorize a write on the mesh bus.
 *
 * Two callers, two credentials. A trusted node (the Android relay) presents the
 * shared relay API key. A browser sensor node cannot hold that key — it is a public
 * page — so at stream-connect time it is issued a per-device bus token, which it
 * must present alongside its own deviceId. That binds mesh writes to a device with
 * a live stream rather than accepting anonymous detections from anywhere.
 */
function authenticateUserWrite(req) {
  if (authenticate(req)) return true;
  const authorization = req.headers.authorization || "";
  if (!authorization.startsWith("Bus ")) return false;
  const token = authorization.slice(4);
  if (!token) return false;
  for (const entry of meshDevices.values()) {
    if (entry.busToken === token) return true;
  }
  return false;
}

function authenticateMeshWrite(req, deviceId) {
  if (authenticate(req)) return true;
  if (!deviceId) return false;
  const entry = meshDevices.get(deviceId);
  if (!entry || !entry.busToken) return false;
  const authorization = req.headers.authorization || "";
  return authorization === `Bus ${entry.busToken}`;
}

function haversineMeters(lat1, lon1, lat2, lon2) {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * How many independent confirmations are needed before the fleet responds.
 *
 * Only devices that are actually listening count toward the quorum: a viewer with
 * the page open but no microphone can never confirm, so including it would raise
 * the bar for everyone and stall real detections.
 */
function meshSensorCount() {
  let count = 0;
  for (const entry of meshDevices.values()) if (entry.sensor) count++;
  return count;
}

function meshRequiredConfirmations() {
  const fleet = meshSensorCount();
  if (fleet <= 1) return 1; // solo sensor: local trigger only
  const scaled = Math.ceil(fleet * MESH_QUORUM_FRACTION);
  return Math.min(MESH_MAX_REQUIRED_CONFIRMATIONS, Math.max(2, scaled));
}

function meshBroadcast(event, data, exceptDeviceId = null) {
  const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const [deviceId, entry] of meshDevices) {
    if (deviceId === exceptDeviceId) continue;
    try {
      entry.res.write(frame);
    } catch (_) {
      meshDevices.delete(deviceId);
    }
  }
}

// Coarse TDoA multilateration by grid search. Given confirming devices with positions
// and audio arrival timestamps on a *shared* clock, the true source is the point that
// makes (arrival_i - travelTime_i) constant across devices (that constant is the common
// clock offset). We search a grid around the centroid and pick the point minimising the
// variance of that quantity. Returns null when there is not enough synchronised timing
// data, so the caller falls back to the confidence-weighted centroid.
function triangulateTdoa(located) {
  // A timestamp is only usable when we also know how well the reporting device's
  // clock is aligned to ours; an unqualified timestamp must never be treated as good.
  const timed = located.filter(
    (v) =>
      Number.isFinite(v.detectedAtMs) &&
      Number.isFinite(v.timingUncertaintyMs) &&
      v.timingUncertaintyMs <= MESH_MAX_TIMING_UNCERTAINTY_MS
  );
  if (timed.length < 3) return null;

  const arrivals = timed.map((v) => v.detectedAtMs);
  const spread = Math.max(...arrivals) - Math.min(...arrivals);
  if (spread > MESH_MAX_ARRIVAL_SPREAD_MS) return null;

  const lat0 = timed.reduce((s, v) => s + v.latitude, 0) / timed.length;
  const lon0 = timed.reduce((s, v) => s + v.longitude, 0) / timed.length;
  const mPerDegLat = 111320;
  const mPerDegLon = 111320 * Math.cos((lat0 * Math.PI) / 180);
  const pts = timed.map((v) => ({
    x: (v.longitude - lon0) * mPerDegLon,
    y: (v.latitude - lat0) * mPerDegLat,
    t: v.detectedAtMs / 1000 // seconds
  }));

  // Without a baseline between the listeners there is nothing to triangulate from.
  const baseline = Math.max(
    ...pts.map((a) => Math.max(...pts.map((b) => Math.hypot(a.x - b.x, a.y - b.y))))
  );
  if (baseline < MESH_MIN_BASELINE_METERS) return null;

  // Sweep for the point where `arrival - travelTime` is most nearly constant across
  // listeners: coarse first, then a fine pass inside the winning cell.
  const sweep = (cx, cy, range, step) => {
    let best = null;
    for (let gx = cx - range; gx <= cx + range; gx += step) {
      for (let gy = cy - range; gy <= cy + range; gy += step) {
        const offsets = pts.map((p) => p.t - Math.hypot(p.x - gx, p.y - gy) / SPEED_OF_SOUND_MPS);
        const mean = offsets.reduce((s, o) => s + o, 0) / offsets.length;
        const variance = offsets.reduce((s, o) => s + (o - mean) ** 2, 0) / offsets.length;
        if (!best || variance < best.variance) best = { gx, gy, variance };
      }
    }
    return best;
  };

  let best = sweep(0, 0, 150, 5);
  if (!best) return null;
  best = sweep(best.gx, best.gy, 5, 0.5) || best;

  const residualMs = Math.sqrt(best.variance) * 1000;
  // The fit must be consistent with how well the clocks are actually known.
  const tolerance = Math.max(10, Math.max(...timed.map((v) => v.timingUncertaintyMs)) * 2);
  if (residualMs > tolerance) return null;

  return {
    latitude: lat0 + best.gy / mPerDegLat,
    longitude: lon0 + best.gx / mPerDegLon,
    method: "tdoa_multilateration",
    timingResidualMs: Number(residualMs.toFixed(1))
  };
}

// Estimate the source from all confirming votes. Prefers TDoA when synchronised timing
// is available, else a confidence-weighted centroid. Adds a relative floor offset from
// altitude spread and a spread radius as a crude confidence bound.
function meshEstimateSource(session) {
  const located = [...session.votes.values()].filter(
    (v) => v.isGunshot && Number.isFinite(v.latitude) && Number.isFinite(v.longitude) &&
      !(v.latitude === 0 && v.longitude === 0)
  );
  if (located.length === 0) return null;

  let base = triangulateTdoa(located);
  if (!base) {
    let wSum = 0, latAcc = 0, lonAcc = 0;
    for (const v of located) {
      const w = Math.min(1, Math.max(0.05, v.confidence || 0.05));
      wSum += w; latAcc += v.latitude * w; lonAcc += v.longitude * w;
    }
    base = {
      latitude: latAcc / wSum,
      longitude: lonAcc / wSum,
      method: located.length >= 2 ? "weighted_centroid" : "single_report",
      timingResidualMs: null
    };
  }

  const altitudes = located.map((v) => v.altitude).filter(Number.isFinite);
  const floorOffset = altitudes.length >= 2
    ? Math.round((Math.max(...altitudes) - Math.min(...altitudes)) / MESH_FLOOR_HEIGHT_METERS)
    : 0;
  const spreadMeters = Math.max(
    0,
    ...located.map((v) => haversineMeters(base.latitude, base.longitude, v.latitude, v.longitude))
  );

  return { ...base, floorOffset, spreadMeters, contributingNodes: located.length };
}

function meshEvaluateSession(session) {
  const confirmed = [...session.votes.values()].filter((v) => v.isGunshot);
  const independent = confirmed.filter((v) => v.deviceId !== session.sourceDeviceId);
  const threshold = meshRequiredConfirmations();
  const hasPeers = meshSensorCount() > 1;
  const enough = confirmed.length >= threshold && (!hasPeers || independent.length >= 1);
  if (!enough || session.triggered) return null;

  session.triggered = true;
  const estimate = meshEstimateSource(session);
  session.lastEstimate = estimate;
  const confirmedByNodes = confirmed.map((v) => v.deviceId);

  // Fold the confirmed, triangulated detection into the incident model so the dispatch
  // dashboard, Opius client, and notifications all react — same as the Android path.
  const observedAtMs = Date.now();
  const incidentId = `incident-mesh-${session.sessionId.slice(0, 8)}`;
  const incident = incidents.get(incidentId) || createIncident(incidentId, observedAtMs);
  confirmedByNodes.forEach((n) => addUnique(incident.devices, n));
  confirmedByNodes.forEach((n) => addUnique(incident.confirmedByNodes, n));
  if (estimate) {
    incident.location = { latitude: estimate.latitude, longitude: estimate.longitude };
  }
  incident.status = "CONFIRMED_RESPONSE";
  incident.dispatchRecommended = true;
  incident.lastObservedAtMs = observedAtMs;
  incident.updatedAt = nowIso();
  incident.recommendedAction = recommendationFor(incident);
  incident.policeBrief = policeBriefFor(incident);
  incident.medicalBrief = medicalBriefFor(incident);
  incidents.set(incidentId, incident);
  scheduleDispatchNotification(incident);
  session.incidentId = incidentId;

  const trigger = {
    sessionId: session.sessionId,
    incidentId,
    confirmedByNodes,
    estimate,
    timestamp: nowIso()
  };
  meshBroadcast("response_trigger", trigger);
  console.log(
    `[mesh] RESPONSE TRIGGERED session=${session.sessionId} confirmed=${confirmed.length}/${threshold} ` +
      `method=${estimate ? estimate.method : "none"} spread=${estimate ? estimate.spreadMeters.toFixed(1) : "?"}m ` +
      `floor=${estimate ? estimate.floorOffset : 0}`
  );
  return trigger;
}

// After a session has triggered, later votes can still sharpen the location — most
// importantly they can push it from a coarse centroid to a TDoA fix once ≥3 timed
// confirmations exist. Re-broadcast a refined location when it materially improves.
function meshMaybeRefine(session) {
  const estimate = meshEstimateSource(session);
  if (!estimate) return;
  const prev = session.lastEstimate;
  const improved =
    !prev ||
    (estimate.method === "tdoa_multilateration" && prev.method !== "tdoa_multilateration") ||
    estimate.contributingNodes > prev.contributingNodes;
  if (!improved) return;

  session.lastEstimate = estimate;
  const incident = incidents.get(session.incidentId);
  if (incident) {
    incident.location = { latitude: estimate.latitude, longitude: estimate.longitude };
    incident.updatedAt = nowIso();
  }
  meshBroadcast("location_refined", {
    sessionId: session.sessionId,
    incidentId: session.incidentId,
    estimate,
    timestamp: nowIso()
  });
  console.log(
    `[mesh] location refined session=${session.sessionId} method=${estimate.method} ` +
      `nodes=${estimate.contributingNodes} residual=${estimate.timingResidualMs}ms`
  );
}

function meshRecordVote(session, vote) {
  session.votes.set(vote.deviceId, vote);
  if (!session.triggered) {
    return meshEvaluateSession(session);
  }
  meshMaybeRefine(session);
  return null;
}

function handleMeshStream(req, res, params) {
  const deviceId = params.get("deviceId") || crypto.randomUUID();
  const busToken = crypto.randomUUID();
  res.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-store",
    connection: "keep-alive"
  });
  res.write(`event: hello\ndata: ${JSON.stringify({ deviceId, busToken, serverTimeMs: Date.now(), ok: true })}\n\n`);

  meshDevices.set(deviceId, {
    res,
    busToken,
    lastSeen: Date.now(),
    latitude: numberOrNull(params.get("lat")),
    longitude: numberOrNull(params.get("lon")),
    platform: params.get("platform") || "unknown",
    // Only a device with its microphone on can corroborate a detection.
    sensor: params.get("sensor") === "1"
  });
  console.log(`[mesh] device connected: ${deviceId} (${meshDevices.size} online)`);

  const keepAlive = setInterval(() => {
    try { res.write(": ping\n\n"); } catch (_) { /* handled on close */ }
  }, 20000);

  req.on("close", () => {
    clearInterval(keepAlive);
    // Only drop the entry if it is still this connection: on a reconnect the newer
    // stream has already replaced it, and deleting by id alone would evict it.
    if (meshDevices.get(deviceId)?.res === res) {
      meshDevices.delete(deviceId);
    }
    console.log(`[mesh] device disconnected: ${deviceId} (${meshDevices.size} online)`);
  });
}

async function handleMeshDetection(req, res) {
  let body;
  try { body = await readJson(req); } catch (e) { json(res, 400, { ok: false, error: `Invalid JSON: ${e.message}` }); return; }

  const deviceId = String(body.deviceId || "").trim();
  if (!deviceId) { json(res, 400, { ok: false, error: "deviceId required" }); return; }
  if (!authenticateMeshWrite(req, deviceId)) { json(res, 401, { ok: false, error: "Unauthorized" }); return; }

  const sessionId = crypto.randomUUID();
  const session = { sessionId, sourceDeviceId: deviceId, startedAtMs: Date.now(), triggered: false, votes: new Map() };
  detectionSessions.set(sessionId, session);
  setTimeout(() => detectionSessions.delete(sessionId), MESH_VOTE_WINDOW_MS * 3);

  // The raising device's own detection counts as its vote.
  meshRecordVote(session, {
    deviceId,
    isGunshot: true,
    confidence: numberOrNull(body.confidence) ?? 1,
    latitude: numberOrNull(body.latitude) ?? NaN,
    longitude: numberOrNull(body.longitude) ?? NaN,
    altitude: numberOrNull(body.altitude) ?? NaN,
    detectedAtMs: numberOrNull(body.detectedAtMs) ?? Date.now(),
    timingUncertaintyMs: numberOrNull(body.timingUncertaintyMs) ?? NaN
  });

  // Ask every other device to classify its own audio and vote.
  meshBroadcast("wake_classify", {
    sessionId,
    sourceDeviceId: deviceId,
    latitude: numberOrNull(body.latitude),
    longitude: numberOrNull(body.longitude),
    at: nowIso()
  }, deviceId);

  json(res, 202, {
    ok: true,
    sessionId,
    required: meshRequiredConfirmations(),
    online: meshDevices.size,
    sensors: meshSensorCount()
  });
}

async function handleMeshSensorState(req, res) {
  let body;
  try { body = await readJson(req); } catch (e) { json(res, 400, { ok: false, error: `Invalid JSON: ${e.message}` }); return; }

  const deviceId = String(body.deviceId || "").trim();
  if (!authenticateMeshWrite(req, deviceId)) { json(res, 401, { ok: false, error: "Unauthorized" }); return; }
  const entry = meshDevices.get(deviceId);
  if (!entry) { json(res, 404, { ok: false, error: "Device not connected" }); return; }

  entry.sensor = Boolean(body.sensor);
  if (Number.isFinite(numberOrNull(body.latitude))) entry.latitude = numberOrNull(body.latitude);
  if (Number.isFinite(numberOrNull(body.longitude))) entry.longitude = numberOrNull(body.longitude);
  entry.lastSeen = Date.now();

  console.log(`[mesh] ${deviceId} listening=${entry.sensor} (${meshSensorCount()} sensors online)`);
  json(res, 200, { ok: true, sensors: meshSensorCount(), required: meshRequiredConfirmations() });
}

async function handleMeshVote(req, res) {
  let body;
  try { body = await readJson(req); } catch (e) { json(res, 400, { ok: false, error: `Invalid JSON: ${e.message}` }); return; }

  const sessionId = String(body.sessionId || "").trim();
  const deviceId = String(body.deviceId || "").trim();
  if (!deviceId) { json(res, 400, { ok: false, error: "deviceId required" }); return; }
  if (!authenticateMeshWrite(req, deviceId)) { json(res, 401, { ok: false, error: "Unauthorized" }); return; }
  const session = detectionSessions.get(sessionId);
  if (!session) { json(res, 404, { ok: false, error: "Unknown or expired session" }); return; }
  if (Date.now() - session.startedAtMs > MESH_VOTE_WINDOW_MS) {
    json(res, 409, { ok: false, error: "Vote window closed" }); return;
  }

  const trigger = meshRecordVote(session, {
    deviceId,
    isGunshot: Boolean(body.isGunshot),
    confidence: numberOrNull(body.confidence) ?? 0,
    latitude: numberOrNull(body.latitude) ?? NaN,
    longitude: numberOrNull(body.longitude) ?? NaN,
    altitude: numberOrNull(body.altitude) ?? NaN,
    detectedAtMs: numberOrNull(body.detectedAtMs) ?? Date.now(),
    timingUncertaintyMs: numberOrNull(body.timingUncertaintyMs) ?? NaN
  });

  const confirmed = [...session.votes.values()].filter((v) => v.isGunshot).length;
  json(res, 202, { ok: true, sessionId, confirmed, required: meshRequiredConfirmations(), triggered: Boolean(trigger) });
}

// ─────────────────────────────────────────────────────────────────────────────
// Opius — mobile-framed user-facing client (the cross-platform / iOS relay app).
// Faithful to the Opius prototype (Home / Chat / Update tabs) but wired to the
// live relay endpoints instead of mock state. Served at GET /app.
// ─────────────────────────────────────────────────────────────────────────────
function opiusAppHtml() {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0A0A0A">
<title>Opius</title>
<style>
  :root {
    --accent: oklch(56% 0.2 258);
    --danger: oklch(55% 0.19 25);
    --danger-light: oklch(94% 0.05 25);
    --warning: oklch(78% 0.14 75);
    --warning-light: oklch(95% 0.05 80);
    --safe: oklch(64% 0.15 145);
    --safe-light: oklch(94% 0.05 145);
    --text: oklch(20% 0.01 260);
    --text-2: oklch(48% 0.01 260);
    --text-3: oklch(65% 0.01 260);
    --bg: oklch(96% 0.004 260);
    --border: oklch(90% 0.005 260);
    --card: #ffffff;
  }
  @keyframes opiusPulse { 0% { opacity:.55; transform:scale(1); } 100% { opacity:0; transform:scale(2.4); } }
  @keyframes opiusBlink { 0%,100% { opacity:1; } 50% { opacity:.35; } }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { margin:0; padding:0; height:100%; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
    background: var(--bg); color: var(--text);
    display:flex; flex-direction:column; height:100dvh; overflow:hidden;
  }
  #topAlert {
    position:fixed; top:calc(env(safe-area-inset-top) + 8px); left:12px; right:12px; z-index:30;
    background: var(--warning-light); border:1px solid var(--warning); color: oklch(35% 0.1 75);
    padding:9px 14px; border-radius:12px; font-size:12px; font-weight:600;
    box-shadow:0 6px 16px rgba(0,0,0,.12); display:none;
  }
  header {
    display:flex; align-items:center; justify-content:space-between;
    padding:calc(env(safe-area-inset-top) + 10px) 20px 12px; background:var(--card);
    border-bottom:1px solid var(--border); flex-shrink:0;
  }
  .brand { display:flex; align-items:center; gap:8px; }
  .brand .mark { width:22px; height:22px; border-radius:7px; background:var(--accent); }
  .brand .name { font-size:21px; font-weight:800; letter-spacing:-.3px; }
  .pill { display:flex; align-items:center; gap:6px; padding:6px 12px; border-radius:999px; font-size:12px; font-weight:700; }
  .pill .dot { width:6px; height:6px; border-radius:999px; }
  main { flex:1; overflow:auto; -webkit-overflow-scrolling:touch; }
  .tab { display:none; }
  .tab.active { display:block; }
  .center { height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:22px; padding:0 40px; text-align:center; }
  .radar { position:relative; width:96px; height:96px; display:flex; align-items:center; justify-content:center; }
  .radar .ring { position:absolute; inset:0; border-radius:999px; background:var(--accent); opacity:.35; animation:opiusPulse 2.4s ease-out infinite; }
  .radar .ring.d { inset:14px; opacity:.5; animation-delay:.8s; }
  .radar .core { width:22px; height:22px; border-radius:7px; background:var(--accent); }
  h2.section { font-size:17px; font-weight:800; margin:0; }
  .row { display:flex; align-items:center; justify-content:space-between; }
  .btn-pill { border:1px solid var(--accent); color:var(--accent); background:#fff; font-size:12px; font-weight:700; padding:7px 13px; border-radius:999px; }
  .card { background:var(--card); border-radius:14px; box-shadow:0 1px 6px rgba(0,0,0,.06); }
  .update { margin:0 20px 10px; padding:12px 14px; border-radius:12px; background:var(--card); box-shadow:0 1px 6px rgba(0,0,0,.06); border-left:4px solid var(--accent); }
  .update.critical { border-left-color: var(--danger); }
  .update .meta { display:flex; justify-content:space-between; align-items:baseline; }
  .update .src { font-size:12.5px; font-weight:700; }
  .update .time { font-size:10.5px; color:var(--text-3); }
  .update .txt { font-size:13px; color:var(--text-2); margin-top:3px; line-height:1.4; }
  .summary { margin:0 20px 12px; padding:14px; border-radius:14px; background:var(--danger-light); border-left:4px solid var(--danger); }
  .summary .k { font-size:10.5px; font-weight:800; letter-spacing:.06em; color:oklch(45% 0.19 25); margin-bottom:4px; }
  .summary .v { font-size:13.5px; font-weight:600; line-height:1.4; }
  .firstaid { margin:0 20px 14px; padding:14px; border-radius:14px; background:var(--card); box-shadow:0 1px 6px rgba(0,0,0,.06); }
  .firstaid .k { font-size:10.5px; font-weight:800; letter-spacing:.06em; color:oklch(45% 0.1 145); margin-bottom:6px; }
  .firstaid .head { font-size:13.5px; font-weight:700; margin-bottom:8px; }
  .firstaid ol { margin:0; padding-left:18px; }
  .firstaid li { font-size:12.5px; color:var(--text-2); line-height:1.45; margin-bottom:6px; }
  .firstaid .disc { font-size:11px; color:var(--text-3); margin-top:6px; }
  .empty { margin:0 20px 20px; padding:22px 14px; text-align:center; color:var(--text-3); font-size:12.5px; background:var(--card); border-radius:14px; }
  .msg { display:flex; margin:0 20px; }
  .bubble { max-width:78%; padding:10px 14px; border-radius:16px; font-size:13.5px; line-height:1.4; }
  .msg.bot { justify-content:flex-start; } .msg.bot .bubble { background:#F1F0EE; color:var(--text); }
  .msg.user { justify-content:flex-end; } .msg.user .bubble { background:var(--accent); color:#fff; }
  .quickrow { display:flex; gap:8px; padding:8px 20px; overflow-x:auto; }
  .quickrow .btn-pill { white-space:nowrap; flex-shrink:0; font-weight:600; }
  .composer { display:flex; gap:8px; align-items:center; padding:10px 16px calc(env(safe-area-inset-bottom) + 10px); border-top:1px solid var(--border); background:#fff; }
  .composer input { flex:1; padding:10px 14px; border-radius:20px; border:1px solid var(--border); font-size:13.5px; outline:none; }
  .send { width:38px; height:38px; border-radius:19px; background:var(--accent); color:#fff; border:none; font-size:16px; font-weight:700; flex-shrink:0; }
  .field { padding:0 20px; }
  label.f { display:block; font-size:12px; font-weight:700; color:var(--text-2); margin:16px 0 6px; }
  select, input.f { width:100%; padding:11px 12px; border-radius:10px; border:1px solid var(--border); font-size:14px; background:#fff; }
  .stepper { display:flex; align-items:center; gap:12px; }
  .stepper button { width:30px; height:30px; border-radius:15px; border:1px solid var(--border); background:#fff; font-size:16px; }
  .stepper .n { font-size:15px; font-weight:700; width:16px; text-align:center; }
  .submit { width:calc(100% - 40px); margin:18px 20px; padding:14px; border-radius:12px; background:var(--accent); color:#fff; border:none; font-size:14.5px; font-weight:700; }
  .sent { margin:0 20px 20px; padding:14px; border-radius:12px; background:var(--safe-light); }
  .sent .k { font-size:13px; font-weight:700; color:oklch(35% 0.1 145); margin-bottom:4px; }
  .sent .v { font-size:12.5px; color:var(--text-2); line-height:1.4; }
  nav {
    display:flex; height:56px; border-top:1px solid var(--border); background:#fff;
    padding-bottom:env(safe-area-inset-bottom); flex-shrink:0;
  }
  nav button { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; background:none; border:none; color:var(--text-3); font-size:10.5px; font-weight:600; }
  nav button.active { color:var(--accent); }
  .title { padding:8px 20px 14px; }
  .title .h { font-size:21px; font-weight:800; }
  .title .s { font-size:12.5px; color:var(--text-2); margin-top:2px; }
</style>
</head>
<body>
  <div id="topAlert"></div>
  <header>
    <div class="brand"><div class="mark"></div><div class="name">Opius</div></div>
    <div class="pill" id="statusPill"><span class="dot"></span><span id="statusText">Listening</span></div>
  </header>

  <main>
    <!-- HOME -->
    <section class="tab active" id="tab-home">
      <div id="homeIdle" class="center">
        <div class="radar"><div class="ring"></div><div class="ring d"></div><div class="core"></div></div>
        <div style="font-size:22px;font-weight:800;letter-spacing:-.3px;">Opius</div>
        <div class="pill" id="idlePill"><span class="dot"></span><span id="idlePillText">Listening</span></div>
        <div style="font-size:13.5px;color:var(--text-3);line-height:1.5;max-width:260px;" id="idleBlurb">Opius will alert you the moment something needs your attention. Turn on listening mode to also let this phone help detect and locate an incident.</div>
        <button class="btn-pill" id="sensorToggle" style="padding:11px 20px;font-size:13.5px;">Turn on listening mode</button>
        <div id="sensorReadout" style="font-size:11.5px;color:var(--text-3);line-height:1.6;max-width:270px;"></div>
      </div>
      <div id="homeIncident" style="display:none;">
        <div class="row" style="padding:14px 20px 10px;">
          <h2 class="section">Live Updates</h2>
          <button class="btn-pill" id="pullBtn">Refresh</button>
        </div>
        <div id="summary"></div>
        <div id="firstAid"></div>
        <div id="updates"></div>
        <div style="height:16px;"></div>
      </div>
    </section>

    <!-- CHAT -->
    <section class="tab" id="tab-chat" style="height:100%;">
      <div style="display:flex;flex-direction:column;height:100%;">
        <div class="title"><div class="h">Opius Assistant</div><div class="s">Connected to the campus response team</div></div>
        <div id="chatScroll" style="flex:1;overflow:auto;display:flex;flex-direction:column;gap:10px;padding-bottom:10px;"></div>
        <div class="quickrow">
          <button class="btn-pill" data-quick="I'm safe.">I'm safe</button>
          <button class="btn-pill" data-quick="I need help.">I need help</button>
          <button class="btn-pill" data-quick="Someone is injured.">Someone's injured</button>
          <button class="btn-pill" data-quick="Where is the shooter?">Where's the shooter?</button>
        </div>
        <div class="composer">
          <input id="chatInput" type="text" placeholder="Message…" autocomplete="off"/>
          <button class="send" id="chatSend">↑</button>
        </div>
      </div>
    </section>

    <!-- UPDATE -->
    <section class="tab" id="tab-update">
      <div class="title"><div class="h">Share Your Status</div><div class="s">Goes directly to campus responders.</div></div>
      <div class="field">
        <label class="f">Building</label>
        <select id="fBuilding"></select>
        <div style="display:flex;gap:12px;">
          <div style="flex:1;"><label class="f">Floor</label><input class="f" id="fFloor" placeholder="e.g. 2"/></div>
          <div style="flex:1;"><label class="f">Room</label><input class="f" id="fRoom" placeholder="e.g. 204"/></div>
        </div>
        <div class="row" style="margin-top:16px;"><div style="font-size:13.5px;font-weight:600;">People with you</div><div class="stepper"><button data-step="people" data-d="-1">–</button><span class="n" id="nPeople">1</span><button data-step="people" data-d="1">+</button></div></div>
        <div class="row" style="margin-top:14px;"><div style="font-size:13.5px;font-weight:600;">Injured</div><div class="stepper"><button data-step="injured" data-d="-1">–</button><span class="n" id="nInjured">0</span><button data-step="injured" data-d="1">+</button></div></div>
        <div class="row" style="margin-top:14px;"><div style="font-size:13.5px;font-weight:600;">Critically injured</div><div class="stepper"><button data-step="critical" data-d="-1">–</button><span class="n" id="nCritical">0</span><button data-step="critical" data-d="1">+</button></div></div>
      </div>
      <button class="submit" id="statusSubmit">Send Status Update</button>
      <div id="statusSent"></div>
    </section>
  </main>

  <nav>
    <button class="active" data-tab="home">
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none"><path d="M4 11L12 4L20 11V20H14V14H10V20H4V11Z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>Home</button>
    <button data-tab="chat">
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none"><path d="M4 5H20V16H9L4 20V5Z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>Chat</button>
    <button data-tab="update">
      <svg width="21" height="21" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="8" r="3.4" stroke="currentColor" stroke-width="2"/><path d="M5 20C5 16 8 14 12 14C16 14 19 16 19 20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>Update</button>
  </nav>

<script>
(function () {
  var DEFAULT_BUILDINGS = ["Fowler Hall", "Engineering Quad", "Library", "Student Union"];
  var form = { people: 1, injured: 0, critical: 0 };
  var incident = null;
  var chat = [{ sender: "bot", text: "Hi, I'm the Opius safety assistant. I'm here if you need to report your status or ask for help." }];

  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return ({ "&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;" })[c]; }); }
  function api(path, opts) {
    opts = opts || {};
    // Caller-supplied credentials win: mesh writes carry their own per-device
    // "Bus <token>" authorization, which must not be overwritten here.
    opts.headers = Object.assign({ "content-type": "application/json" }, opts.headers || {});
    return fetch(path, opts).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (!r.ok) {
          var error = new Error((data && data.error) || ("HTTP " + r.status));
          error.status = r.status;
          error.body = data;
          throw error;
        }
        return data;
      });
    });
  }

  // Tabs
  Array.prototype.forEach.call(document.querySelectorAll("nav button"), function (b) {
    b.addEventListener("click", function () {
      var t = b.getAttribute("data-tab");
      Array.prototype.forEach.call(document.querySelectorAll("nav button"), function (x) { x.classList.toggle("active", x === b); });
      Array.prototype.forEach.call(document.querySelectorAll(".tab"), function (s) { s.classList.toggle("active", s.id === "tab-" + t); });
    });
  });

  // Transient top-of-screen notice, used when a confirmed detection arrives on the
  // mesh stream so the user is not waiting on the next poll.
  var bannerTimer = null;
  function banner(text) {
    var el = $("topAlert");
    el.textContent = text;
    el.style.display = "block";
    if (bannerTimer) clearTimeout(bannerTimer);
    bannerTimer = setTimeout(function () { el.style.display = "none"; }, 12000);
  }

  // Whether this device's own microphone is helping detect, which is a different
  // thing from whether it is receiving alerts — the pill should not claim otherwise.
  var sensorListening = false;
  var lastStatusActive = false;
  function refreshStatusPill() { setStatusPill(lastStatusActive); }

  function setStatusPill(active) {
    lastStatusActive = active;
    var pill = $("statusPill"), dot = pill.querySelector(".dot");
    var idlePill = $("idlePill"), idleDot = idlePill.querySelector(".dot");
    if (active) {
      pill.style.background = "var(--danger-light)"; pill.style.color = "var(--danger)"; dot.style.background = "var(--danger)"; $("statusText").textContent = "Active Alert";
      idlePill.style.background = "var(--danger-light)"; idlePill.style.color = "var(--danger)"; idleDot.style.background = "var(--danger)"; $("idlePillText").textContent = "Active Alert";
    } else {
      var idleLabel = sensorListening ? "Listening" : "Alerts on";
      pill.style.background = "var(--safe-light)"; pill.style.color = "oklch(40% 0.1 145)"; dot.style.background = "var(--safe)"; $("statusText").textContent = idleLabel;
      idlePill.style.background = "var(--safe-light)"; idlePill.style.color = "oklch(40% 0.1 145)"; idleDot.style.background = "var(--safe)"; $("idlePillText").textContent = idleLabel;
    }
  }

  function isCritical(text) { return /confirmed|active shooter|shelter|evacuate|injured|avoid/i.test(text || ""); }

  function renderHome() {
    var active = incident && incident.status && incident.status !== "CLEARED";
    setStatusPill(!!active);
    $("homeIdle").style.display = active ? "none" : "flex";
    $("homeIncident").style.display = active ? "block" : "none";
    if (!active) return;

    var updates = (incident.liveUpdates || []).map(function (u) { return typeof u === "string" ? u : (u.text || u.message || ""); }).filter(Boolean);
    var summaryText = updates.filter(isCritical)[0] || incident.recommendedAction || "";
    $("summary").innerHTML = summaryText
      ? '<div class="summary"><div class="k">SAFETY SUMMARY</div><div class="v">' + esc(summaryText) + '</div></div>'
      : "";

    var fa = incident.firstAid;
    $("firstAid").innerHTML = fa
      ? '<div class="firstaid"><div class="k">FIRST AID</div><div class="head">' + esc(fa.headline) + '</div><ol>' +
        (fa.steps || []).map(function (s) { return "<li><strong>" + esc(s.title) + ":</strong> " + esc(s.detail) + "</li>"; }).join("") +
        '</ol><div class="disc">' + esc(fa.disclaimer || "") + '</div></div>'
      : "";

    $("updates").innerHTML = updates.length
      ? updates.map(function (t) {
          return '<div class="update' + (isCritical(t) ? " critical" : "") + '"><div class="meta"><div class="src">Opius Network</div></div><div class="txt">' + esc(t) + '</div></div>';
        }).join("")
      : '<div class="empty">No updates yet. You\\'ll see alerts here if something happens nearby.</div>';
  }

  function renderChat() {
    var el = $("chatScroll");
    el.innerHTML = chat.map(function (m) {
      return '<div class="msg ' + (m.sender === "user" ? "user" : "bot") + '"><div class="bubble">' + esc(m.text) + "</div></div>";
    }).join("");
    el.scrollTop = el.scrollHeight;
  }

  function botReply(text) { chat.push({ sender: "bot", text: text }); renderChat(); }

  function sendChat(text) {
    text = (text || "").trim();
    if (!text) return;
    chat.push({ sender: "user", text: text });
    renderChat();
    if (incident && incident.id) {
      api("/v1/incidents/" + encodeURIComponent(incident.id) + "/authority-messages", {
        headers: Sensor.authHeaders(),
        method: "POST",
        body: JSON.stringify({ sender: "Community member", role: "user", message: text })
      }).then(function () {
        botReply("Thanks — I've logged that and shared it with the response team." + (incident && incident.status !== "CLEARED" ? " Stay safe and keep your phone nearby." : ""));
      }).catch(function () { botReply("I couldn't reach the response team just now — I'll keep retrying."); });
    } else {
      botReply("There's no active incident right now, but I've noted this. Open the Update tab to share your location if you need help.");
    }
  }

  $("chatSend").addEventListener("click", function () { var v = $("chatInput").value; $("chatInput").value = ""; sendChat(v); });
  $("chatInput").addEventListener("keydown", function (e) { if (e.key === "Enter") { var v = $("chatInput").value; $("chatInput").value = ""; sendChat(v); } });
  Array.prototype.forEach.call(document.querySelectorAll("[data-quick]"), function (b) {
    b.addEventListener("click", function () { sendChat(b.getAttribute("data-quick")); });
  });

  // Update form
  function buildingOptions() {
    var opts = (incident && incident.zones && incident.zones.length) ? incident.zones : DEFAULT_BUILDINGS;
    $("fBuilding").innerHTML = opts.map(function (b) { return '<option value="' + esc(b) + '">' + esc(b) + "</option>"; }).join("");
  }
  Array.prototype.forEach.call(document.querySelectorAll("[data-step]"), function (b) {
    b.addEventListener("click", function () {
      var k = b.getAttribute("data-step"), d = Number(b.getAttribute("data-d"));
      form[k] = Math.max(k === "people" ? 0 : 0, form[k] + d);
      $("n" + k.charAt(0).toUpperCase() + k.slice(1)).textContent = form[k];
    });
  });
  $("statusSubmit").addEventListener("click", function () {
    var building = $("fBuilding").value, floor = $("fFloor").value.trim(), room = $("fRoom").value.trim();
    var summary = building + (floor ? ", Floor " + floor : "") + (room ? ", Room " + room : "") + " · " + form.people + " with you" +
      (form.injured > 0 ? " · " + form.injured + " injured" : "") + (form.critical > 0 ? " · " + form.critical + " critical" : "");
    var safetyStatus = form.injured > 0 || form.critical > 0 ? "INJURED" : "SAFE";
    var body = {
      safetyStatus: safetyStatus,
      injuredCount: form.injured + form.critical,
      companionsCount: form.people,
      roomNumber: (floor ? "Floor " + floor + " " : "") + (room ? "Room " + room : ""),
      note: "Status from " + summary
    };
    var now = new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    function showSent() {
      $("statusSent").innerHTML = '<div class="sent"><div class="k">✓ Sent to responders at ' + esc(now) + '</div><div class="v">' + esc(summary) + "</div></div>";
    }
    if (incident && incident.id) {
      api("/v1/incidents/" + encodeURIComponent(incident.id) + "/notes", { method: "POST", headers: Sensor.authHeaders(), body: JSON.stringify(body) })
        .then(showSent).catch(function () {
          $("statusSent").innerHTML = '<div class="sent" style="background:var(--danger-light)"><div class="k" style="color:var(--danger)">Couldn\\'t send — will retry</div><div class="v">' + esc(summary) + "</div></div>";
        });
    } else {
      $("statusSent").innerHTML = '<div class="sent"><div class="k">Saved locally</div><div class="v">No active incident yet — ' + esc(summary) + "</div></div>";
    }
  });

  function refresh() {
    return api("/v1/incidents/latest").then(function (res) {
      incident = res && res.ok ? res.incident : null;
      renderHome();
      buildingOptions();
    }).catch(function () { /* offline; keep last state */ });
  }
  $("pullBtn").addEventListener("click", refresh);

  // ───────────────────────────────────────────────────────────────────────────
  // Sensor node: this page as a listening member of the mesh.
  //
  // Google Nearby is Android-only, so an iPhone cannot join the peer-to-peer mesh
  // directly. It can, however, do everything that actually matters over this relay:
  // listen on its own microphone, raise a candidate detection, vote on detections
  // raised by other phones, and receive the confirmed response. That is what makes
  // an iOS device a participant rather than a spectator.
  //
  // Honest limits, stated plainly in the UI too:
  //  - This is an *impulsive transient* detector (peak level, crest factor, rise
  //    over the running background, microphone clipping) — not a trained gunshot
  //    classifier like the Android node's YAMNet head. It corroborates; it should
  //    not be the only thing that ever fires.
  //  - Listening mode is explicit and user-visible, which is both the honest design
  //    and the only pattern Apple permits.
  // ───────────────────────────────────────────────────────────────────────────
  var Sensor = (function () {
    var BUFFER_SIZE = 1024;
    var PEAK_MIN = 0.35;        // absolute level that could be an impulse nearby
    var CREST_MIN = 4.0;        // peak/RMS: impulses are peaky, speech and music are not
    var SNR_MIN = 6.0;          // rise over the running background level
    var CLIP_GATE = 0.005;      // 0.5% of samples railed: a very close, loud event
    var IMPULSE_MEMORY_MS = 3000;
    var RAISE_COOLDOWN_MS = 6000;
    var BACKGROUND_ALPHA = 0.02;

    var enabled = false;
    var audioCtx = null, micStream = null, processor = null, micSource = null;
    var background = 0.01;
    var lastImpulse = null;      // { atServerMs, confidence, peak, crest, clipFraction }
    var lastRaiseAtMs = 0;
    var stream = null;           // EventSource
    var deviceId = null;
    var busToken = null;         // per-device credential for mesh writes
    var clockOffsetMs = 0;       // server clock minus this device's clock
    var clockUncertaintyMs = NaN;
    var position = null;         // { latitude, longitude, altitude }
    var onTrigger = function () {};

    function readout(html) { $("sensorReadout").innerHTML = html; }

    // api() rejects on any non-2xx, and some failures here are entirely normal —
    // voting on a session that has already closed returns 404, and losing the stream
    // invalidates the bus token. Handle them here so a routine race cannot surface as
    // an unhandled rejection, and report the ones a user can act on.
    function busPost(path, body) {
      return api(path, {
        method: "POST",
        headers: busToken ? { authorization: "Bus " + busToken } : {},
        body: JSON.stringify(body)
      }).catch(function (error) {
        if (error && error.status === 401) {
          // The stream dropped and took the token with it; the EventSource will
          // reconnect on its own and issue a new one.
          busToken = null;
        } else if (error && error.status !== 404 && error.status !== 409) {
          render("Could not reach the response relay — retrying.");
        }
        return null;
      });
    }

    function deviceIdentity() {
      try {
        var stored = localStorage.getItem("opius-device-id");
        if (stored) return stored;
        var made = "opius-" + Math.random().toString(36).slice(2, 10);
        localStorage.setItem("opius-device-id", made);
        return made;
      } catch (e) {
        return "opius-" + Math.random().toString(36).slice(2, 10);
      }
    }

    // NTP-style: several round trips, keep the one with the lowest delay, because a
    // fast round trip bounds how wrong the offset can be. Sound covers 0.343 m per
    // millisecond, so this is what arrival-time triangulation ultimately rests on.
    function syncClock() {
      var samples = [];
      function once() {
        var t1 = Date.now();
        return fetch("/v1/time", { cache: "no-store" })
          .then(function (r) { return r.json(); })
          .then(function (body) {
            var t4 = Date.now();
            var rtt = t4 - t1;
            var offset = body.serverTimeMs - (t1 + t4) / 2;
            samples.push({ offset: offset, rtt: rtt });
          })
          .catch(function () { /* keep whatever we already had */ });
      }
      return once().then(once).then(once).then(once).then(once).then(function () {
        if (!samples.length) return;
        samples.sort(function (a, b) { return a.rtt - b.rtt; });
        clockOffsetMs = samples[0].offset;
        clockUncertaintyMs = samples[0].rtt / 2;
      });
    }

    function watchPosition() {
      if (!navigator.geolocation) return;
      navigator.geolocation.watchPosition(
        function (pos) {
          position = {
            latitude: pos.coords.latitude,
            longitude: pos.coords.longitude,
            altitude: typeof pos.coords.altitude === "number" ? pos.coords.altitude : null
          };
        },
        function () { /* a vote without a position still counts toward consensus */ },
        { enableHighAccuracy: true, maximumAge: 15000, timeout: 15000 }
      );
    }

    function connectStream(isSensor) {
      if (stream) { stream.close(); stream = null; }
      var query = "?deviceId=" + encodeURIComponent(deviceId) +
        "&platform=" + encodeURIComponent(platformLabel()) +
        "&sensor=" + (isSensor ? "1" : "0");
      if (position) {
        query += "&lat=" + position.latitude + "&lon=" + position.longitude;
      }
      stream = new EventSource("/v1/mesh/stream" + query);

      // The relay issues a per-device token on connect; mesh writes carry it, since
      // a public page cannot hold the shared relay key.
      stream.addEventListener("hello", function (event) {
        var data = JSON.parse(event.data);
        busToken = data.busToken || null;
        // The hello frame is also a free clock sample from the server.
        if (typeof data.serverTimeMs === "number" && !isFinite(clockUncertaintyMs)) {
          clockOffsetMs = data.serverTimeMs - Date.now();
        }
      });

      // Another phone thinks it heard a shot: check our own microphone and vote.
      stream.addEventListener("wake_classify", function (event) {
        var data = JSON.parse(event.data);
        castVote(data.sessionId);
      });

      stream.addEventListener("response_trigger", function (event) {
        onTrigger(JSON.parse(event.data), "confirmed");
      });

      // Later votes can sharpen the fix — most importantly from a coarse cluster
      // centroid to a real arrival-time solve. That is an update to the same
      // incident, not a second one, and must not be announced as a new alert.
      stream.addEventListener("location_refined", function (event) {
        onTrigger(JSON.parse(event.data), "refined");
      });
    }

    function platformLabel() {
      var ua = navigator.userAgent || "";
      if (/iPhone|iPad|iPod/i.test(ua)) return "ios-web";
      if (/Android/i.test(ua)) return "android-web";
      return "web";
    }

    function recentImpulse() {
      if (!lastImpulse) return null;
      var ageMs = (Date.now() + clockOffsetMs) - lastImpulse.atServerMs;
      return ageMs >= 0 && ageMs <= IMPULSE_MEMORY_MS ? lastImpulse : null;
    }

    function timingUncertaintyMs() {
      if (!audioCtx) return NaN;
      var bufferMs = (BUFFER_SIZE / audioCtx.sampleRate) * 1000;
      // Clock offset error, plus where inside the buffer the callback lands.
      return (clockUncertaintyMs || 25) + bufferMs / 2;
    }

    function castVote(sessionId) {
      var impulse = recentImpulse();
      var body = {
        deviceId: deviceId,
        sessionId: sessionId,
        isGunshot: Boolean(impulse),
        confidence: impulse ? impulse.confidence : 0
      };
      if (position) {
        body.latitude = position.latitude;
        body.longitude = position.longitude;
        if (position.altitude !== null) body.altitude = position.altitude;
      }
      // Only claim an arrival time when we actually heard something — a guessed
      // timestamp would quietly corrupt everyone else's triangulation.
      if (impulse) {
        body.detectedAtMs = impulse.atServerMs;
        body.timingUncertaintyMs = impulse.timingUncertaintyMs;
      }
      busPost("/v1/mesh/votes", body);
    }

    function raiseDetection(impulse) {
      var now = Date.now();
      if (now - lastRaiseAtMs < RAISE_COOLDOWN_MS) return;
      lastRaiseAtMs = now;

      var body = {
        deviceId: deviceId,
        confidence: impulse.confidence,
        detectedAtMs: impulse.atServerMs,
        timingUncertaintyMs: impulse.timingUncertaintyMs
      };
      if (position) {
        body.latitude = position.latitude;
        body.longitude = position.longitude;
        if (position.altitude !== null) body.altitude = position.altitude;
      }
      busPost("/v1/mesh/detections", body);
      render("Impulse detected — asking nearby devices to confirm.");
    }

    function onAudio(event) {
      var samples = event.inputBuffer.getChannelData(0);
      var count = samples.length;
      var sumSquares = 0, peak = 0, peakIndex = 0, clipped = 0;
      for (var i = 0; i < count; i++) {
        var value = samples[i];
        sumSquares += value * value;
        var magnitude = value < 0 ? -value : value;
        if (magnitude > peak) { peak = magnitude; peakIndex = i; }
        if (magnitude >= 0.98) clipped++;
      }
      var rms = Math.sqrt(sumSquares / count);
      var crest = rms > 0 ? peak / rms : 0;
      var clipFraction = clipped / count;

      // Track the background level only on quiet frames, so the event itself can
      // never raise the bar it has to clear.
      if (rms < background * 3) {
        background = (1 - BACKGROUND_ALPHA) * background + BACKGROUND_ALPHA * rms;
      }
      var snr = rms / Math.max(background, 1e-4);

      var impulsive = (peak >= PEAK_MIN && crest >= CREST_MIN && snr >= SNR_MIN) ||
        clipFraction >= CLIP_GATE;
      if (!impulsive) return;

      // Timestamp the impulse itself, not the callback: locate the peak inside the
      // buffer and subtract the samples that follow it.
      var sampleRate = audioCtx.sampleRate;
      var onsetLocalMs = Date.now() - ((count - peakIndex) / sampleRate) * 1000;

      var confidence = Math.min(1, Math.max(0.15,
        0.25 * Math.min(1, peak / 0.9) +
        0.35 * Math.min(1, crest / 12) +
        0.25 * Math.min(1, snr / 25) +
        0.15 * Math.min(1, clipFraction / 0.02)
      ));

      lastImpulse = {
        atServerMs: onsetLocalMs + clockOffsetMs,
        confidence: confidence,
        peak: peak,
        crest: crest,
        clipFraction: clipFraction,
        timingUncertaintyMs: timingUncertaintyMs()
      };
      raiseDetection(lastImpulse);
    }

    function render(note) {
      var blurb = $("idleBlurb");
      if (!enabled) {
        // The blurb above the button already explains the off state; repeating it
        // here just crowds the screen.
        if (blurb) blurb.style.display = "";
        readout("");
        return;
      }
      if (blurb) blurb.style.display = "none";
      var lines = [];
      lines.push("<strong>Listening on this phone.</strong> Detecting sudden, loud impulsive " +
        "sounds and corroborating what nearby devices hear.");
      if (isFinite(clockUncertaintyMs)) {
        lines.push("Clock synced to within " + Math.round(clockUncertaintyMs) + " ms — needed for triangulation.");
      }
      lines.push(position
        ? "Position shared for triangulation."
        : "No position yet — this device can confirm, but cannot help locate.");
      if (lastImpulse) {
        var ago = Math.round(((Date.now() + clockOffsetMs) - lastImpulse.atServerMs) / 1000);
        lines.push("Last impulse " + ago + "s ago (confidence " + lastImpulse.confidence.toFixed(2) + ").");
      }
      if (note) lines.push(note);
      readout(lines.join("<br>"));
    }

    function start() {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        readout("This browser cannot access the microphone.");
        return;
      }
      navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false }
      }).then(function (mediaStream) {
        micStream = mediaStream;
        var Ctx = window.AudioContext || window.webkitAudioContext;
        audioCtx = new Ctx();
        if (audioCtx.state === "suspended") audioCtx.resume();
        micSource = audioCtx.createMediaStreamSource(mediaStream);
        processor = audioCtx.createScriptProcessor(BUFFER_SIZE, 1, 1);
        processor.onaudioprocess = onAudio;
        micSource.connect(processor);
        // Safari will not run the processor unless it reaches a destination; a muted
        // gain node keeps the graph alive without playing anything back.
        var mute = audioCtx.createGain();
        mute.gain.value = 0;
        processor.connect(mute);
        mute.connect(audioCtx.destination);

        enabled = true;
        sensorListening = true;
        refreshStatusPill();
        $("sensorToggle").textContent = "Turn off listening mode";
        watchPosition();
        syncClock().then(function () {
          announceSensorState(true);
          render();
        });
      }).catch(function () {
        readout("Microphone permission is needed for listening mode.");
      });
    }

    function announceSensorState(isSensor) {
      busPost("/v1/mesh/sensor-state", {
        deviceId: deviceId,
        sensor: isSensor,
        latitude: position ? position.latitude : undefined,
        longitude: position ? position.longitude : undefined
      });
    }

    function stop() {
      enabled = false;
      if (processor) { processor.disconnect(); processor.onaudioprocess = null; processor = null; }
      if (micSource) { micSource.disconnect(); micSource = null; }
      if (micStream) { micStream.getTracks().forEach(function (t) { t.stop(); }); micStream = null; }
      if (audioCtx) { audioCtx.close(); audioCtx = null; }
      lastImpulse = null;
      sensorListening = false;
      refreshStatusPill();
      $("sensorToggle").textContent = "Turn on listening mode";
      announceSensorState(false);
      render();
    }

    return {
      // The chat and status-report tabs authenticate with the same per-device token.
      authHeaders: function () {
        return busToken ? { authorization: "Bus " + busToken } : {};
      },
      init: function (triggerHandler) {
        onTrigger = triggerHandler || function () {};
        deviceId = deviceIdentity();
        // Receive confirmed alerts even when not listening: everyone in the building
        // needs the response, whether or not their microphone is helping detect it.
        syncClock().then(function () { connectStream(false); });
        $("sensorToggle").addEventListener("click", function () {
          if (enabled) stop(); else start();
        });
        render();
        setInterval(function () { if (enabled) render(); }, 4000);
      }
    };
  })();

  Sensor.init(function (trigger, kind) {
    // A confirmed detection: surface it immediately rather than waiting for the
    // next five-second poll.
    var estimate = trigger.estimate || {};
    var detail;
    if (kind === "refined") {
      detail = estimate.method === "tdoa_multilateration"
        ? "Location narrowed down from arrival times across " + estimate.contributingNodes + " devices."
        : "Location updated as more devices reported in.";
    } else {
      var nodes = (trigger.confirmedByNodes || []).length;
      detail = "Confirmed by " + (nodes ? nodes + " devices" : "multiple devices") + " nearby.";
    }
    if (estimate.floorOffset) {
      detail += " Approximately " + estimate.floorOffset + " floor(s) above the lowest device.";
    }
    banner(detail);
    refresh();
  });
  setStatusPill(false);
  renderChat();
  buildingOptions();
  refresh();
  setInterval(refresh, 5000);
})();
</script>
</body>
</html>`;
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

  if (req.method === "GET" && (pathname === "/app" || pathname === "/opius")) {
    text(res, 200, opiusAppHtml(), "text/html; charset=utf-8");
    return;
  }

  // Shared time base. Browser sensor nodes have no common clock with each other,
  // so each one measures its offset to this server (NTP style, keeping the
  // lowest-round-trip sample) and reports detection times on the server's clock.
  // Without this, arrival-time triangulation across phones is meaningless.
  if (req.method === "GET" && pathname === "/v1/time") {
    json(res, 200, { ok: true, serverTimeMs: Date.now() });
    return;
  }

  // Cross-platform mesh bus (Android + iOS interoperate over HTTP/SSE).
  if (req.method === "GET" && pathname === "/v1/mesh/stream") {
    handleMeshStream(req, res, url.searchParams);
    return;
  }
  if (req.method === "GET" && pathname === "/v1/mesh/status") {
    json(res, 200, {
      ok: true,
      online: meshDevices.size,
      sensors: meshSensorCount(),
      required: meshRequiredConfirmations(),
      devices: [...meshDevices.entries()].map(([deviceId, entry]) => ({
        deviceId,
        platform: entry.platform,
        sensor: Boolean(entry.sensor),
        latitude: entry.latitude,
        longitude: entry.longitude
      })),
      sessions: [...detectionSessions.values()].map((session) => ({
        sessionId: session.sessionId,
        sourceDeviceId: session.sourceDeviceId,
        triggered: Boolean(session.triggered),
        confirmations: [...session.votes.values()].filter((v) => v.isGunshot).length,
        votes: session.votes.size,
        estimate: session.lastEstimate || null
      }))
    });
    return;
  }

  if (req.method === "POST" && pathname === "/v1/mesh/detections") {
    await handleMeshDetection(req, res);
    return;
  }
  if (req.method === "POST" && pathname === "/v1/mesh/votes") {
    await handleMeshVote(req, res);
    return;
  }
  if (req.method === "POST" && pathname === "/v1/mesh/sensor-state") {
    await handleMeshSensorState(req, res);
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
