-- Local rehearsal only.  A multi-store legacy import is staged by dependency
-- order, not one distributed transaction.  This ledger makes its readiness
-- explicit: an application profile requiring a successful run refuses a
-- target that contains only earlier, individually committed units.

CREATE TABLE catalog_unified_migration_runs (
    public_id UUID PRIMARY KEY,
    source_id TEXT NOT NULL,
    source_manifest_sha256 CHAR(64) NOT NULL CHECK (source_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('STAGING', 'SUCCEEDED', 'FAILED')),
    failure_code TEXT,
    failure_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_catalog_unified_migration_successful_source
    ON catalog_unified_migration_runs(source_id, source_manifest_sha256)
    WHERE status = 'SUCCEEDED';
CREATE INDEX idx_catalog_unified_migration_runs_status
    ON catalog_unified_migration_runs(status, started_at DESC);

CREATE TABLE catalog_unified_migration_steps (
    run_id UUID NOT NULL REFERENCES catalog_unified_migration_runs(public_id) ON DELETE CASCADE,
    step_key TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'NOOP_REIMPORT', 'FAILED')),
    detail_json JSONB,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, step_key)
);
