-- Local prototype only.  R24 Guide is a per-painting standard-guide cache,
-- not a Creation workflow or an audio pipeline.  The cache and its narrow
-- provider-dispatch evidence live with the PostgreSQL catalog identity.

CREATE TABLE catalog_painting_guides (
    public_id UUID PRIMARY KEY,
    painting_id UUID NOT NULL UNIQUE REFERENCES catalog_paintings(public_id) ON DELETE RESTRICT,
    source_hash CHAR(64),
    status TEXT NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'HELD_LEGACY')),
    legacy_original_status TEXT,
    result_json JSONB,
    input_batch_id UUID NOT NULL,
    input_revision INTEGER NOT NULL CHECK (input_revision > 0),
    input_snapshot JSONB NOT NULL,
    generated_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_catalog_guide_input_revision
        FOREIGN KEY (input_batch_id, input_revision)
        REFERENCES catalog_batch_revisions(batch_id, revision) ON DELETE RESTRICT,
    CONSTRAINT ck_catalog_painting_guides_success
        CHECK ((status = 'SUCCESS' AND source_hash IS NOT NULL AND result_json IS NOT NULL AND generated_at IS NOT NULL)
            OR status <> 'SUCCESS')
);

CREATE INDEX idx_catalog_painting_guides_status_updated
    ON catalog_painting_guides(status, updated_at DESC);
CREATE INDEX idx_catalog_painting_guides_source_hash
    ON catalog_painting_guides(source_hash)
    WHERE source_hash IS NOT NULL;

-- The original R24 table holds only the current cache row.  This immutable
-- companion keeps the exact source snapshot of each PostgreSQL-generated or
-- imported successful result, so a later catalog revision cannot redefine it.
CREATE TABLE catalog_painting_guide_result_revisions (
    public_id UUID PRIMARY KEY,
    guide_id UUID NOT NULL REFERENCES catalog_painting_guides(public_id) ON DELETE RESTRICT,
    source_hash CHAR(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    result_json JSONB NOT NULL,
    input_batch_id UUID NOT NULL,
    input_revision INTEGER NOT NULL CHECK (input_revision > 0),
    input_snapshot JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_catalog_guide_result_source UNIQUE (guide_id, source_hash),
    CONSTRAINT fk_catalog_guide_result_input_revision
        FOREIGN KEY (input_batch_id, input_revision)
        REFERENCES catalog_batch_revisions(batch_id, revision) ON DELETE RESTRICT
);

CREATE INDEX idx_catalog_guide_result_painting_revision
    ON catalog_painting_guide_result_revisions(input_batch_id, input_revision, generated_at DESC);

-- A Guide HTTP POST is synchronous.  This table is intentionally not a queue:
-- it records only the provider-dispatch boundary so a restart or database
-- outage cannot silently issue a second uncertain paid request.
CREATE TABLE catalog_painting_guide_generation_attempts (
    public_id UUID PRIMARY KEY,
    painting_id UUID NOT NULL REFERENCES catalog_paintings(public_id) ON DELETE RESTRICT,
    source_hash CHAR(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    request_id UUID NOT NULL UNIQUE,
    status TEXT NOT NULL CHECK (status IN ('DISPATCHING', 'SUCCEEDED', 'FAILED_BEFORE_RESPONSE', 'POST_RESPONSE_UNCERTAIN')),
    provider_failure_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_catalog_guide_attempt_painting_source UNIQUE (painting_id, source_hash)
);

CREATE INDEX idx_catalog_guide_attempt_status_updated
    ON catalog_painting_guide_generation_attempts(status, updated_at DESC)
    WHERE status IN ('DISPATCHING', 'POST_RESPONSE_UNCERTAIN');

-- One-way import ledger.  A completed source is deliberately a no-op replay:
-- it never restores a removed target row or overwrites a later Guide cache.
CREATE TABLE catalog_legacy_guide_imports (
    source_id TEXT PRIMARY KEY,
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    mapping_sha256 CHAR(64) NOT NULL CHECK (mapping_sha256 ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status = 'SUCCEEDED'),
    imported_guides INTEGER NOT NULL CHECK (imported_guides >= 0),
    imported_successes INTEGER NOT NULL CHECK (imported_successes >= 0),
    imported_held INTEGER NOT NULL CHECK (imported_held >= 0),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE catalog_legacy_guide_import_attempts (
    attempt_id UUID PRIMARY KEY,
    source_id TEXT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    outcome TEXT NOT NULL CHECK (outcome IN ('STARTED', 'SUCCEEDED', 'NOOP_REIMPORT', 'FAILED', 'CONFLICT')),
    failure_code TEXT,
    failure_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_catalog_legacy_guide_import_attempts_source
    ON catalog_legacy_guide_import_attempts(source_id, started_at DESC);
