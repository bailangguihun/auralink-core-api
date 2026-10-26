-- Local prototype only.  This is a PostgreSQL-native catalog persistence slice;
-- it is intentionally separate from the deployed SQLite/Flyway history.

CREATE TABLE catalog_batches (
    batch_id UUID PRIMARY KEY,
    first_label TEXT NOT NULL,
    first_source_description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE catalog_batch_revisions (
    batch_id UUID NOT NULL REFERENCES catalog_batches(batch_id) ON DELETE RESTRICT,
    revision INTEGER NOT NULL CHECK (revision > 0),
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    contract_version TEXT NOT NULL,
    manifest_json JSONB NOT NULL,
    preview_summary JSONB NOT NULL,
    publication_status TEXT NOT NULL CHECK (publication_status = 'PUBLISHED'),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (batch_id, revision)
);

CREATE TABLE catalog_batch_current_revisions (
    batch_id UUID PRIMARY KEY REFERENCES catalog_batches(batch_id) ON DELETE RESTRICT,
    revision INTEGER NOT NULL CHECK (revision > 0),
    selected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (batch_id, revision) REFERENCES catalog_batch_revisions(batch_id, revision) ON DELETE RESTRICT
);

-- Allocations are durable reservations, not published paintings. They make new
-- IDs stable across a rolled-back business transaction and its retry.
CREATE TABLE catalog_identity_reservations (
    batch_id UUID NOT NULL,
    source_record_key TEXT NOT NULL,
    painting_id UUID NOT NULL,
    image_asset_id UUID,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (batch_id, source_record_key),
    UNIQUE (painting_id),
    UNIQUE (image_asset_id)
);

CREATE TABLE catalog_import_attempts (
    attempt_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('STARTED', 'SUCCEEDED', 'NOOP_REIMPORT', 'CONCURRENT_IN_PROGRESS', 'FAILED', 'CONFLICT')),
    failure_code TEXT,
    failure_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_catalog_import_attempts_batch_revision ON catalog_import_attempts(batch_id, revision, started_at);

-- Claims are operational import state, intentionally separate from published
-- business data. A failed import leaves a FAILED claim/attempt but no batch,
-- revision, painting, asset, source record, or annotation row.
CREATE TABLE catalog_import_claims (
    batch_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    claim_status TEXT NOT NULL CHECK (claim_status IN ('STAGING', 'PUBLISHED', 'FAILED')),
    active_attempt_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (batch_id, revision)
);

CREATE TABLE catalog_media_assets (
    public_id UUID PRIMARY KEY,
    storage_uri TEXT NOT NULL UNIQUE,
    relative_path TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    source_batch_id UUID NOT NULL,
    source_revision INTEGER NOT NULL CHECK (source_revision > 0),
    media_status TEXT NOT NULL CHECK (media_status IN ('ACTIVE', 'MISSING', 'AMBIGUOUS')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (source_batch_id, source_revision) REFERENCES catalog_batch_revisions(batch_id, revision) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_catalog_media_assets_sha256 ON catalog_media_assets(content_sha256);

CREATE TABLE catalog_paintings (
    public_id UUID PRIMARY KEY,
    current_batch_id UUID NOT NULL,
    current_revision INTEGER NOT NULL CHECK (current_revision > 0),
    image_asset_id UUID REFERENCES catalog_media_assets(public_id) ON DELETE RESTRICT,
    image_display_status TEXT NOT NULL CHECK (image_display_status IN ('READY', 'MISSING', 'AMBIGUOUS', 'UNRESOLVED', 'NOT_READY')),
    catalog_status TEXT NOT NULL CHECK (catalog_status IN ('ACTIVE', 'INCOMPLETE')),
    title TEXT,
    author_name TEXT,
    category TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (current_batch_id, current_revision) REFERENCES catalog_batch_revisions(batch_id, revision) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_catalog_paintings_gallery ON catalog_paintings(catalog_status, image_display_status, current_batch_id, current_revision);
CREATE INDEX idx_catalog_paintings_title ON catalog_paintings(title);

CREATE TABLE catalog_source_records (
    batch_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    annotation_file_id TEXT NOT NULL,
    record_number INTEGER NOT NULL CHECK (record_number > 0),
    source_record_key TEXT,
    record_state TEXT NOT NULL CHECK (record_state IN ('VALID', 'INCOMPLETE', 'INVALID', 'DUPLICATE', 'AMBIGUOUS', 'UNRESOLVED')),
    proposed_painting_id UUID,
    proposed_image_asset_id UUID,
    painting_id UUID REFERENCES catalog_paintings(public_id) ON DELETE RESTRICT,
    image_asset_id UUID REFERENCES catalog_media_assets(public_id) ON DELETE RESTRICT,
    image_resolution TEXT NOT NULL,
    image_relative_path TEXT,
    image_storage_uri TEXT,
    image_content_sha256 CHAR(64),
    raw_record JSONB NOT NULL,
    issues JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (batch_id, revision, annotation_file_id, record_number),
    FOREIGN KEY (batch_id, revision) REFERENCES catalog_batch_revisions(batch_id, revision) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_catalog_source_records_nonduplicate_key
    ON catalog_source_records(batch_id, revision, source_record_key)
    WHERE source_record_key IS NOT NULL AND record_state <> 'DUPLICATE';

CREATE INDEX idx_catalog_source_records_painting ON catalog_source_records(painting_id);

CREATE TABLE catalog_annotation_field_definitions (
    batch_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    annotation_file_id TEXT NOT NULL,
    field_key TEXT NOT NULL,
    mapping_version TEXT NOT NULL,
    original_column_name TEXT NOT NULL,
    label TEXT NOT NULL,
    value_type TEXT NOT NULL,
    unit TEXT,
    field_group TEXT NOT NULL,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    visibility TEXT NOT NULL CHECK (visibility IN ('public', 'internal', 'hidden')),
    filterability TEXT NOT NULL CHECK (filterability IN ('none', 'exact', 'facet', 'full_text', 'range')),
    core_field TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (batch_id, revision, annotation_file_id, field_key),
    FOREIGN KEY (batch_id, revision) REFERENCES catalog_batch_revisions(batch_id, revision) ON DELETE RESTRICT
);

CREATE TABLE catalog_annotation_revisions (
    batch_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    annotation_file_id TEXT NOT NULL,
    record_number INTEGER NOT NULL CHECK (record_number > 0),
    field_key TEXT NOT NULL,
    original_column_name TEXT NOT NULL,
    raw_value TEXT NOT NULL,
    normalized_value TEXT,
    value_status TEXT NOT NULL CHECK (value_status IN ('MISSING', 'PRESENT')),
    assertion_status TEXT NOT NULL CHECK (assertion_status IN ('UNSPECIFIED', 'UNKNOWN', 'INFERRED', 'CONFIRMED', 'UNRESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (batch_id, revision, annotation_file_id, record_number, field_key),
    FOREIGN KEY (batch_id, revision, annotation_file_id, record_number)
        REFERENCES catalog_source_records(batch_id, revision, annotation_file_id, record_number) ON DELETE RESTRICT,
    FOREIGN KEY (batch_id, revision, annotation_file_id, field_key)
        REFERENCES catalog_annotation_field_definitions(batch_id, revision, annotation_file_id, field_key) ON DELETE RESTRICT
);

CREATE INDEX idx_catalog_annotation_revisions_key_value
    ON catalog_annotation_revisions(field_key, normalized_value)
    WHERE value_status = 'PRESENT';
