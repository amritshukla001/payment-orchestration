-- Cache for AI-generated compensation summaries: one row per payment, so
-- the LLM call (and its cost) happens at most once per payment -- repeat
-- requests serve the stored text. `source` records whether the summary came
-- from the Claude API or the deterministic fallback template.
CREATE TABLE payment_saga_summaries (
    payment_id   UUID PRIMARY KEY,
    summary      TEXT NOT NULL,
    source       VARCHAR(16) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL
);
