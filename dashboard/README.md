# PayFlow Ops Console

This app is two separate views over the same running payment-orchestration
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
  FRAUD_CHECKED → AUTHORIZED → LEDGER_POSTED → SETTLED`, ending on a
  success, reversed, or failure screen. No fake card-number/CVV fields —
  this project models accounts as opaque UUIDs throughout, and a realistic
  card-entry form with no real card network behind it would misrepresent
  what's actually happening. A customer id is generated once via
  `crypto.randomUUID()` and persisted in `localStorage` so repeat visits
  are "the same customer."

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
| `GET /api/payment-engine/{id}/summary` | payment-engine | the compensated-payment "Generate summary" button in the drawer |
| `POST /payments` | payment-api | Demo controls → **Send a payment** |
| `POST /api/compliance/accounts/{id}/flag` | compliance-service | Demo controls → **Flag for KYC review** |
| `POST /api/compliance/accounts/{id}/verify` | compliance-service | Demo controls → **Verify** |
| `POST /api/compliance/upi/{id}/register` | compliance-service | Demo controls → **Register as UPI recipient** |

Before the CQRS read model existed, the grid polled `payment-engine`
directly and the detail drawer made two further calls into `ledger-service`
and `notification-service` — see the root README's
[CQRS read model](../README.md#cqrs-read-model) section for why that
changed and how the projection is kept asynchronously consistent with
those services' own transactional stores.

## Demo controls

A collapsible panel above the grid (`ControlPanel.jsx`) so a demo doesn't
need a terminal open. Two sections:

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
