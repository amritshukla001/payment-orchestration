// Each service still owns its own database and its own port, but the
// dashboard no longer needs to know any of them -- it talks to the API
// gateway's single origin, which routes each path to the right service.
const GATEWAY_URL = "http://localhost:8088";

// Matches each service's PAYFLOW_API_KEY default (local-dev-api-key-change-me)
// unless overridden -- see dashboard/README.md. A key shipped in frontend JS
// is never a real secret; this demonstrates the auth boundary, not credential
// management.
const API_KEY = import.meta.env.VITE_API_KEY ?? "local-dev-api-key-change-me";

async function getJson(url, { notFoundOk = false } = {}) {
  const res = await fetch(url, { headers: { "X-API-Key": API_KEY } });
  if (notFoundOk && res.status === 404) {
    return null;
  }
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

// Backs the merchant analytics view -- aggregate counts/amounts only,
// same read-model-service projection the grid uses, just grouped instead
// of row-by-row.
export function fetchAnalyticsSummary(days = 7) {
  return getJson(`${GATEWAY_URL}/api/payments/analytics/summary?days=${days}`);
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

// The customer-facing resolution of a CARD payment's step-up pause --
// see payment-engine's StepUpController and Checkout.jsx's AWAITING_STEP_UP handling.
export function confirmStepUp(paymentId) {
  return postJson(`${GATEWAY_URL}/api/payment-engine/${paymentId}/step-up/confirm`);
}

export function declineStepUp(paymentId) {
  return postJson(`${GATEWAY_URL}/api/payment-engine/${paymentId}/step-up/decline`);
}

// The demo lever for funds-auth-service's NetBankingAvailabilityRule --
// mirrors registerUpi's role for UpiDirectoryRule.
export function markBankOutage(bankCode) {
  return postJson(`${GATEWAY_URL}/api/funds-auth/banks/${bankCode}/outage`);
}

export function restoreBank(bankCode) {
  return postJson(`${GATEWAY_URL}/api/funds-auth/banks/${bankCode}/restore`);
}

// One call for the whole detail drawer (payment + ledger entries +
// notifications), where this used to be two separate calls into
// ledger-service and notification-service. `notFoundOk` exists for
// pollUntilTerminal below: a payment-api 202 response comes back before
// the outbox has even published PAYMENT_INITIATED, let alone before
// read-model-service's projection has caught up, so the very first poll
// or two can legitimately 404 -- that's "not projected yet", not an error.
export function fetchPaymentDetail(paymentId, { notFoundOk = false } = {}) {
  return getJson(`${GATEWAY_URL}/api/payments/${paymentId}`, { notFoundOk });
}

// AI-generated incident summary for a COMPENSATED payment, served by
// payment-engine from its own event-sourced transition log. Generated once
// and cached server-side; falls back to a deterministic template when the
// Claude API is unavailable (response.source says which).
export function fetchPaymentEngineSummary(paymentId) {
  return getJson(`${GATEWAY_URL}/api/payment-engine/${paymentId}/summary`);
}

const TERMINAL_STATES = new Set(["SETTLED", "FAILED", "COMPENSATED"]);

// The checkout page's only way to know how a payment turned out -- there's
// no websocket/SSE push in this system by design (see the root README's
// dashboard section), so this polls the same read-model projection the
// ops console grid does, just for one payment instead of all of them.
// Resolves with the last poll's detail once a terminal state is reached,
// or once maxWaitMs elapses (the saga is almost always done in well under
// a second, so this is a generous ceiling, not a tuned SLA).
export async function pollUntilTerminal(paymentId, { intervalMs = 500, maxWaitMs = 20000, onUpdate } = {}) {
  const deadline = Date.now() + maxWaitMs;
  for (;;) {
    const detail = await fetchPaymentDetail(paymentId, { notFoundOk: true });
    if (detail) {
      onUpdate?.(detail);
      if (TERMINAL_STATES.has(detail.payment.state) || Date.now() >= deadline) {
        return detail;
      }
    } else if (Date.now() >= deadline) {
      throw new Error(`${paymentId} never appeared in the read model within ${maxWaitMs}ms`);
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}
