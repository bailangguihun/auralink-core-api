-- ROUND 9C-B.1 append-only Creation retry and provider-dispatch ledger.
-- This migration is additive and deliberately preserves all V1-V3 tables.

ALTER TABLE creations ADD COLUMN retry_version INTEGER NOT NULL DEFAULT 0
    CHECK (retry_version >= 0);

CREATE TABLE creation_execution_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    creation_id INTEGER NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    retry_idempotency_key_digest VARCHAR(64),
    admitted_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    resolution_code VARCHAR(128),
    CONSTRAINT uq_creation_execution_attempts_creation_number
        UNIQUE (creation_id, attempt_number),
    CONSTRAINT uq_creation_execution_attempts_creation_idempotency
        UNIQUE (creation_id, retry_idempotency_key_digest),
    CONSTRAINT fk_creation_execution_attempts_creation
        FOREIGN KEY (creation_id) REFERENCES creations (id) ON DELETE CASCADE
);

-- SQLite supports partial indexes.  This is the durable one-active-attempt
-- boundary; NULL retry idempotency keys intentionally remain repeatable.
CREATE UNIQUE INDEX uq_creation_execution_attempts_one_active
    ON creation_execution_attempts (creation_id)
    WHERE finished_at IS NULL;
CREATE INDEX idx_creation_execution_attempts_creation_admitted
    ON creation_execution_attempts (creation_id, admitted_at, id);

CREATE TABLE creation_step_dispatch_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    creation_step_id INTEGER NOT NULL,
    creation_execution_attempt_id INTEGER NOT NULL,
    provider_request_key VARCHAR(128),
    dispatch_state VARCHAR(32) NOT NULL DEFAULT 'NOT_SENT'
        CHECK (dispatch_state IN ('NOT_SENT', 'SEND_STARTED', 'RESULT_PERSISTED')),
    dispatch_started_at TIMESTAMP,
    finished_at TIMESTAMP,
    resolution_code VARCHAR(128),
    result_asset_id INTEGER,
    result_digest VARCHAR(64),
    canonical_poem_digest VARCHAR(64),
    CONSTRAINT uq_creation_step_dispatch_attempts_step_execution
        UNIQUE (creation_step_id, creation_execution_attempt_id),
    CONSTRAINT uq_creation_step_dispatch_attempts_provider_request_key
        UNIQUE (provider_request_key),
    CONSTRAINT fk_creation_step_dispatch_attempts_step
        FOREIGN KEY (creation_step_id) REFERENCES creation_steps (id) ON DELETE CASCADE,
    CONSTRAINT fk_creation_step_dispatch_attempts_execution_attempt
        FOREIGN KEY (creation_execution_attempt_id) REFERENCES creation_execution_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_creation_step_dispatch_attempts_result_asset
        FOREIGN KEY (result_asset_id) REFERENCES media_assets (id) ON DELETE RESTRICT
);

CREATE INDEX idx_creation_step_dispatch_attempts_step
    ON creation_step_dispatch_attempts (creation_step_id, id);
CREATE INDEX idx_creation_step_dispatch_attempts_execution
    ON creation_step_dispatch_attempts (creation_execution_attempt_id, id);

-- V3 kept only current-state projections.  One deterministic snapshot is the
-- maximum honest backfill; this migration never invents historical attempts.
INSERT INTO creation_execution_attempts (
    creation_id, attempt_number, retry_idempotency_key_digest,
    admitted_at, finished_at, resolution_code
)
SELECT
    c.id,
    1,
    NULL,
    c.created_at,
    CASE WHEN c.status IN ('SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS')
        THEN COALESCE(c.finished_at, c.updated_at, c.created_at)
        ELSE NULL END,
    CASE WHEN c.status IN ('SUCCEEDED', 'FAILED', 'PARTIAL_SUCCESS')
        THEN 'LEGACY_V3_STATE_SNAPSHOT'
        ELSE NULL END
FROM creations c;

INSERT INTO creation_step_dispatch_attempts (
    creation_step_id, creation_execution_attempt_id, provider_request_key,
    dispatch_state, dispatch_started_at, finished_at, resolution_code,
    result_asset_id, result_digest, canonical_poem_digest
)
SELECT
    s.id,
    a.id,
    -- V3 did not constrain projection request keys.  Preserve the first
    -- deterministic occurrence; a duplicate legacy value is not valid proof
    -- of a distinct immutable request key and must not block an additive
    -- migration.  The Step projection remains available for conservative
    -- ambiguity handling.
    CASE WHEN s.provider_request_key IS NOT NULL AND EXISTS (
        SELECT 1 FROM creation_steps earlier
        WHERE earlier.provider_request_key = s.provider_request_key
          AND earlier.id < s.id
    ) THEN NULL ELSE s.provider_request_key END,
    -- An out-of-vocabulary legacy projection cannot safely be treated as an
    -- unsent request.  Preserve conservative ambiguity rather than failing
    -- an otherwise additive V3 migration.
    CASE WHEN s.provider_dispatch_state IN ('NOT_SENT', 'SEND_STARTED', 'RESULT_PERSISTED')
        THEN s.provider_dispatch_state ELSE 'SEND_STARTED' END,
    CASE WHEN s.provider_dispatch_state IN ('SEND_STARTED', 'RESULT_PERSISTED')
        THEN s.started_at ELSE NULL END,
    CASE WHEN s.status IN ('SUCCEEDED', 'FAILED') THEN s.finished_at ELSE NULL END,
    'LEGACY_V3_STATE_SNAPSHOT',
    CASE WHEN s.provider_dispatch_state = 'RESULT_PERSISTED' THEN s.output_asset_id ELSE NULL END,
    CASE WHEN s.provider_dispatch_state = 'RESULT_PERSISTED' AND s.output_asset_id IS NOT NULL
        THEN (SELECT m.sha256 FROM media_assets m WHERE m.id = s.output_asset_id) ELSE NULL END,
    NULL
FROM creation_steps s
JOIN creation_execution_attempts a
    ON a.creation_id = s.creation_id AND a.attempt_number = 1
WHERE s.started_at IS NOT NULL
   OR s.status IN ('RUNNING', 'SUCCEEDED', 'FAILED')
   OR s.provider_dispatch_state <> 'NOT_SENT'
   OR s.provider_request_key IS NOT NULL;
