// Each service owns its own database and its own port -- there is no API
// gateway in front of them (an explicitly deferred roadmap item), so the
// dashboard talks to saga-orchestrator, ledger-service, and
// notification-service directly, each on its own origin.
const ORCHESTRATOR_URL = "http://localhost:8082";
const LEDGER_URL = "http://localhost:8085";
const NOTIFICATION_URL = "http://localhost:8087";

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

export function fetchSagas() {
  return getJson(`${ORCHESTRATOR_URL}/api/sagas`);
}

export function fetchLedgerEntries(paymentId) {
  return getJson(`${LEDGER_URL}/api/ledger/${paymentId}`);
}

export function fetchNotifications(paymentId) {
  return getJson(`${NOTIFICATION_URL}/api/notifications/${paymentId}`);
}
