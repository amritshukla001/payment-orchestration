// Each service owns its own database and its own port -- there is no API
// gateway in front of them (an explicitly deferred roadmap item), so the
// dashboard talks to saga-orchestrator, ledger-service, and
// notification-service directly, each on its own origin.
const ORCHESTRATOR_URL = "http://localhost:8082";
const LEDGER_URL = "http://localhost:8085";
const NOTIFICATION_URL = "http://localhost:8087";

async function getJson(url) {
  const res = await fetch(url);
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
