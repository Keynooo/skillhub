-- Track skills whose LLM auto-tagging returned no valid scenario labels, so a human
-- can label them manually instead of the failure being silently dropped (see
-- LabelTaskConsumer / LabelTaggingReviewService).
CREATE TABLE label_tagging_review (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    namespace_id BIGINT,
    skill_name VARCHAR(256),
    skill_slug VARCHAR(256),
    reason TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(128),
    CONSTRAINT uq_label_tagging_review_skill UNIQUE (skill_id)
);

CREATE INDEX idx_label_tagging_review_status ON label_tagging_review (status);
