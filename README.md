# payment-orchestration

[![CI](https://github.com/amritshukla001/payment-orchestration/actions/workflows/ci.yml/badge.svg)](https://github.com/amritshukla001/payment-orchestration/actions/workflows/ci.yml)

A backend system-design playground — a portfolio project built to
deliberately exercise as broad a range of real HLD and LLD concepts as
possible, not to showcase one pattern. A payments-processing pipeline is
the vehicle here, not the point: distributed-transaction patterns,
event-driven architecture, and classic OOP design patterns are what it's
actually about, applied to original, non-proprietary domain logic.

Structurally, a payment moves through a chain of independent services —
fraud check, funds authorization, ledger posting, settlement,
notification — coordinated by a saga orchestrator. That's one pattern
among several in play (see [Concepts & patterns demonstrated](#concepts--patterns-demonstrated)
below) rather than the whole story. If any step fails, previously
completed steps are unwound via compensating actions instead of leaving
the system in a half-done state.

## Architecture

```
Client → Payment API (+ outbox) → Kafka: payment.events
                                          │
                                   Saga Orchestrator ⇄ Kafka: payment.commands
                                          │                    │            │            │
                          ┌───────────────┼────────────┬───────┴────┬───────┘            │
                          ▼               ▼             ▼            ▼                   ▼
                    Fraud Service   Funds Auth    Ledger Service  (Settlement,      (Notification,
                                                                    planned)          planned)
```

- **payment-api** — REST intake. Validates a request, persists it, and
  publishes an event via the transactional outbox pattern (write + outbox
  row in one DB transaction, published by a separate poller) — avoids the
  dual-write problem between the database and Kafka.
- **saga-orchestrator** — owns the payment's state machine. Consumes domain
  events on `payment.events`, decides the next step, and issues commands on
  `payment.commands`. Keeps the whole lifecycle in one place instead of
  scattering it across every service's event handlers.
- **fraud-service** — consumes `CHECK_FRAUD` commands, evaluates independent
  rule strategies (`FraudRule` implementations, Strategy pattern), publishes
  the verdict back onto `payment.events`.
- **funds-auth-service** — a mock bank. Lazily provisions accounts on first
  sight, reserves/releases funds with optimistic locking, and already
  implements the `RELEASE_FUNDS` handler ahead of the compensation logic
  that will call it.
- **ledger-service** — append-only double-entry ledger. Posts the HOLD leg
  (debit payer, credit a fixed suspense account) when funds are authorized;
  settlement-service (a later phase) will post the matching FINAL leg
  (debit suspense, credit payee) to complete the capture. Nothing here is
  ever updated — a correction would be a new offsetting entry, never a
  mutation of history.
- **common** — shared event/command contracts (`EventEnvelope`, `PaymentState`,
  event and command records) used by every service.

Every event carries a stable `eventId` (`EventEnvelope`), and every consumer
checks its own `processed_events` table before acting — Kafka only guarantees
at-least-once delivery, so idempotent processing is what makes redelivery
safe rather than a source of duplicate work.

Each service owns its own Postgres database (`payflow`, `orchestrator`,
`fraud`, `fundsauth`, `ledger`), even though they currently share one
container for local-dev convenience — mirrors "database per service"
without needing a separate Postgres instance per service.

## Concepts & patterns demonstrated

**Distributed systems / HLD**
- **Saga (orchestration, not choreography)** — a central orchestrator owns
  the state machine rather than scattering the payment lifecycle across
  every service's event handlers; see the tradeoff this was chosen over.
- **Transactional Outbox** — write + outbox row in one DB transaction,
  published by a separate poller, avoiding the dual-write problem between
  a database and Kafka.
- **Idempotent Consumer** — every consumer checks its own `processed_events`
  table before acting, since Kafka only guarantees at-least-once delivery.
- **Database per service** — five services, five separate Postgres
  databases, no shared schema.
- **Optimistic locking** — `@Version` on `Payment`, `PaymentSagaState`, and
  `Account` guards concurrent writers without pessimistic locks.
- **Schema evolution via versioned migrations** — a real schema change
  (`payment_saga_state` gaining `payee_account`) shipped as a new Flyway
  `V2` migration, never editing the applied `V1`.
- **Consistency tradeoffs** — strong (ACID) consistency within each
  service's own database, eventual consistency across the saga as a whole.

**Classic OOP / LLD (Gang of Four)**
- **Strategy** — fraud-service's `FraudRule` interface, with
  `HighValueThresholdRule` and `PositiveAmountRule` as independent
  `@Component` beans Spring autowires into the engine's rule list.
- **Mediator** — the saga orchestrator: fraud-service and funds-auth-service
  never call each other directly, only the orchestrator.
- **Command** — `CheckFraudCommand`, `AuthorizeFundsCommand`, etc. are
  requests encapsulated as objects; `ReleaseFundsCommand` is the literal
  *undo* counterpart to `AuthorizeFundsCommand`.
- **Factory Method** — static factories on result types (`Verdict.approve()` /
  `.reject()`), and Spring's `ConsumerFactory`/`ProducerFactory` beans.
- **Repository** — every `JpaRepository` interface.
- **Singleton** — every Spring-managed `@Component`/`@Service` bean.

Not every pattern fits everywhere, and forcing one in where it doesn't
belong would defeat the point — some (Observer, Decorator, Template Method)
are deliberately not used here; where a pattern is architecturally implied
but not hand-rolled (Kafka pub/sub *is* an Observer relationship, but
nothing here reimplements `Observer`/`Listener` in Java), that distinction
is worth being explicit about rather than overclaiming it.

## Status

**Built:** `payment-api`, `saga-orchestrator`, `fraud-service`,
`funds-auth-service`, `ledger-service` — a payment can flow from intake
through fraud approval, funds authorization, and a real double-entry ledger
posting to `LEDGER_POSTED`, verified end-to-end against real Kafka and
Postgres, not just compiled. All rejection branches (fraud threshold,
insufficient funds) verified too.

**Not built yet:** `settlement-service`, `notification-service`,
compensation/rollback wiring for mid-saga failures (funds-auth-service
already implements the `RELEASE_FUNDS` handler ahead of this), and a
React + AG Grid dashboard.

**Also on the roadmap** — the difference between a demo and something that
reads as production-grade:
- **Security** — API-key auth (every endpoint is wide open right now)
- **Observability** — metrics (Micrometer/Prometheus) + distributed tracing across the 4-service saga
- **Resilience** — circuit breaker + retry policy (Resilience4j); Kafka redelivery gives retries "for free" today but there's no deliberate backoff policy
- **API documentation** — OpenAPI/Swagger on payment-api
- **Containerization** — a Dockerfile per service + a compose file that builds from source, so running this doesn't require a local Java/Maven install
- **Architecture Decision Records** — short docs on why Kafka over Pulsar, why orchestration over choreography
- **Schema evolution discipline** — every future schema change ships as a new Flyway migration, never editing one already applied
- **Load testing** — k6/Gatling against payment-api, to turn the design doc's capacity estimate into a measured number

## Testing

Tests are written alongside each service as it's built, not deferred to the
end — every phase since `fraud-service` has shipped with its own tests.

- **Unit tests** (JUnit 5 + Mockito) for pure logic and mockable
  collaborators: fraud-service's `FraudRule` strategies and rule engine,
  funds-auth-service's `MockBankLedger` (reserve/release/insufficient-funds),
  ledger-service's `DoubleEntryLedger`, and — the most important one —
  saga-orchestrator's `PaymentEventListener`, covering every state
  transition (`INITIATED → FRAUD_CHECKED → AUTHORIZED → LEDGER_POSTED`,
  both `FAILED` branches, idempotent redelivery, and unknown-payment
  handling). That last suite is the **regression pack** for the saga: every
  scenario in it mirrors something that was previously verified by hand
  with curl + psql, now automated so a future change can't silently break
  it without CI catching it.
- **Integration test** (Testcontainers — real Postgres + real Kafka, not
  mocks) for payment-api: posts a real payment over HTTP, confirms it's
  persisted, confirms the outbox actually published to a live Kafka topic,
  and confirms the `Idempotency-Key` retry path returns the same payment
  instead of creating a duplicate.

Run everything: `mvn test` from the repo root (requires Docker for the
Testcontainers-based payment-api test — this needs a Docker Engine version
Testcontainers is tested against; a very new Docker Desktop can trip a
known compatibility issue between Testcontainers and its bundled
docker-java client, in which case CI is the source of truth for that one
test rather than your local machine).

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
cd funds-auth-service && mvn spring-boot:run   # :8084
cd ledger-service && mvn spring-boot:run       # :8085
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

Check the actual ledger posting:

```bash
docker exec payflow-postgres psql -U payflow -d ledger \
  -c "SELECT payment_id, debit_account, credit_account, amount_cents, posting_type FROM ledger_entries;"
```

## Tech stack

Java 21 · Spring Boot 3.3.4 · Maven (multi-module) · PostgreSQL 16 · Flyway ·
Apache Kafka 3.7 (KRaft) · Kafka UI · Docker Compose · Hibernate / Spring Data JPA
