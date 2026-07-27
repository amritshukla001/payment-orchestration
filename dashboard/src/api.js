// Each service still owns its own database and its own port, but the
// dashboard no longer needs to know any of them -- it talks to the API
// gateway's single origin, which routes each path to the right service.
const GATEWAY_URL = "http://localhost:8088";

// Matches each service's PAYFLOW_API_KEY default (local-dev-api-key-change-me)
// unless overridden -- see dashboard/README.md. A key shipped in frontend JS
// is never a real secret; this demonstrates the auth boundary, not credential
// management.
const API_KEY = import.meta.env.VITE_API_KEY ?? "local-dev-api-key-change-me";

async function getJson(url) {
  const res = await fetch(url, { headers: { "X-API-Key": API_KEY } });
  if (!res.ok) {
    throw new Error(`${url} -> HTTP ${res.status}`);
  }
  return res.json();
}

async function postJson(url, body, extraHeaders = {}) {
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "X-API-Key": API_KEY,
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...extraHeaders,
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`${url} -> HTTP ${res.status}${text ? `: ${text}` : ""}`);
  }
  return res.status === 204 ? null : res.json();
}

// Backed by read-model-service's CQRS projection, not payment-engine's
// own table -- one denormalized view built from payment.events.
export function fetchPayments() {
  return getJson(`${GATEWAY_URL}/api/payments`);
}

// Kicks off a real payment through payment-api's transactional outbox --
// the same POST /payments the curl walkthrough in the root README uses,
// just from the dashboard instead of a terminal.
export function createPayment(request) {
  const idempotencyKey = crypto.randomUUID();
  return postJson(`${GATEWAY_URL}/payments`, request, { "Idempotency-Key": idempotencyKey });
}

// The three demo levers compliance-service exposes -- API-only by design
// (see compliance-service's ComplianceController), surfaced here so a demo
// doesn't need a terminal open.
export function flagAccount(accountId) {
  return postJson(`${GATEWAY_URL}/api/compliance/accounts/${accountId}/flag`);
}

export function verifyAccount(accountId) {
  return postJson(`${GATEWAY_URL}/api/compliance/accounts/${accountId}/verify`);
}

export function registerUpi(accountId) {
  return postJson(`${GATEWAY_URL}/api/compliance/upi/${accountId}/register`);
}

// One call for the whole detail drawer (payment + ledger entries +
// notifications), where this used to be two separate calls into
// ledger-service and notification-service.
export function fetchPaymentDetail(paymentId) {
  return getJson(`${GATEWAY_URL}/api/payments/${paymentId}`);
}

// AI-generated incident summary for a COMPENSATED payment, served by
// payment-engine from its own event-sourced transition log. Generated once
// and cached server-side; falls back to a deterministic template when the
// Claude API is unavailable (response.source says which).
export function fetchPaymentEngineSummary(paymentId) {
  return getJson(`${GATEWAY_URL}/api/payment-engine/${paymentId}/summary`);
}
