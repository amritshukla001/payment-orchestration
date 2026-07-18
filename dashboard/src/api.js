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

export function fetchSagas() {
  return getJson(`${GATEWAY_URL}/api/sagas`);
}

export function fetchLedgerEntries(paymentId) {
  return getJson(`${GATEWAY_URL}/api/ledger/${paymentId}`);
}

export function fetchNotifications(paymentId) {
  return getJson(`${GATEWAY_URL}/api/notifications/${paymentId}`);
}
