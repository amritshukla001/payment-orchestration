# PayFlow Ops Console

A read-only React + AG Grid dashboard over the running payment-orchestration
services. There's no dashboard-specific backend or query database here — it
polls three existing REST APIs through the API Gateway (`http://localhost:8088`,
see the root README's [API Gateway](../README.md#api-gateway) section):

| Endpoint (via gateway) | Backing service | Used for |
|---|---|---|
| `GET /api/sagas` | saga-orchestrator | the live grid of payments and their current state |
| `GET /api/ledger/{paymentId}` | ledger-service | double-entry postings in the detail drawer |
| `GET /api/notifications/{paymentId}` | notification-service | sent notifications in the detail drawer |

`saga-orchestrator` is the only service whose view of a payment reflects its
*current* state — `payment-api`'s own `payments` row is stamped `INITIATED`
at creation and never updated again, since it has no Kafka consumer of its
own. That's why the grid reads from the orchestrator, not payment-api.

## Running it

Requires the rest of the stack (Postgres, Kafka, all 7 Spring Boot services,
and the `api-gateway`) already running — see the root `README.md`.

```bash
npm install
npm run dev
```

Opens on `http://localhost:5173`. The grid polls `/api/sagas` every 3
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
