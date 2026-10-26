-- Private real-snapshot rehearsal only.  The runtime catalog contract keeps
-- batch-relative URIs; this immutable side table retains the R24 V2 media
-- metadata and original storage key without storing file bytes or absolute
-- source paths. Catalog-root files with no SQLite media row remain only in the
-- immutable batch image inventory; this table never invents an asset UUID for
-- such an orphan file.
CREATE TABLE catalog_r24_legacy_media_provenance (
    source_snapshot_id TEXT NOT NULL,
    legacy_asset_id BIGINT NOT NULL,
    public_id UUID NOT NULL,
    asset_scope TEXT NOT NULL CHECK (asset_scope IN ('CATALOG', 'PRIVATE')),
    legacy_storage_key TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    mime_type TEXT,
    file_size BIGINT,
    content_sha256 CHAR(64),
    width INTEGER,
    height INTEGER,
    duration_seconds DOUBLE PRECISION,
    asset_type TEXT NOT NULL,
    semantic_type TEXT NOT NULL,
    source_type TEXT NOT NULL,
    visibility TEXT NOT NULL,
    legacy_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_snapshot_id, legacy_asset_id),
    UNIQUE (source_snapshot_id, public_id)
);

CREATE INDEX idx_catalog_r24_legacy_media_provenance_public
    ON catalog_r24_legacy_media_provenance(public_id);
