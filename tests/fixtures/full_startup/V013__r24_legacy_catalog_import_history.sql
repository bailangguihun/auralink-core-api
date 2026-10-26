-- Immutable audit/provenance records from R24 SQLite catalog_import_runs.
-- These rows are distinct from catalog_unified_migration_runs, which records
-- the PostgreSQL rehearsal itself.
CREATE TABLE catalog_r24_legacy_catalog_import_history (
    source_snapshot_id TEXT NOT NULL,
    legacy_import_id BIGINT NOT NULL,
    public_id UUID NOT NULL,
    source_name TEXT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    total_rows INTEGER NOT NULL,
    inserted_rows INTEGER NOT NULL,
    updated_rows INTEGER NOT NULL,
    unchanged_rows INTEGER NOT NULL,
    matched_images INTEGER NOT NULL,
    missing_images INTEGER NOT NULL,
    orphan_images INTEGER NOT NULL,
    legacy_status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    legacy_error_message TEXT,
    PRIMARY KEY (source_snapshot_id, legacy_import_id),
    UNIQUE (source_snapshot_id, public_id)
);

CREATE INDEX idx_catalog_r24_legacy_import_history_source_status_started
    ON catalog_r24_legacy_catalog_import_history(source_snapshot_id, legacy_status, started_at);
