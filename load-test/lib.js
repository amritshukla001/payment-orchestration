// Shared between payments-throughput-test.js and saga-completion-latency-test.js.

// Comfortably below the $9,000 settlement-decline / $10,000 fraud-reject
// thresholds (SettlementRiskCheck / HighValueThresholdRule), so payloads
// built from this land on the happy path rather than a failure path.
export const MIN_AMOUNT_CENTS = 100; // $1
export const MAX_AMOUNT_CENTS = 500_000; // $5,000

// Local RFC 4122 v4 generator -- avoids a runtime dependency on
// jslib.k6.io being reachable during the test run.
export function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function randomAmountCents() {
  return Math.floor(Math.random() * (MAX_AMOUNT_CENTS - MIN_AMOUNT_CENTS + 1)) + MIN_AMOUNT_CENTS;
}
