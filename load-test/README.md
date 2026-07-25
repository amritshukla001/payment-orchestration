# Load testing

Turns the [HLD/LLD design doc](../RESUME.md)'s capacity estimate into a
measured number: **20 payments/sec average, 100/sec peak (5x)**. There's no
stated latency SLA in that doc — only "a slow downstream must not block
intake" — so these scripts report latency, they don't assert a target that
was never written down.

Requires [k6](https://k6.io) (`brew install k6`) and the full stack running
locally (`docker compose up -d` from the repo root, then all 7 services per
the main [README](../README.md#running-it-locally)).

## `payments-throughput-test.js`

The primary script. Hits `POST /payments` only, at exactly the two
documented rates, back to back:

- `average_load` — 20 req/s for 2 minutes
- `peak_load` — 100 req/s for 1 minute

```bash
k6 run load-test/payments-throughput-test.js
```

Payloads use random payer/payee UUIDs (funds-auth-service lazily provisions
a $100,000 balance for any account on first sight — no seeding needed) and
`amountCents` randomized $1–$5,000, comfortably below the $9,000
settlement-decline and $10,000 fraud-reject thresholds, so this measures
sustained *happy-path* throughput rather than failure-path behavior.

Only `http_req_failed` (< 1%) is gated as pass/fail. Read `http_req_duration`
(p50/p95/p99) in the summary output — that's the actual "measured number"
this exists to produce.

## `saga-completion-latency-test.js`

`payments-throughput-test.js` only measures how fast the API *accepts* a
payment (`202`, immediately — that's the point of the async outbox design).
This script measures how long the *whole saga* takes to reach a terminal
state (`SETTLED`, `FAILED`, or `COMPENSATED`): POST, then poll
`GET /payments/{id}` every 250ms up to a 15s timeout. Runs at a light 5 req/s
— well under the documented average — since it's measuring pipeline
latency, not stress-testing intake.

```bash
k6 run load-test/saga-completion-latency-test.js
```

Reports a custom `saga_completion_seconds` trend metric, plus a
`saga_completion_timeouts` counter for any payment that never reached a
terminal state within 15s (a sign the pipeline is backing up, not something
expected to fire at 5 req/s).

## Overriding the target

Both scripts default to `http://localhost:8080` and the default local API
key. Override either via env vars:

```bash
BASE_URL=http://localhost:8080 PAYFLOW_API_KEY=your-key k6 run load-test/payments-throughput-test.js
```

## Not part of CI

`.github/workflows/ci.yml` only runs `mvn -B clean verify` — these scripts
are a manual, local tool. Load tests are slow and resource-heavy in a way
that doesn't belong gating every PR, and they need the full docker-compose
stack plus all 7 services actually running, which CI doesn't set up.
