# Payment Processing Engine Ops Console

A read-only React + AG Grid dashboard over the running payment-processing-engine
services. It polls `read-model-service`'s CQRS read model through the API
Gateway (`http://localhost:8088`, see the root README's
[API Gateway](../README.md#api-gateway) section) — a denormalized
projection built entirely from `payment.events`, not a live query against
any write-side service's own table:

| Endpoint (via gateway) | Backing service | Used for |
|---|---|---|
| `GET /api/payments` | read-model-service | the live grid of payments and their current state |
| `GET /api/payments/{paymentId}` | read-model-service | the detail drawer's ledger postings and notifications, in one call |

Before the CQRS read model existed, the grid polled `saga-orchestrator`
directly and the detail drawer made two further calls into `ledger-service`
and `notification-service` — see the root README's
[CQRS read model](../README.md#cqrs-read-model) section for why that
changed and how the projection is kept asynchronously consistent with
those services' own transactional stores.

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
