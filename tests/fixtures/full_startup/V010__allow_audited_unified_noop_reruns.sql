-- V009 intentionally records the readiness state.  A source package can be
-- re-run safely after success, however: each underlying importer has its own
-- immutable fingerprint ledger and returns NOOP_REIMPORT without synchronizing
-- target edits.  Keep every unified audit run instead of rejecting a lawful
-- no-op at the run-ledger boundary.

DROP INDEX uq_catalog_unified_migration_successful_source;

CREATE INDEX idx_catalog_unified_migration_successful_source
    ON catalog_unified_migration_runs(source_id, source_manifest_sha256, finished_at DESC)
    WHERE status = 'SUCCEEDED';
