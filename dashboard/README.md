# PayFlow Ops Console

A read-only React + AG Grid dashboard over the running payment-orchestration
services. There's no dashboard-specific backend or query database here — it
polls three services' existing REST APIs directly:

| Service | Port | Endpoint | Used for |
|---|---|---|---|
| saga-orchestrator | 8082 | `GET /api/sagas` | the live grid of payments and their current state |
| ledger-service | 8085 | `GET /api/ledger/{paymentId}` | double-entry postings in the detail drawer |
| notification-service | 8087 | `GET /api/notifications/{paymentId}` | sent notifications in the detail drawer |

`saga-orchestrator` is the only service whose view of a payment reflects its
*current* state — `payment-api`'s own `payments` row is stamped `INITIATED`
at creation and never updated again, since it has no Kafka consumer of its
own. That's why the grid reads from the orchestrator, not payment-api.

## Running it

Requires the rest of the stack (Postgres, Kafka, and all 7 Spring Boot
services) already running — see the root `README.md`.

```bash
npm install
npm run dev
```

Opens on `http://localhost:5173`. The grid polls `/api/sagas` every 3
seconds; click any row to open a drawer with that payment's ledger postings
and notifications.

There's no API gateway in front of the services (an explicitly deferred
roadmap item), so the dashboard calls each service's port directly and
relies on the `@CrossOrigin` annotations already added to each read
controller.
