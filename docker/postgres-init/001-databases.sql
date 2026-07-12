-- Each service owns its own database, even though they share one
-- container for local dev convenience — mirrors "database per service"
-- without needing a separate Postgres instance per service.
CREATE DATABASE orchestrator;
CREATE DATABASE fraud;
CREATE DATABASE fundsauth;
CREATE DATABASE ledger;
