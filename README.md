# payment-orchestration

A saga-based, event-driven payment orchestration system — a portfolio project
demonstrating the same architecture patterns used in production banking
systems (event-driven microservices, workflow orchestration, idempotent
consumers) applied to original, non-proprietary domain logic.

A payment moves through a chain of independent services — fraud check, funds
authorization, ledger posting, settlement, notification — coordinated by a
saga orchestrator rather than direct service-to-service calls. If any step
fails, previously completed steps are unwound via compensating actions
instead of leaving the system in a half-done state.

## Architecture

```
Client → Payment API (+ outbox) → Kafka: payment.events
                                          │
                                   Saga Orchestrator ⇄ Kafka: payment.commands
                                          │                         │
                          ┌───────────────┼─────────────┬──────────┘
                          ▼               ▼              ▼
                    Fraud Service   Funds Auth      Ledger Service
                                    (planned)         (planned)
```

- **payment-api** — REST intake. Validates a request, persists it, and
  publishes an event via the transactional outbox pattern (write + outbox
  row in one DB transaction, published by a separate poller) — avoids the
  dual-write problem between the database and Kafka.
- **saga-orchestrator** — owns the payment's state machine. Consumes domain
  events on `payment.events`, decides the next step, and issues commands on
  `payment.commands`. Keeps the whole lifecycle in one place instead of
  scattering it across every service's event handlers.
- **fraud-service** — consumes `CHECK_FRAUD` commands, evaluates a threshold
  rule, publishes the verdict back onto `payment.events`.
- **common** — shared event/command contracts (`EventEnvelope`, `PaymentState`,
  event and command records) used by every service.

Every event carries a stable `eventId` (`EventEnvelope`), and every consumer
checks its own `processed_events` table before acting — Kafka only guarantees
at-least-once delivery, so idempotent processing is what makes redelivery
safe rather than a source of duplicate work.

Each service owns its own Postgres database (`payflow`, `orchestrator`,
`fraud`), even though they currently share one container for local-dev
convenience — mirrors "database per service" without needing a separate
Postgres instance per service.

## Status

**Built:** `payment-api`, `saga-orchestrator`, `fraud-service` — a payment
can flow from intake through a fraud-approved or fraud-rejected verdict,
verified end-to-end against real Kafka and Postgres, not just compiled.

**Not built yet:** `funds-auth-service`, `ledger-service`,
`settlement-service`, `notification-service`, compensation/rollback wiring
for mid-saga failures, and a React + AG Grid dashboard.

## Running it locally

Requires Docker Desktop and Java 21 + Maven.

```bash
# 1. Start infra (Postgres, Kafka, Kafka UI)
docker compose up -d

# 2. Build everything
mvn -DskipTests install

# 3. Start each service (separate terminals)
cd payment-api && mvn spring-boot:run          # :8080
cd saga-orchestrator && mvn spring-boot:run    # :8082
cd fraud-service && mvn spring-boot:run        # :8083
```

Kafka UI is at http://localhost:8081 for browsing topics/messages directly.

### Try it

```bash
curl -i -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" \
  -d '{"payerAccount":"11111111-1111-1111-1111-111111111111","payeeAccount":"22222222-2222-2222-2222-222222222222","amountCents":2500,"currency":"USD"}'
```

Amounts over $10,000 (`amountCents > 1000000`) get rejected by the fraud
rule — try it to see the saga end in `FAILED` instead.

```bash
curl http://localhost:8080/payments/<id>
curl http://localhost:8080/payments/<id>/timeline
```

Check saga progress directly:

```bash
docker exec payflow-postgres psql -U payflow -d orchestrator \
  -c "SELECT payment_id, state FROM payment_saga_state;"
```

## Tech stack

Java 21 · Spring Boot 3.3.4 · Maven (multi-module) · PostgreSQL 16 · Flyway ·
Apache Kafka 3.7 (KRaft) · Kafka UI · Docker Compose · Hibernate / Spring Data JPA
