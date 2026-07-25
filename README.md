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
Client → Payment API (+ outbox) → Kafka: payment.events ⇄ Notification Service
                                          │                  (passive observer,
                                   Saga Orchestrator          no command needed)
                                          ⇄ Kafka: payment.commands
                                          │           │            │            │           │
                     ┌────────────────────┼───────────┼────────────┴───┬────────┴───┬───────┘
                     ▼                    ▼           ▼                 ▼           ▼
              Compliance Service   Fraud Service  Funds Auth        Ledger        Settlement
                                                                     Service        Service
                                                                                      │
                                    (happy path loops back with POST_FINAL_LEDGER)
```

**Compensation path** (when settlement declines after funds were already
authorized — see below): `SETTLEMENT_DECLINED → COMPENSATING → REVERSE_LEDGER
→ LEDGER_REVERSED → RELEASE_FUNDS → FUNDS_RELEASED → COMPENSATED`, undoing
steps in the reverse order they were applied.

- **payment-api** — REST intake. Validates a request, persists it, and
  publishes an event via the transactional outbox pattern (write + outbox
  row in one DB transaction, published by a separate poller) — avoids the
  dual-write problem between the database and Kafka.
- **saga-orchestrator** — owns the payment's state machine. Consumes domain
  events on `payment.events`, decides the next step, and issues commands on
  `payment.commands`. Keeps the whole lifecycle in one place instead of
  scattering it across every service's event handlers.
- **compliance-service** — the first gate a payment passes through (see
  [Compliance & Payment Methods](#compliance--payment-methods)): consumes
  `CHECK_COMPLIANCE` commands, evaluates independent rule strategies
  (`ComplianceRule` implementations, the same Strategy pattern as
  fraud-service), records an AML-style regulatory report for high-value
  amounts regardless of verdict, and publishes the verdict back onto
  `payment.events`.
- **fraud-service** — consumes `CHECK_FRAUD` commands, evaluates independent
  rule strategies (`FraudRule` implementations, Strategy pattern), publishes
  the verdict back onto `payment.events`.
- **funds-auth-service** — a mock bank. Lazily provisions accounts on first
  sight, reserves/releases funds with optimistic locking. Its `RELEASE_FUNDS`
  handler credits the account back and publishes `FUNDS_RELEASED` — the
  final step of compensation.
- **ledger-service** — append-only double-entry ledger. Posts the HOLD leg
  (debit payer, credit a fixed suspense account) when funds are authorized,
  the FINAL leg (debit suspense, credit payee) once settlement confirms
  capture, or a REVERSAL leg (debit suspense, credit payer — the exact
  inverse of HOLD) if settlement declines instead. Nothing here is ever
  updated — a correction is always a new offsetting entry, never a
  mutation of history.
- **settlement-service** — confirms capture: records its own `settlements`
  row (distinct from the ledger's postings) and publishes `PAYMENT_SETTLED`.
  The orchestrator marks the payment `SETTLED` immediately on that event —
  terminal from the payer/payee's perspective — then separately tells
  ledger-service to post the FINAL leg as a bookkeeping step that follows
  behind rather than gates the terminal state, mirroring how real
  settlement and ledger close can lag each other slightly. Amounts between
  $9,000–$10,000 get declined instead (`SettlementRiskCheck`) — a
  realistic payments scenario where the issuer clears authorization but
  declines at capture — which is the only point in this saga where
  compensation has anything real to undo.
- **notification-service** — architecturally the odd one out: it subscribes
  directly to `payment.events` for `PAYMENT_SETTLED`/`PAYMENT_FAILED`/
  `PAYMENT_COMPENSATED` rather than reacting to an orchestrator-issued
  command, since nothing depends on a notification succeeding. Notifies
  both payer and payee on success; payer only on failure or compensation.
  Also publishes a `NOTIFICATION_SENT` event after each one purely for
  [read-model-service](#cqrs-read-model) — the one thing it produces onto
  the bus, since nothing else in the saga reacts to it.
- **read-model-service** — the [CQRS read model](#cqrs-read-model): a pure
  Observer over `payment.events`, projecting a denormalized view for the
  dashboard, asynchronously consistent with and decoupled from every other
  service's own database.
- **common** — shared event/command contracts (`EventEnvelope`, `PaymentState`,
  event and command records) used by every service.

Every event carries a stable `eventId` (`EventEnvelope`), and every consumer
checks its own `processed_events` table before acting — Kafka only guarantees
at-least-once delivery, so idempotent processing is what makes redelivery
safe rather than a source of duplicate work.

Each service owns its own Postgres database (`payflow`, `orchestrator`,
`fraud`, `fundsauth`, `ledger`, `settlement`, `notification`, `readmodel`,
`compliance`), even though they currently share one container for local-dev
convenience — mirrors "database per service" without needing a separate
Postgres instance per service.

## Compensation

Every prior phase's `FAILED` path happened *before* anything was reserved —
fraud rejects before funds are touched, insufficient funds means nothing
was ever reserved. There was nothing to compensate, which would have made
the Saga pattern's actual reason for existing untested. So settlement-service
was given a real decline scenario (`SettlementRiskCheck`, $9,000–$10,000) —
the issuer clears authorization but declines at capture, a genuine payments
scenario — creating the one point in this saga where compensation has
something real to undo.

The sequence undoes the saga's steps in **reverse order** of how they were
originally applied:

1. `SETTLEMENT_DECLINED` → orchestrator sets state to `COMPENSATING`,
   issues `REVERSE_LEDGER`.
2. ledger-service posts a REVERSAL entry (debit suspense, credit payer —
   the exact inverse of the original HOLD) → publishes `LEDGER_REVERSED`.
3. Orchestrator issues `RELEASE_FUNDS` (state stays `COMPENSATING` — this
   is the second of two compensating actions, not the last step yet).
4. funds-auth-service credits the payer's account back → publishes
   `FUNDS_RELEASED`.
5. Orchestrator sets state to `COMPENSATED` (terminal), publishes
   `PAYMENT_COMPENSATED` → notification-service notifies the payer.

Verified end-to-end: a $9,500 payment reaches `COMPENSATED` with the
ledger's HOLD and REVERSAL entries exactly offsetting, the payer's account
balance restored to its exact starting value, the funds reservation marked
`RELEASED`, and no `settlements` row ever created.

## Event Sourcing

The saga aggregate used to be the opposite of event-sourced:
`PaymentSagaState.advanceTo()` mutated a current-value `state` column in
place (`payment_saga_state`, guarded by `@Version` optimistic locking).
Kafka's `payment.events` carries facts *between* services but was never
retained/replayable as saga-orchestrator's own system of record — a
restart with an empty database couldn't have rebuilt `state` from it.

Now `payment_saga_events` is the actual system of record: one immutable
row per transition (`sequence_number, event_type, to_state, occurred_at`),
never updated or deleted — the same append-only discipline
`ledger-service`'s `ledger_entries` already uses. `payer_account`,
`payee_account`, `amount_cents`, and `currency` are only ever populated on
the `sequence_number = 0` (`PAYMENT_INITIATED`) row, since nothing else
about a payment changes after that. `PaymentSagaAggregate` — the same
shape `PaymentSagaState` used to be — is no longer persisted at all; it's
a disposable projection reconstructed by `PaymentSagaAggregate.replay()`,
folding the ordered event log into "what's true right now." Every decision
`PaymentEventListener` makes is unchanged — it's the exact same state
machine — only *how* each resulting fact gets stored changed, from
"mutate + save one row" to "append one new row."

This is a full replay on every read (`SagaEventStore.load()`/`.loadAll()`),
not a `DISTINCT ON`-style optimized query — a deliberate choice. This
aggregate's own read path (`GET /api/sagas`) doesn't need to be the fast
one; [read-model-service](#cqrs-read-model) already exists as the actual
scalable, denormalized read path the dashboard uses. Event Sourcing on the
write side and CQRS on the read side turn out to be a natural pair: one
gives the aggregate a true, replayable history; the other gives reads a
purpose-built, fast projection — neither needs to compromise for the
other's concern.

`@Version` optimistic locking is gone from this aggregate for the same
reason it was mostly a formality before: Kafka's per-`paymentId`
partition-key ordering already guarantees this listener never processes
two events for the same payment concurrently. What replaces it is a
`UNIQUE (payment_id, sequence_number)` constraint on `payment_saga_events`
— a safety net against an accidental double-append, not a contended path.

## AI Compensation Summaries

When a payment ends in `COMPENSATED`, saga-orchestrator can turn its own
event-sourced transition log into a plain-English incident summary —
`GET /api/sagas/{id}/summary`, surfaced in the dashboard drawer as a
"Generate summary" button on compensated payments. The pattern being
demonstrated is **LLM explains, never decides**: every fraud, funds, and
settlement decision in the saga stays fully deterministic; the model
(Google's Gemini API, called via a plain `RestClient` — chosen because its
free tier needs no billing setup) only narrates a history that is already
final. Three deliberate constraints keep it production-shaped:

- **Out of the hot path.** The LLM is never called from a Kafka listener —
  generation is on-demand, triggered by a human clicking a button. A slow
  or down LLM cannot slow a payment by construction.
- **Generated once, cached forever.** The first request per payment writes
  `payment_saga_summaries` (a `V4` migration); every later request — and
  every repeat button click — serves the stored row. LLM cost is bounded at
  one call per compensated payment.
- **Graceful degradation, same pattern as the
  [ML Fraud Risk Scorer](#ml-fraud-risk-scorer).** The Gemini call sits
  behind a Resilience4j circuit breaker (`ai-summarizer`) on its own bean
  (`GeminiSummaryClient` — separate from the caller so Spring's AOP proxy
  actually intercepts it). API down, rate-limited, circuit open, or no
  `GEMINI_API_KEY` configured at all: the endpoint still answers, with a
  deterministic template built from the same event log. The response's
  `source` field (`AI` vs `DETERMINISTIC`) is surfaced as a badge in the
  dashboard, so it's always honest about which path produced the text.

The prompt input is just `payment_saga_events` rendered as a compact text
timeline (`SagaTimelineFormatter`) — a direct payoff of
[event sourcing](#event-sourcing): the full incident history is one indexed
query away, no cross-service joins needed.

## Compliance & Payment Methods

Two of the honest gaps this project used to have — no compliance/regulatory
layer, and only one implicit payment method — are addressed together,
since real compliance requirements differ *by* payment method: a new
`PaymentMethod` field (`CARD`, `UPI`, `NETBANKING`, set once at intake and
carried through the saga like `currency`) flows into a new 10th service,
**compliance-service**, which sits as the saga's first gate — before fraud,
not after, since account standing is more fundamental than transaction-level
risk scoring. `PAYMENT_INITIATED` now issues `CHECK_COMPLIANCE` instead of
`CHECK_FRAUD` directly; only a `COMPLIANCE_APPROVED` verdict (new
`COMPLIANCE_CHECKED` state) advances to the fraud check that used to run
first.

Two independent `ComplianceRule` strategies (Strategy pattern, mirroring
`FraudRule` exactly):

- **`KycVerificationRule`** — both payer and payee must be KYC-verified.
  Accounts are lazily provisioned as `verified=true` on first sight (the
  same lazy-provisioning pattern funds-auth-service's `Account` already
  uses), so every existing demo flow keeps working unchanged. A specific
  account is routed to a compliance rejection only by explicitly flagging
  it first (`POST /api/compliance/accounts/{accountId}/flag`) — the same
  "clear, deliberate trigger" pattern this project already uses elsewhere
  (e.g. settlement's $9k–$10k decline range), not a restrictive default.
- **`UpiDirectoryRule`** — only fires for `UPI`-method payments; rejects
  unless the payee account has been explicitly registered
  (`POST /api/compliance/upi/{accountId}/register`) — mirrors "you can only
  pay a registered VPA." Deliberately the opposite default from KYC: absence
  means not registered, no lazy auto-registration, since a payment-method
  directory shouldn't silently admit every account.

Independently of the verdict, any payment whose amount crosses a configured
threshold (`compliance.aml-threshold-cents`, default $10,000) gets an
append-only `RegulatoryReport` row recorded — real CTR-style filing happens
on the attempted transaction, not just approved ones, so this always runs
before the rule engine's verdict is even evaluated. `GET
/api/compliance/reports` lists every recorded report for audit visibility.

**Deliberately out of scope** (a natural follow-up, not built here): a
genuinely asynchronous CARD step-up/2FA confirmation — a saga pause,
external resume endpoint, and timeout handling — is a real, distinct
pattern, but a substantially larger addition than this slice; `CARD` today
only affects which compliance rule fires, not a new saga state.

## Concepts & patterns demonstrated

**Distributed systems / HLD**
- **Saga (orchestration, not choreography)** — a central orchestrator owns
  the state machine rather than scattering the payment lifecycle across
  every service's event handlers; see the tradeoff this was chosen over.
  Includes real **compensating transactions** (see [Compensation](#compensation)
  above), not just the happy path — the actual reason the pattern exists.
- **Transactional Outbox** — write + outbox row in one DB transaction,
  published by a separate poller, avoiding the dual-write problem between
  a database and Kafka.
- **Idempotent Consumer** — every consumer checks its own `processed_events`
  table before acting, since Kafka only guarantees at-least-once delivery.
- **Database per service** — nine services, nine separate Postgres
  databases, no shared schema.
- **Optimistic locking** — `@Version` on `Payment` and `Account` guards
  concurrent writers without pessimistic locks. `PaymentSagaAggregate` no
  longer needs it (see [Event Sourcing](#event-sourcing) below) — an
  append-only log has nothing to race over updating.
- **Schema evolution via versioned migrations** — a real schema change
  (`payment_saga_state` gaining `payee_account`) shipped as a new Flyway
  `V2` migration, never editing the applied `V1`; `V3` later replaced that
  whole table with an append-only event log (see
  [Event Sourcing](#event-sourcing)), again as a new migration rather than
  an edit to either prior one.
- **Consistency tradeoffs** — strong (ACID) consistency within each
  service's own database, eventual consistency across the saga as a whole.
- **CQRS** — [read-model-service](#cqrs-read-model) is a dedicated read side,
  projecting a denormalized view from `payment.events` rather than the
  dashboard querying each write-side service's own table directly.
- **Event Sourcing** — the saga aggregate's `state` is no longer a
  persisted column; it's derived by folding over an append-only
  `payment_saga_events` log (see [Event Sourcing](#event-sourcing)).

**Classic OOP / LLD (Gang of Four)**
- **Strategy** — fraud-service's `FraudRule` interface, with
  `HighValueThresholdRule`, `PositiveAmountRule`, and `MlRiskScoreRule` as
  independent `@Component` beans Spring autowires into the engine's rule
  list, ordered via `@Order` (see [ML Fraud Risk Scorer](#ml-fraud-risk-scorer)).
  compliance-service's `ComplianceRule` mirrors the exact same shape with
  `KycVerificationRule`/`UpiDirectoryRule` (see
  [Compliance & Payment Methods](#compliance--payment-methods)).
- **Mediator** — the saga orchestrator: fraud-service and funds-auth-service
  never call each other directly, only the orchestrator.
- **Command** — `CheckFraudCommand`, `AuthorizeFundsCommand`, etc. are
  requests encapsulated as objects; `ReleaseFundsCommand`/`ReverseLedgerCommand`
  are the literal *undo* counterparts to `AuthorizeFundsCommand`/`PostLedgerCommand`,
  actually issued now that compensation is wired up, not just defined ahead of time.
- **Factory Method** — static factories on result types (`Verdict.approve()` /
  `.reject()`), and Spring's `ConsumerFactory`/`ProducerFactory` beans.
- **Repository** — every `JpaRepository` interface.
- **Proxy** — every `JpaRepository` interface is also this: Spring Data
  generates a runtime proxy implementation, never a class we write. Same
  mechanism drives [Resilience](#resilience): `@Retry` and `@Transactional`
  on the same listener method both work via generated proxies
  (`RetryAspect`, `TransactionInterceptor`) intercepting the call and adding
  behavior — retry-with-backoff, transaction begin/commit — before
  delegating to the real bean.
- **Singleton** — every Spring-managed `@Component`/`@Service` bean.
- **Observer** — notification-service subscribes to `payment.events` as a
  passive observer of terminal outcomes, distinct from every other
  service's Command-style "react to what the orchestrator explicitly told
  you to do." Earlier phases of this project noted Kafka pub/sub is
  *architecturally* Observer-shaped but nothing here hand-rolled it — this
  is the point where that stopped being true.

Not every pattern fits everywhere, and forcing one in where it doesn't
belong would defeat the point — Decorator and Template Method are
deliberately not used here; nothing in this codebase needed them.

## Observability

Every service exposes Prometheus-formatted metrics at `/actuator/prometheus`
(added to the existing `health,info` exposure) and exports distributed
traces via OpenTelemetry to a local Zipkin collector — both come mostly for
free from Micrometer/Spring Boot Actuator, already a dependency of every
service, plus `micrometer-registry-prometheus` and
`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-zipkin` added on
top of it.

- **Metrics** — JVM, HTTP request, and Hikari connection-pool metrics are
  auto-instrumented by Actuator with no code changes. `docker compose`
  brings up a `prometheus` container (`docker/prometheus/prometheus.yml`)
  that scrapes all ten services on their host ports via
  `host.docker.internal`, since services run on the host via
  `mvn spring-boot:run` rather than inside the compose network. Browse
  metrics/targets at http://localhost:9090.
- **Distributed tracing** — 100% sampling (`management.tracing.sampling.probability:
  1.0` — a demo default, not a production one) with spans exported to a
  `zipkin` container at http://localhost:9411. Inbound HTTP requests get a
  span automatically; the more interesting piece is **Kafka**, since every
  hop between saga steps is an async publish/consume rather than a direct
  call. Each service's `KafkaConfig` explicitly enables Micrometer
  Observation (`setObservationEnabled(true)`) on both its listener container
  and its `KafkaTemplate`, which is what lets a single trace ID follow a
  payment across `payment-api → saga-orchestrator → fraud-service →
  funds-auth-service → ledger-service → settlement-service` and back,
  stitched together over Kafka rather than lost at each broker hop.
- The `/actuator/prometheus` endpoint sits behind the same `/actuator`
  exemption in `ApiKeyAuthFilter` that already covers `/actuator/health` —
  scraping is unauthenticated by design, matching how Prometheus itself is
  normally trusted at the network boundary rather than the app layer.

## Resilience

Every consumer service's `@KafkaListener` method now retries transient
failures in-process, with exponential backoff, before falling back to
today's ultimate safety net: log the failure, don't ack, let Kafka
redeliver. Before this, any failure — transient or not — was caught, logged,
and silently discarded, which meant the `@Transactional` method actually
**committed** whatever partial state existed at the point of failure instead
of rolling it back, and the only retry mechanism was Kafka's own immediate,
unbounded, zero-backoff redelivery.

`@Transactional(rollbackFor = Exception.class)` and Resilience4j's
`@Retry(name = "kafka-consumer", fallbackMethod = "...")` sit on the same
listener method (`resilience4j.retry.instances.kafka-consumer` in each
service's `application.yml`: 3 attempts, 200ms initial wait, ×2 exponential
backoff). That combination is safe — not incidental — because Resilience4j's
`RetryAspect` defaults to a lower `@Order` than Spring's transaction advice
(`LOWEST_PRECEDENCE - 4` vs. `LOWEST_PRECEDENCE`), so **Retry always wraps
outside Transactional**: every retry attempt gets a fresh transaction and a
clean persistence context, and a failed attempt's writes are rolled back
before the next one starts, rather than accumulating in one open
transaction. This was verified empirically (a throwaway Spring Boot + H2 app
using the exact same annotation combination) before being applied here: a
method that fails once then succeeds ends with exactly one committed row,
not two; a method that always fails hits exactly `max-attempts` tries with
the configured backoff between them, then the fallback runs — without the
exception ever propagating back to the `@KafkaListener` container, so `ack`
is simply never reached, identical in effect to the old catch block, just
retried with backoff first.

**Scope boundary**: this only adds backoff to the in-process retry that
happens *before* Kafka redelivery, not a circuit breaker and not dead-letter
handling. A genuinely poisoned message (one that will never succeed) still
loops forever at the outer Kafka-redelivery layer once Resilience4j's bounded
retries are exhausted each time — that's a separate, not-yet-built concern.

## Caching

`funds-auth-service` cache-asides an account's balance in Redis behind a new
read-only `MockBankLedger.getBalance()` — not something `reserve()`/`release()`
themselves use. That's deliberate: those two methods are the read-modify-write
that actually moves money, so they always read Postgres directly; a cache
should never be trusted for the value a fund-authorization decision hinges
on. `getBalance()` is for callers that tolerate a briefly stale value in
exchange for not hitting Postgres on every read — the design doc's stated
use case is a fraud velocity check (not yet wired up; see the roadmap).

`@Cacheable(cacheNames = "accountBalances", key = "#accountId")` and
Resilience4j's `@CircuitBreaker(name = "account-balance-cache",
fallbackMethod = "getBalanceFallback")` sit on the same method, the same
"why this is safe" question as [Resilience](#resilience)'s
`@Retry`+`@Transactional` combination: Resilience4j's `CircuitBreakerAspect`
defaults to a lower `@Order` than Spring's cache advice, so **CircuitBreaker
wraps outside Cacheable**, and Spring's default `CacheErrorHandler` rethrows
cache-get failures rather than swallowing them — meaning a Redis outage
surfaces as an exception the circuit breaker can actually catch and redirect
to the fallback, instead of disappearing silently. Verified empirically
(a throwaway Spring Boot + real Redis container, killed mid-test) before
being applied here: with Redis up, a second `getBalance()` call for the same
account is a genuine cache hit (no second Postgres read); with Redis down,
the call still returns the correct value via the fallback, with no exception
reaching the caller.

Eviction is the opposite choice from `@Retry`+`@Transactional`'s annotation-first
approach, on purpose: `reserve()`/`release()` call `cacheManager.evict(...)`
manually in a try/catch after their existing `accountRepository.save(...)`,
rather than using `@CacheEvict`. An annotation-based evict failure would
propagate into the caller's `@Transactional` scope
(`FundsAuthCommandListener.onCommand`) — meaning a Redis outage could break
fund reservation itself, exactly the single point of failure a cache is
supposed to avoid. The tradeoff this accepts: eviction is best-effort, so a
Redis outage during a write can leave `getBalance()` serving a stale value
until the next successful write's eviction succeeds or Redis recovers.
Acceptable specifically because `reserve()`/`release()` never read from the
cache themselves — only `getBalance()`'s (currently test-only) callers ever
see a stale value, never the authorization decision.

## API Gateway

`api-gateway` (port 8088) is a Spring Cloud Gateway (reactive/WebFlux)
service sitting in front of the six REST-exposing services — `payment-api`,
`saga-orchestrator`, `ledger-service`, `notification-service`,
`read-model-service`, `compliance-service` — routing by path (`/payments/**`
→ `payment-api`, `/api/sagas/**` → `saga-orchestrator`, `/api/ledger/**` →
`ledger-service`, `/api/notifications/**` → `notification-service`,
`/api/payments/**` → `read-model-service`, `/api/compliance/**` →
`compliance-service`). `fraud-service`, `funds-auth-service`, and
`settlement-service` are Kafka-only and have nothing to route to.

A custom `AuthGlobalFilter` validates `X-API-Key` at the edge before a
request reaches any route — the same header, property, and default as
every backend service's own check (see [Security](#security)). It's
ordered to run before rate limiting (`getOrder()` returns a negative value,
lower than the `RequestRateLimiter` filter's implicit `0`), so a bad-key
request is rejected before it can consume a legitimate client's rate-limit
budget.

`POST /payments` specifically — the client-facing write that actually
drives a saga through fraud, funds, ledger, and settlement — is
rate-limited via Spring Cloud Gateway's built-in Redis-backed
`RequestRateLimiter` (reusing the same `payflow-redis` container from
[Caching](#caching)), keyed per `X-API-Key` by a custom `KeyResolver` bean.
`replenishRate: 20`, `burstCapacity: 40` are deliberately the same numbers
[Load testing](#load-testing)'s k6 scripts already use for average (20/s)
and peak (100/s) load — meaning a peak-scenario k6 run against the gateway
(not yet done) would start seeing `429`s once the burst bucket empties, a
real interaction between two roadmap items worth exploring as a follow-up.
`GET` reads on `payment-api` are not rate-limited, since they're cheap and
idempotent.

CORS is now centralized via `spring.cloud.gateway.globalcors` instead of
being duplicated per controller — the `@CrossOrigin` annotations on
`SagaController`, `LedgerController`, and `NotificationController` are
gone, since nothing browser-based calls those services directly anymore.

`/actuator/gateway/routes` (exempted from auth like every other
`/actuator/**` path) lists the live route table — useful for confirming
routing config loaded as expected.

The five backend services keep their own `SecurityConfig`/`ApiKeyAuthFilter`
unchanged, deliberately: there's no network isolation between the gateway
and the backend ports on a local machine, so removing per-service auth
would be a real regression, not just redundancy. The gateway is a first
checkpoint, not a replacement for the others.

## ML Fraud Risk Scorer

`fraud-service`'s `FraudRuleEngine` (Strategy — see
[Concepts & patterns demonstrated](#concepts--patterns-demonstrated)) gets
a fourth rule, `MlRiskScoreRule`, alongside the existing
`PositiveAmountRule`/`HighValueThresholdRule`. It scores every payment with
a hand-trained logistic regression on three features — `amount`, `velocity`
(the payer's check count in the last 24h), and `deviation` (how far this
amount is from the payer's own recent average) — and rejects above a
configured threshold. **This is a toy classifier, explicitly not a
production model**: no training framework, no real labeled fraud data.
`FraudModelTrainer` (`fraud-service/src/test/java/.../ml/`, not part of
the running service) generates synthetic transactions against a documented
ground-truth rule and trains via plain gradient descent — pure Java, no ML
library — and its output is hand-pasted into `application.yml`'s
`fraud.ml-scorer.weights` once, offline. The point is the integration
pattern, not the model.

Velocity and deviation come from a new `fraud_check_history` table,
populated by `FraudCommandListener` on every check. This is deliberately
*not* a call to funds-auth-service for real account age — that service is
Kafka-only by design, and a new synchronous REST dependency would be a
bigger architectural change than this feature warrants (see
[Ideas under discussion](#ideas-under-discussion) for the event-driven
alternative). One honest limitation this creates: a brand-new payer always
scores `velocity=0, deviation=0` (nothing to compare against yet), so the
ML rule can never flag a first-time payer's amount alone — exactly why it's
ordered *after* the two deterministic rules (`@Order(1)`/`@Order(2)`/
`@Order(3)` on the three rules), not instead of them.

`MockMlFraudScorer.score()` — not `MlRiskScoreRule` itself — carries the
`@CircuitBreaker(name = "ml-fraud-scorer")`. That placement is deliberate,
not incidental: Spring's AOP proxy only intercepts calls arriving from
*outside* a bean, so an annotated method can never trigger its own fallback
via self-invocation (calling it through `this` from another method on the
same class) — confirmed the hard way, by building it the other way first
and watching the fallback silently never fire under a live simulated
outage. Putting the annotation on `MockMlFraudScorer`, a separate bean
called externally by `MlRiskScoreRule`, is what makes the proxy actually
apply. Verified live: forcing `fraud.ml-scorer.simulated-failure-rate` to
`1.0` and sending the same anomalous payment that the ML rule rejects under
normal operation, it now settles instead — the fallback returns a
below-threshold score, so a down ML service degrades to the deterministic
rules exactly as intended, never blocks the saga.

## Containerization

Every one of the 10 Spring Boot modules — `payment-api`, `saga-orchestrator`,
`fraud-service`, `compliance-service`, `funds-auth-service`, `ledger-service`,
`settlement-service`, `notification-service`, `read-model-service`,
`api-gateway` — has its own `Dockerfile`, each a two-stage build:
`maven:3.9-eclipse-temurin-21` compiles the jar (there's no Maven wrapper in
this repo, so the build stage needs the image's own Maven), then a slim
`eclipse-temurin:21-jre-alpine` runtime stage just copies it in.
`docker-compose.yml` builds and runs all 10 alongside the existing infra, so
`docker compose up --build` is now a complete alternative to running
`mvn spring-boot:run` per service — see
[Running it locally](#running-it-locally) for both. The `mvn spring-boot:run`
workflow isn't going away; it's still the faster loop for active
development, this is just a second way to run the whole thing with nothing
but Docker installed.

Each service's build context is the **repo root**, not its own directory:
`common` (the shared library every service depends on) has no published
artifact, so it has to be compiled from source as part of the same Maven
reactor build as the service itself. All 11 modules' `pom.xml` files get
copied and `dependency:go-offline` run before any `src/` is copied in — the
standard Docker/Maven layer-caching trick, so a rebuild after a source-only
change doesn't re-download the world. This was a genuine early failure
worth calling out: Maven's reactor won't even parse if the root `pom.xml`'s
declared `<modules>` list references directories that don't exist yet in
the build context — copying just `common`'s and the target service's
`pom.xml` isn't enough; **every** module's `pom.xml` has to be present, even
ones this particular image never builds.

Two networking changes were needed to make containers reachable from each
other, both because the existing setup was built assuming every service ran
on the host, talking to infra over `localhost`:

- **Kafka needed a second listener.** The existing setup only advertises
  `PLAINTEXT://localhost:9092` — fine for a host process, but a client
  bootstrapping from *inside* the compose network gets redirected to that
  same `localhost:9092` post-connect and fails. Added
  `INTERNAL://kafka:29092` alongside it; containerized services use the
  internal one, anything still run via `mvn spring-boot:run` keeps using
  `localhost:9092` unchanged.
- **Each service gets an `application-docker.yml`**, a new Spring profile
  (`SPRING_PROFILES_ACTIVE=docker`, set in `docker-compose.yml`, not baked
  into the image) overriding just the datasource URL, Kafka bootstrap
  servers, and Zipkin endpoint to use container hostnames (`postgres`,
  `kafka`, `zipkin`) instead of `localhost`. The base `application.yml` is
  untouched, so `mvn spring-boot:run` behaves exactly as before.
  `api-gateway`'s version is the one exception worth a callout: its route
  table is a YAML list, and Spring's environment-variable override syntax
  for list elements is index-fragile, so its `application-docker.yml`
  re-declares the whole route table with container hostnames rather than
  overriding individual URIs.

`docker/prometheus/prometheus.yml` lists both the host address
(`host.docker.internal:PORT`) and the container address (`<service>:PORT`)
for every job — whichever workflow is actually running shows "up" in
Prometheus's `/targets`, the other harmlessly shows "down," so switching
between `mvn spring-boot:run` and `docker compose up` never means editing
monitoring config.

Verified live end to end: built and started all 10 containers alongside the
existing infra, sent a real payment through the containerized gateway
(`:8088`), and watched it cross every container boundary — payment-api's
Kafka command through saga-orchestrator, fraud-service, funds-auth-service
(Postgres *and* Redis), ledger-service, and settlement-service — to reach
`SETTLED`, the same as the host-run version.

Explicitly out of scope: `dashboard/` isn't containerized here — it's a
Vite/React dev app with no production build/serving setup today, a Node
tooling concern separate from what this item was actually about (not
needing a local Java/Maven install).

## CQRS Read Model

`read-model-service` is a dedicated 9th service whose only job is serving
the dashboard: it subscribes to `payment.events`, projects a denormalized
`payment_view` (plus `ledger_entry_view`/`notification_view` child tables)
into its own Postgres database (`readmodel`), and exposes `GET /api/payments`
/ `GET /api/payments/{id}` — replacing three separate read APIs that used
to live on `saga-orchestrator`, `ledger-service`, and `notification-service`
and query each service's own write-side table directly. It's asynchronously
consistent with, and fully decoupled from, those services' own transactional
stores — none of them are queried at request time, and none of their own
REST endpoints were removed; they still work standalone.

Building this required closing a real gap first: the ledger and
notification "step-completed" events (`LEDGER_POSTED`, `LEDGER_FINALIZED`,
`LEDGER_REVERSED`) previously carried only `paymentId`/`occurredAt` — enough
for the orchestrator, which only needs to know "done," but not enough to
reconstruct a posting's debit/credit accounts or type for a denormalized
view. Those three events now carry the full posted entry (ledger-service
already has it in hand when publishing), and `notification-service` — the
one service that was deliberately consumer-only, since nothing in the saga
depends on a notification succeeding — now also publishes a new
`NOTIFICATION_SENT` event purely for this read side. Both changes are
additive: existing consumers that only read `paymentId` off these events
are unaffected.

`GET /api/payments/{id}` bundles the payment, its ledger entries, and its
notifications into one response — the dashboard's detail drawer used to
need two separate calls (ledger + notifications) on top of the list; this
collapses the whole detail view into a single round trip.

## Dashboard

`dashboard/` is a small React + AG Grid ops console — a live grid of every
payment and its current saga state, with a per-payment detail drawer showing
ledger postings and notifications. It's deliberately *not* a new backend
service of its own beyond the read model above: it's a thin client polling
[read-model-service](#cqrs-read-model)'s two read-only endpoints —

- `GET /api/payments` — the grid itself, the current state of every payment.
- `GET /api/payments/{paymentId}` — the detail drawer's ledger postings and
  notifications, in one call.

Both go through the [API Gateway](#api-gateway) at `http://localhost:8088`
rather than the service's own port — the frontend knows one origin, and CORS
is handled once at the gateway rather than per-controller. See
`dashboard/README.md` for how to run it.

## Security

Every REST endpoint across the six services that expose one — `payment-api`,
`saga-orchestrator`, `ledger-service`, `notification-service`,
`read-model-service`, `compliance-service` — requires an `X-API-Key` header, checked by a shared
`ApiKeyAuthFilter` in `common` that
each service registers explicitly via its own `FilterRegistrationBean`
(component scanning never crosses from `common` into a service's own base
package, so this can't just be a `@Component` picked up automatically). The
key is a single configured value (`payflow.security.api-key`, overridable via
the `PAYFLOW_API_KEY` env var, defaulting to `local-dev-api-key-change-me`
for local dev) — deliberately simple, since the point is demonstrating the
auth boundary itself, not building a credential-management system.
`/actuator/**` is explicitly exempted so health checks keep working
unauthenticated. `fraud-service`, `funds-auth-service`, and
`settlement-service` have no custom REST endpoints (Kafka-only), so there's
nothing to gate on them beyond actuator.

The [API Gateway](#api-gateway) adds the same check again at the edge
(`AuthGlobalFilter`, checking the same header against the same
`payflow.security.api-key` value) before a request reaches any backend —
deliberately in addition to, not instead of, each service's own check,
since backend ports remain directly reachable with no network isolation
locally.

## Status

**Built — all 9 core services, including real compensation, plus a live dashboard:**
`payment-api`, `saga-orchestrator`, `fraud-service`, `compliance-service`,
`funds-auth-service`, `ledger-service`, `settlement-service`,
`notification-service`, `read-model-service`. The full
happy path works end to end — intake through fraud approval, funds
authorization, ledger HOLD posting, settlement capture, a final
double-entry ledger posting, and notifications to both parties, all the
way to `SETTLED`. Both early-failure branches (fraud threshold,
insufficient funds) work too. And the saga's actual reason for existing —
**compensating transactions** — is wired up and verified: a settlement
decline after funds were already authorized correctly reverses the ledger
HOLD, releases the reservation, and restores the payer's balance to its
exact starting value, ending in `COMPENSATED`. All verified against real
Kafka and Postgres, not just compiled. On top of that, the
[Dashboard](#dashboard) above gives a live, browsable view over all of it,
every REST endpoint is gated behind [API-key auth](#security),
[Observability](#observability) gives Prometheus metrics and Kafka-spanning
distributed traces across the whole saga, every Kafka listener now retries
transient failures with exponential backoff before falling back to Kafka
redelivery (see [Resilience](#resilience)), [Load testing](#load-testing)
gives k6 scripts to turn the design doc's capacity estimate into a measured
number, funds-auth-service now cache-asides account balance in Redis
behind a circuit breaker (see [Caching](#caching)), a Spring Cloud
Gateway edge service now fronts the REST-exposing services with
centralized routing, auth, rate limiting, and CORS (see
[API Gateway](#api-gateway)), every REST-exposing service (payment-api,
saga-orchestrator, ledger-service, notification-service,
read-model-service) now serves a live OpenAPI spec and Swagger UI
(`/v3/api-docs`, `/swagger-ui/index.html`) generated straight from its
controllers, exempted from API-key auth the same way `/actuator` is so the
docs themselves are publicly browsable (each UI's Authorize button attaches
the `X-API-Key` header for actually calling the endpoints), and
fraud-service now has a fourth `FraudRule` backed by a small hand-trained
classifier, circuit-breaker-guarded so a down ML service degrades to the
deterministic rules instead of blocking the saga (see
[ML Fraud Risk Scorer](#ml-fraud-risk-scorer)), every service now has
its own Dockerfile, so the whole stack runs via `docker compose up --build`
with nothing but Docker installed (see [Containerization](#containerization)),
and a dedicated [CQRS Read Model](#cqrs-read-model) service now projects
`payment.events` into one denormalized view purpose-shaped for the
dashboard, decoupled from the three write-side services' tables it used to
query directly — which required enriching the ledger events and adding a
small producer to notification-service (previously consumer-only) so the
projection has full detail to work with, not just `paymentId`s. The saga
aggregate itself is now [event-sourced](#event-sourcing) too:
`payment_saga_events` is an append-only log of every transition, and
`state` is derived by folding over it on read rather than persisted as a
mutable column — `PaymentSagaState` no longer exists. And that event log
now feeds [AI Compensation Summaries](#ai-compensation-summaries): a
compensated payment's transition history can be turned into a plain-English
incident note on demand via the Gemini API — LLM explains, never decides —
circuit-breaker-guarded with a deterministic fallback so the endpoint works
with no API key at all. A 10th service, **compliance-service**, now gates
every payment before fraud with a KYC check and a UPI-directory check tied
to a new `paymentMethod` field (`CARD`/`UPI`/`NETBANKING`) threaded through
the whole saga, plus AML-style regulatory reporting for high-value amounts
regardless of verdict — see
[Compliance & Payment Methods](#compliance--payment-methods).

**Also on the roadmap** — the difference between a demo and something that
reads as production-grade:
- **Architecture Decision Records** — short docs on why Kafka over Pulsar, why orchestration over choreography
- **Schema evolution discipline** — every future schema change ships as a new Flyway migration, never editing one already applied
- **Event sourcing the rest of the way** — only the saga aggregate is event-sourced so far (see [Event Sourcing](#event-sourcing)); `Account.debit()`/`credit()` in funds-auth-service still mutates a current-value `balanceCents` column in place, guarded by `@Version` optimistic locking. The same log-and-fold approach would apply there too, just for a different aggregate.

## Ideas under discussion

Rougher than the roadmap above — directions being considered, not committed to:

- **Real velocity/behavioral features via a cross-service event** — the [ML Fraud Risk Scorer](#ml-fraud-risk-scorer)'s velocity/deviation features are computed from fraud-service's own local history, deliberately avoiding a synchronous call to funds-auth-service (which is Kafka-only by design). A fuller version would have funds-auth-service publish account-lifecycle events and let fraud-service build a local read model from them — more consistent with this project's event-driven style than adding a new synchronous REST call — could also double as infrastructure for [read-model-service](#cqrs-read-model), one feature store serving both the dashboard and fraud features.
- **LLM-generated fraud explanations, not decisions** — when fraud-service rejects a payment, turn the triggering rule + context into a human-readable reason for the notification/audit trail via an LLM call. The same LLM-explains-not-decides pattern that [AI Compensation Summaries](#ai-compensation-summaries) now applies to the compensation path, applied to the fraud path instead — `GeminiSummaryClient`'s circuit-breaker-plus-fallback shape would carry over directly.

## Testing

Tests are written alongside each service as it's built, not deferred to the
end — every phase since `fraud-service` has shipped with its own tests.

- **Unit tests** (JUnit 5 + Mockito) for pure logic and mockable
  collaborators: fraud-service's `FraudRule` strategies and rule engine,
  compliance-service's `KycVerificationRule`/`UpiDirectoryRule` strategies,
  its command listener (both verdicts, idempotency, the AML-report side
  effect firing regardless of verdict, failure propagation), and its REST
  controller, funds-auth-service's `MockBankLedger` and command listener (both
  authorize and release paths), ledger-service's `DoubleEntryLedger` (HOLD,
  FINAL, and REVERSAL legs), settlement-service's `SettleCommandListener`
  and `SettlementRiskCheck`, notification-service's `PaymentOutcomeListener`
  (both-parties-on-success, payer-only-on-failure-or-compensation), and —
  the most important one — saga-orchestrator's `PaymentEventListener`,
  covering every state transition — including the new `CHECK_COMPLIANCE`
  step and both its verdicts — plus the full compensation sequence
  (`SETTLEMENT_DECLINED → COMPENSATING → LEDGER_REVERSED → COMPENSATING →
  FUNDS_RELEASED → COMPENSATED`, chained through one test to prove it lands
  on `COMPENSATED` rather than getting stuck), both early-failure branches,
  idempotent redelivery, and unknown-payment handling. That last suite is
  the **regression pack** for the saga: every scenario in it mirrors
  something that was previously verified by hand with curl + psql, now
  automated so a future change can't silently break it without CI catching it.
  saga-orchestrator, settlement-service, notification-service, and
  funds-auth-service's listener tests each also cover the resilience
  refactor: a collaborator failure now propagates out of the listener
  method instead of being silently swallowed, with `ack` never called —
  the plain-Mockito unit tests don't exercise Resilience4j's AOP-driven
  retry/backoff itself (that needs a real Spring proxy), but they do prove
  the try/catch removal didn't regress the "don't ack on failure" contract.
- **Integration test** (Testcontainers — real Postgres + real Kafka, not
  mocks) for payment-api: posts a real payment over HTTP, confirms it's
  persisted, confirms the outbox actually published to a live Kafka topic,
  confirms the `Idempotency-Key` retry path returns the same payment instead
  of creating a duplicate, confirms a request with no `X-API-Key` is
  rejected with `401`, and confirms `/actuator/prometheus` is reachable and
  actually emits metrics (not just mapped).
- `common`'s `ApiKeyAuthFilter` has its own unit test covering the correct
  key, a missing key, a wrong key, and an exempt path (`/actuator/**`) — the
  one piece of logic shared identically across all four gated services.

Run everything: `mvn test` from the repo root (requires Docker for the
Testcontainers-based payment-api test — this needs a Docker Engine version
Testcontainers is tested against; a very new Docker Desktop can trip a
known compatibility issue between Testcontainers and its bundled
docker-java client, in which case CI is the source of truth for that one
test rather than your local machine).

## Load testing

`load-test/` has two k6 scripts against `payment-api`, turning the [design
doc](RESUME.md)'s capacity estimate — 20 payments/sec average, 100/sec peak
— into an actual measured number rather than a back-of-envelope guess:

- **`payments-throughput-test.js`** — hits `POST /payments` at exactly
  those two documented rates back to back, reporting `http_req_duration`
  percentiles and gating on error rate (`< 1%`, since there's no stated
  latency SLA to gate on instead).
- **`saga-completion-latency-test.js`** — measures the other half of the
  picture: not how fast the API *accepts* a payment (that's instant, by
  the outbox design), but how long the *whole saga* takes to actually
  reach `SETTLED`/`FAILED`/`COMPENSATED`, by polling after each POST.

Requires `k6` (`brew install k6`) and the full stack running locally. See
`load-test/README.md` for exact commands and how to override the target
host/API key. Manual/local only — not wired into CI, since load tests are
slow and need the full docker-compose stack up, unlike the unit/integration
suite `mvn verify` already runs on every push.

## Running it locally

Two ways to run this — pick one.

**Option A: host processes (faster loop for active development).** Requires
Docker Desktop and Java 21 + Maven.

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
cd settlement-service && mvn spring-boot:run   # :8086
cd notification-service && mvn spring-boot:run # :8087
cd read-model-service && mvn spring-boot:run   # :8089
cd compliance-service && mvn spring-boot:run   # :8090
cd api-gateway && mvn spring-boot:run          # :8088

# 4. (optional) Start the dashboard
cd dashboard && npm install && npm run dev     # http://localhost:5173
```

**Option B: everything in Docker** (see [Containerization](#containerization)) —
requires only Docker Desktop, no local Java/Maven install:

```bash
docker compose --profile docker up --build
```

Builds and starts infra plus all 10 services from source. Same ports as
Option A (`:8080`, `:8082`–`:8090`), so the dashboard and any curl testing
below work identically either way. `--profile docker` is required — a plain
`docker compose up -d` (as used in Option A's step 1) intentionally starts
infra only, so it doesn't unexpectedly try to build and start all 10 services
too.

Either way: Kafka UI is at http://localhost:8081 for browsing topics/messages
directly. Prometheus is at http://localhost:9090 (metrics/targets), Zipkin is
at http://localhost:9411 (traces) — see [Observability](#observability).
Interactive API docs (Swagger UI) are served by every REST-exposing service
at `/swagger-ui/index.html` on its own port — use the Authorize button with
the API key to call endpoints from the page:

- payment-api — http://localhost:8080/swagger-ui/index.html
- saga-orchestrator — http://localhost:8082/swagger-ui/index.html
- ledger-service — http://localhost:8085/swagger-ui/index.html
- notification-service — http://localhost:8087/swagger-ui/index.html
- read-model-service — http://localhost:8089/swagger-ui/index.html
- compliance-service — http://localhost:8090/swagger-ui/index.html

### Try it

`payment-api` is still reachable directly on `:8080` for the walkthrough
below, or through the [API Gateway](#api-gateway) on `:8088` with the exact
same headers and body:

```bash
curl -i -X POST http://localhost:8088/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-gateway-1" \
  -H "X-API-Key: local-dev-api-key-change-me" \
  -d '{"payerAccount":"11111111-1111-1111-1111-111111111111","payeeAccount":"22222222-2222-2222-2222-222222222222","amountCents":2500,"currency":"USD","paymentMethod":"NETBANKING"}'
```

```bash
curl -i -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" \
  -H "X-API-Key: local-dev-api-key-change-me" \
  -d '{"payerAccount":"11111111-1111-1111-1111-111111111111","payeeAccount":"22222222-2222-2222-2222-222222222222","amountCents":2500,"currency":"USD","paymentMethod":"NETBANKING"}'
```

A normal amount like the above reaches `SETTLED` within a couple of
seconds — passing through the new `COMPLIANCE_CHECKED` state along the way.
Amounts over $10,000 (`amountCents > 1000000`) get rejected by the
fraud rule instead — try it to see the saga end in `FAILED`.

To see actual **compensation**, send an amount between $9,000–$10,000
(clears compliance, fraud, and funds authorization, then gets declined at
settlement):

```bash
curl -i -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-compensation-1" \
  -H "X-API-Key: local-dev-api-key-change-me" \
  -d '{"payerAccount":"33333333-3333-3333-3333-333333333333","payeeAccount":"44444444-4444-4444-4444-444444444444","amountCents":950000,"currency":"USD","paymentMethod":"NETBANKING"}'
```

To see a **KYC rejection** (see
[Compliance & Payment Methods](#compliance--payment-methods)), flag the
payer account first, then attempt a payment from it:

```bash
curl -i -X POST http://localhost:8080/api/compliance/accounts/33333333-3333-3333-3333-333333333333/flag \
  -H "X-API-Key: local-dev-api-key-change-me"

curl -i -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-kyc-reject-1" \
  -H "X-API-Key: local-dev-api-key-change-me" \
  -d '{"payerAccount":"33333333-3333-3333-3333-333333333333","payeeAccount":"22222222-2222-2222-2222-222222222222","amountCents":2500,"currency":"USD","paymentMethod":"NETBANKING"}'
```

That ends in `FAILED` at `COMPLIANCE_REJECTED` — no `CHECK_FRAUD` is ever
issued. Undo it with `POST /api/compliance/accounts/{id}/verify` before
reusing that payer account in other examples.

To see a **UPI rejection**, pay an account that's never been registered as a
UPI recipient, then register it and retry:

```bash
curl -i -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-upi-reject-1" \
  -H "X-API-Key: local-dev-api-key-change-me" \
  -d '{"payerAccount":"11111111-1111-1111-1111-111111111111","payeeAccount":"55555555-5555-5555-5555-555555555555","amountCents":2500,"currency":"USD","paymentMethod":"UPI"}'

curl -i -X POST http://localhost:8080/api/compliance/upi/55555555-5555-5555-5555-555555555555/register \
  -H "X-API-Key: local-dev-api-key-change-me"

curl -i -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-upi-retry-1" \
  -H "X-API-Key: local-dev-api-key-change-me" \
  -d '{"payerAccount":"11111111-1111-1111-1111-111111111111","payeeAccount":"55555555-5555-5555-5555-555555555555","amountCents":2500,"currency":"USD","paymentMethod":"UPI"}'
```

The first attempt ends in `FAILED` at `COMPLIANCE_REJECTED`; the retry
after registering proceeds past compliance normally.

That reaches `COMPENSATED` — check the ledger to see the HOLD and REVERSAL
entries exactly offsetting (below), and the account balance restored:

```bash
docker exec payflow-postgres psql -U payflow -d fundsauth \
  -c "SELECT account_id, balance_cents FROM accounts WHERE account_id = '33333333-3333-3333-3333-333333333333';"
```

```bash
curl -H "X-API-Key: local-dev-api-key-change-me" http://localhost:8080/payments/<id>
curl -H "X-API-Key: local-dev-api-key-change-me" http://localhost:8080/payments/<id>/timeline
```

Once it's `COMPENSATED`, generate the
[AI incident summary](#ai-compensation-summaries) (works without a
`GEMINI_API_KEY` too — you'll get `"source": "DETERMINISTIC"` instead of
`"AI"`):

```bash
curl -H "X-API-Key: local-dev-api-key-change-me" http://localhost:8088/api/sagas/<id>/summary
```

Check saga progress directly (or just watch it live in the
[dashboard](#dashboard) instead) — `state` isn't a column here, so this
shows the full [event-sourced](#event-sourcing) transition log, not just
the current value:

```bash
docker exec payflow-postgres psql -U payflow -d orchestrator \
  -c "SELECT payment_id, sequence_number, event_type, to_state FROM payment_saga_events ORDER BY payment_id, sequence_number;"
```

Check both ledger legs (HOLD then FINAL):

```bash
docker exec payflow-postgres psql -U payflow -d ledger \
  -c "SELECT payment_id, debit_account, credit_account, amount_cents, posting_type FROM ledger_entries ORDER BY posted_at;"
```

Check the settlement capture record:

```bash
docker exec payflow-postgres psql -U payflow -d settlement \
  -c "SELECT payment_id, amount_cents, captured_at FROM settlements;"
```

Check who got notified (both payer and payee on success; payer only on a
`FAILED` payment):

```bash
docker exec payflow-postgres psql -U payflow -d notification \
  -c "SELECT payment_id, recipient, outcome, message FROM notifications;"
```

Check recorded AML-style regulatory reports (any payment ≥ $10,000 gets one
regardless of its eventual verdict — the $9,500 compensation example above
stays just under the threshold, so send a payment ≥ `amountCents: 1000000`
to see a row here):

```bash
curl -H "X-API-Key: local-dev-api-key-change-me" http://localhost:8088/api/compliance/reports
```

Check the [CQRS read model](#cqrs-read-model)'s own projection — should
match the folded state of the event log above once `read-model-service`
catches up:

```bash
docker exec payflow-postgres psql -U payflow -d readmodel \
  -c "SELECT payment_id, state FROM payment_view;"
curl -H "X-API-Key: local-dev-api-key-change-me" http://localhost:8088/api/payments/<id>
```

## Tech stack

Java 21 · Spring Boot 3.3.13 · Maven (multi-module) · PostgreSQL 16 · Flyway ·
Apache Kafka 3.7 (KRaft) · Kafka UI · Docker Compose · Hibernate / Spring Data JPA ·
Micrometer / Prometheus · OpenTelemetry / Zipkin · Resilience4j · k6 · Redis ·
Spring Cloud Gateway · springdoc-openapi / Swagger UI · Google Gemini API
