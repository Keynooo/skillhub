-- Persist forkprobe comparison runs so users can revisit past results after
-- the in-memory comparison store (30-min TTL) has expired. Rows are written
-- only when a run reaches a terminal state (COMPLETED / FAILED / CANCELLED).
CREATE TABLE forkprobe_comparison (
    id               BIGSERIAL PRIMARY KEY,
    comparison_id    VARCHAR(64)  NOT NULL UNIQUE,
    user_id          VARCHAR(128) NOT NULL,
    task_description TEXT         NOT NULL,
    provider         VARCHAR(64),
    status           VARCHAR(20)  NOT NULL,
    results_json     jsonb,
    error            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_forkprobe_comparison_user_created
    ON forkprobe_comparison (user_id, created_at DESC);
