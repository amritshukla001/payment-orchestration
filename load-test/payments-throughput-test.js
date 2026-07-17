// Turns the design doc's capacity estimate into a measured number:
// average throughput 20 payments/sec, peak (5x) 100 payments/sec.
// See load-test/README.md for how to run this and how to read the output.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4, randomAmountCents } from './lib.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.PAYFLOW_API_KEY || 'local-dev-api-key-change-me';

export const options = {
  scenarios: {
    average_load: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 100,
      exec: 'postPayment',
      startTime: '0s',
    },
    peak_load: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 150,
      maxVUs: 300,
      exec: 'postPayment',
      // Starts after average_load's 2m duration plus a buffer so the two
      // scenarios' results don't overlap and confound each other.
      startTime: '2m10s',
    },
  },
  thresholds: {
    // The only defensible pass/fail gate given no documented latency SLA --
    // a rising error rate is what "stopped sustaining this rate" looks
    // like. Latency percentiles are reported, not gated, since there's no
    // target in the design doc to assert them against.
    http_req_failed: ['rate<0.01'],
  },
};

export function postPayment() {
  const payload = JSON.stringify({
    payerAccount: uuidv4(),
    payeeAccount: uuidv4(),
    amountCents: randomAmountCents(),
    currency: 'USD',
  });

  const headers = {
    'Content-Type': 'application/json',
    'X-API-Key': API_KEY,
    'Idempotency-Key': uuidv4(),
  };

  const res = http.post(`${BASE_URL}/payments`, payload, { headers });

  check(res, {
    'status is 202': (r) => r.status === 202,
    'body has id': (r) => {
      try {
        return !!JSON.parse(r.body).id;
      } catch {
        return false;
      }
    },
  });
}
