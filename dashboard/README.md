# PayFlow Ops Console

This app is three separate views over the same running payment-orchestration
services, switched by URL rather than a real router (see main.jsx):

- **`/`** — the ops console below: an engineering-facing grid of every
  payment, read-only aside from the Demo controls panel.
- **`/?view=checkout`** — a customer-facing checkout page
  (`Checkout.jsx`/`checkout.css`), styled deliberately differently (light,
  quiet, Stripe/Razorpay-shaped) from the dark ops console, since it's
  meant to be what someone actually paying would see, not what an engineer
  watching Kafka would. Pays a fixed demo merchant account, offers three
  amount presets that double as scenario triggers (a plain payment, a
  $9,500 compensation demo, a $15,000 high-value decline), and polls the
  same `GET /api/payments/{id}` the ops console's drawer uses to drive a
  step-by-step progress view through `INITIATED → COMPLIANCE_CHECKED →
  FRAUD_CHECKED → AWAITING_STEP_UP → AUTHORIZED → LEDGER_POSTED →
  SETTLED`, ending on a success, reversed, or failure screen. A `CARD`
  payment actually pauses at `AWAITING_STEP_UP` — the page shows an
  Approve/Decline panel wired to payment-engine's step-up endpoints (see
  the root README's
  [Compliance & Payment Methods](../README.md#compliance--payment-methods)
  section); every other method's saga skips straight past that step, same
  as the stepper always has for states a payment doesn't visit. No fake
  card-number/CVV fields —
  this project models accounts as opaque UUIDs throughout, and a realistic
  card-entry form with no real card network behind it would misrepresent
  what's actually happening. A customer id is generated once via
  `crypto.randomUUID()` and persisted in `localStorage` so repeat visits
  are "the same customer" by default — the **switch** link next to it
  lets you paste in a different account (e.g. one you already flagged for
  KYC review or registered for UPI via the ops console's Demo controls
  panel) to see that decision from the paying customer's side instead of
  the engineer's, or generate a brand-new one.
- **`/?view=merchant`** — a business-owner-facing analytics summary
  (`Merchant.jsx`/`merchant.css`): total payments, settled volume, and
  settled/reversed/failed rates, a breakdown by payment method, and a
  7-day volume chart. Visually stays with the ops console's dark theme
  (reuses `index.css`'s tokens) rather than introducing a third palette —
  this is an internal/business view, not the customer-facing checkout
  page. Backed by real `GROUP BY` aggregation queries against
  read-model-service's `payment_view` projection
  (`PaymentViewRepository.countByState`/`aggregateByMethod`/`dailyVolumeSince`,
  `GET /api/payments/analytics/summary`) — no client-side math over the
  full payment list, and no new gateway route needed since it's nested
  under the existing `/api/payments/**` path. Polls every 10s, slower
  than the grid's 3s, since aggregate stats don't need sub-second
  freshness. No charting library — the daily-volume bars and method
  breakdown are plain CSS, matching how this dashboard hasn't reached for
  one anywhere else.

The ops console — the grid and detail drawer — are read-only, polling
`read-model-service`'s CQRS read model through the API Gateway
(`http://localhost:8088`, see the root README's
[API Gateway](../README.md#api-gateway) section) — a denormalized
projection built entirely from `payment.events`, not a live query against
any write-side service's own table. A separate **Demo controls** panel
(see below) is the one place the dashboard *writes* — everything else it
touches is read-only:

| Endpoint (via gateway) | Backing service | Used for |
|---|---|---|
| `GET /api/payments` | read-model-service | the live grid of payments and their current state |
| `GET /api/payments/{paymentId}` | read-model-service | the detail drawer's ledger postings and notifications, in one call |
| `GET /api/payments/analytics/summary` | read-model-service | the merchant analytics view's KPIs, method breakdown, and daily volume |
| `GET /api/payment-engine/{id}/summary` | payment-engine | the compensated-payment "Generate summary" button in the drawer |
| `POST /api/payment-engine/{id}/step-up/confirm` | payment-engine | Checkout page → **Approve in bank app** (CARD payments only) |
| `POST /api/payment-engine/{id}/step-up/decline` | payment-engine | Checkout page → **Decline** (CARD payments only) |
| `POST /payments` | payment-api | Demo controls → **Send a payment** |
| `POST /api/compliance/accounts/{id}/flag` | compliance-service | Demo controls → **Flag for KYC review** |
| `POST /api/compliance/accounts/{id}/verify` | compliance-service | Demo controls → **Verify** |
| `POST /api/compliance/upi/{id}/register` | compliance-service | Demo controls → **Register as UPI recipient** |
| `POST /api/funds-auth/banks/{bankCode}/outage` | funds-auth-service | Demo controls → **Mark down for maintenance** |
| `POST /api/funds-auth/banks/{bankCode}/restore` | funds-auth-service | Demo controls → **Restore** |

Before the CQRS read model existed, the grid polled `payment-engine`
directly and the detail drawer made two further calls into `ledger-service`
and `notification-service` — see the root README's
[CQRS read model](../README.md#cqrs-read-model) section for why that
changed and how the projection is kept asynchronously consistent with
those services' own transactional stores.

## Demo controls

A collapsible panel above the grid (`ControlPanel.jsx`) so a demo doesn't
need a terminal open. Three sections:

- **Send a payment** — a real `POST /payments` through the same
  transactional-outbox path the curl walkthrough in the root README uses,
  with editable payer/payee/amount/currency/method fields (prefilled with
  the same demo account UUIDs the root README's "Try it" section uses).
  Generates a fresh `Idempotency-Key` per submission
  (`crypto.randomUUID()`), and the new payment shows up in the grid on the
  next 3-second poll.
- **Compliance actions** — the three demo levers `compliance-service`
  exposes API-only (see the root README's
  [Compliance & Payment Methods](../README.md#compliance--payment-methods)
  section): flag an account for KYC review, verify it again, or register it
  as a UPI recipient. Lets you trigger a KYC rejection or a UPI-directory
  rejection from the UI instead of curl.
- **Bank outages** — funds-auth-service's demo lever for
  `NetBankingAvailabilityRule`: mark one of the five mock banks
  (`BankCodeResolver`) down for maintenance, or restore it. A NetBanking
  payment from an account that resolves to a down bank fails at funds
  authorization; every account's resolved bank is deterministic, so
  toggling the same bank twice in a row demonstrates both outcomes on the
  same account.

Both forms report success/error inline rather than via a toast — see
`ControlPanel.jsx`'s `ResultLine` component.

## Running it

Requires the rest of the stack (Postgres, Kafka, all 8 Spring Boot services,
and the `api-gateway`) already running — see the root `README.md`.

```bash
npm install
npm run dev
```

Opens on `http://localhost:5173`. The grid polls `/api/payments` every 3
seconds; click any row to open a drawer with that payment's ledger postings
and notifications.

All requests go through the API Gateway's single origin
(`http://localhost:8088`), which handles routing to the right backend
service and CORS for the Vite dev server's origin centrally — see the root
README's [API Gateway](../README.md#api-gateway) section.

## Auth

Every service now requires an `X-API-Key` header, checked both by the
gateway at the edge and by each backend service itself (see the root
README's [Security](../README.md#security) section). The dashboard sends
one on every request, read from `VITE_API_KEY` at build/dev time, falling
back to the same `local-dev-api-key-change-me` default the backend services
fall back to:

```bash
VITE_API_KEY=your-key npm run dev
```

A key shipped in frontend JS is never a real secret — this demonstrates the
auth boundary between the dashboard and the services, not a credential
management system.

## Stripe (CARD payments)

Checkout's CARD option renders a real Stripe Elements card field when
`VITE_STRIPE_PUBLISHABLE_KEY` is set at build/dev time:

```bash
VITE_STRIPE_PUBLISHABLE_KEY=pk_test_... npm run dev
```

A publishable key is client-safe by design (Stripe's own terminology for
"not a secret") — same category as `VITE_API_KEY` above. Without it set,
CARD falls back to exactly what it's always done: no card field at all.
The matching backend secret key (`STRIPE_API_KEY`, `sk_test_...`) goes on
`payment-api`, not here — see the root README's
[Compliance & Payment Methods](../README.md#compliance--payment-methods)
section. Both are free Stripe **test-mode** keys — no billing required,
no real card data or money ever involved.
