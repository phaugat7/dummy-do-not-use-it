-- ============================================================
-- 1. DATABASE MIGRATION: outbox_events table
-- ============================================================
-- Run this as a Flyway/Liquibase migration or manually

CREATE TABLE outbox_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(36)   NOT NULL,
    aggregate_type  VARCHAR(255)  NOT NULL,
    aggregate_id    VARCHAR(255)  NOT NULL,
    event_type      VARCHAR(20)   NOT NULL,  -- INSERT, UPDATE, DELETE
    payload         TEXT          NOT NULL,   -- JSON serialized entity
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',  -- PENDING, SENT, FAILED
    retry_count     INT           NOT NULL DEFAULT 0,
    max_retries     INT           NOT NULL DEFAULT 5,
    error_message   TEXT          NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at         TIMESTAMP     NULL,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);
CREATE INDEX idx_outbox_aggregate      ON outbox_events (aggregate_type, aggregate_id);
