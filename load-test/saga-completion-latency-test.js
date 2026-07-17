// Complements payments-throughput-test.js. That script only measures how
// fast payment-api can *accept* a payment (202, immediately, by async
// design). This script measures how long the whole saga actually takes to
// reach a terminal state, by polling GET /payments/{id} after each POST.
// Runs at a light rate (5/s, well under the documented 20/s average) since
// its purpose is measuring the pipeline's own latency, not stress-testing
// intake -- run payments-throughput-test.js for that.
// See load-test/README.md for how to run this and how to read the output.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { uuidv4, randomAmountCents } from './lib.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.PAYFLOW_API_KEY || 'local-dev-api-key-change-me';

const POLL_INTERVAL_SECONDS = 0.25;
const MAX_WAIT_SECONDS = 15;
const TERMINAL_STATES = ['SETTLED', 'FAILED', 'COMPENSATED'];

const sagaCompletionSeconds = new Trend('saga_completion_seconds', true);
const sagaCompletionTimeouts = new Counter('saga_completion_timeouts');

export const options = {
  scenarios: {
    completion_latency: {
      executor: 'constant-arrival-rate',
      rate: 5,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
};

export default function () {
  const payload = JSON.stringify({
    payerAccount: uuidv4(),
    payeeAccount: uuidv4(),
    amountCents: randomAmountCents(),
    currency: 'USD',
  });

  const postRes = http.post(`${BASE_URL}/payments`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
      'Idempotency-Key': uuidv4(),
    },
  });

  const accepted = check(postRes, { 'status is 202': (r) => r.status === 202 });
  if (!accepted) {
    return;
  }

  const paymentId = JSON.parse(postRes.body).id;
  const start = Date.now();
  let finalState = null;
  let elapsedMs = null;

  while (Date.now() - start < MAX_WAIT_SECONDS * 1000) {
    sleep(POLL_INTERVAL_SECONDS);

    const getRes = http.get(`${BASE_URL}/payments/${paymentId}`, {
      headers: { 'X-API-Key': API_KEY },
    });
    if (getRes.status !== 200) {
      continue;
    }

    const state = JSON.parse(getRes.body).state;
    if (TERMINAL_STATES.includes(state)) {
      finalState = state;
      elapsedMs = Date.now() - start;
      break;
    }
  }

  if (elapsedMs !== null) {
    // Trend(..., true) expects raw values in milliseconds -- it's what
    // makes k6 auto-format the summary as e.g. "1.2s" instead of "1200ms".
    sagaCompletionSeconds.add(elapsedMs, { finalState });
  } else {
    sagaCompletionTimeouts.add(1);
  }

  check(elapsedMs, {
    'reached a terminal state within timeout': (v) => v !== null,
  });
}
