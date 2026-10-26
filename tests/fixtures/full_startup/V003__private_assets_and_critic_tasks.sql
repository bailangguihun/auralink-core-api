-- Local prototype only.  Private media metadata and the complete Critic task
-- lifecycle live with catalog_users and catalog_paintings.  Files remain under
-- a separately configured contained root; this migration stores logical keys
-- and verified content metadata, never image blobs or absolute paths.

CREATE TABLE catalog_private_media_assets (
    public_id UUID PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES catalog_users(id) ON DELETE RESTRICT,
    storage_key TEXT NOT NULL UNIQUE,
    original_filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size >= 0),
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    width INTEGER,
    height INTEGER,
    duration_seconds DOUBLE PRECISION,
    asset_type TEXT NOT NULL,
    semantic_type TEXT NOT NULL,
    source_type TEXT NOT NULL,
    visibility TEXT NOT NULL CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'MISSING')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_catalog_private_assets_owner_created
    ON catalog_private_media_assets(owner_user_id, created_at DESC, public_id);
CREATE INDEX idx_catalog_private_assets_owner_status
    ON catalog_private_media_assets(owner_user_id, status);
CREATE INDEX idx_catalog_private_assets_sha256
    ON catalog_private_media_assets(content_sha256);

CREATE TABLE catalog_painting_critic_tasks (
    public_id UUID PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES catalog_users(id) ON DELETE RESTRICT,
    painting_id UUID NOT NULL REFERENCES catalog_paintings(public_id) ON DELETE RESTRICT,
    image_asset_id UUID NOT NULL REFERENCES catalog_media_assets(public_id) ON DELETE RESTRICT,
    source_image_sha256 CHAR(64) NOT NULL CHECK (source_image_sha256 ~ '^[0-9a-f]{64}$'),
    input_batch_id UUID NOT NULL REFERENCES catalog_batches(batch_id) ON DELETE RESTRICT,
    input_revision INTEGER NOT NULL CHECK (input_revision > 0),
    input_snapshot JSONB NOT NULL,
    title_snapshot TEXT,
    profile TEXT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    evaluator_version TEXT NOT NULL,
    model_identity TEXT NOT NULL,
    prompt_schema_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'HELD_LEGACY')),
    -- QUEUED/RUNNING rows imported from SQLite are held, never dispatched;
    -- this column preserves the original source state as migration evidence.
    legacy_original_status TEXT,
    result_json JSONB,
    error_code TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_catalog_critic_task_input_revision
        FOREIGN KEY (input_batch_id, input_revision)
        REFERENCES catalog_batch_revisions(batch_id, revision) ON DELETE RESTRICT,
    CONSTRAINT ck_catalog_critic_task_result
        CHECK ((status = 'SUCCEEDED' AND result_json IS NOT NULL AND finished_at IS NOT NULL)
            OR (status <> 'SUCCEEDED'))
);

-- Owner-scoped current/report lookup, lease recovery, and catalog revision
-- inspection all have matching indexes.  The partial uniqueness constraint is
-- the active duplicate-submission authority under concurrent HTTP requests.
CREATE INDEX idx_catalog_critic_owner_painting_created
    ON catalog_painting_critic_tasks(owner_user_id, painting_id, created_at DESC, public_id);
CREATE INDEX idx_catalog_critic_status_lease
    ON catalog_painting_critic_tasks(status, lease_expires_at)
    WHERE status = 'RUNNING';
CREATE INDEX idx_catalog_critic_image_asset
    ON catalog_painting_critic_tasks(image_asset_id);
CREATE UNIQUE INDEX uq_catalog_critic_active_fingerprint
    ON catalog_painting_critic_tasks(owner_user_id, painting_id, input_fingerprint)
    WHERE status IN ('QUEUED', 'RUNNING');

-- One-way legacy migration ledger.  Completed fingerprints are no-op replays,
-- deliberately preserving later PostgreSQL task/media changes and removals.
CREATE TABLE catalog_legacy_critic_imports (
    source_id TEXT PRIMARY KEY,
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    mapping_sha256 CHAR(64) NOT NULL CHECK (mapping_sha256 ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status = 'SUCCEEDED'),
    imported_assets INTEGER NOT NULL CHECK (imported_assets >= 0),
    imported_tasks INTEGER NOT NULL CHECK (imported_tasks >= 0),
    imported_reports INTEGER NOT NULL CHECK (imported_reports >= 0),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE catalog_legacy_critic_import_attempts (
    attempt_id UUID PRIMARY KEY,
    source_id TEXT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    outcome TEXT NOT NULL CHECK (outcome IN ('STARTED', 'SUCCEEDED', 'NOOP_REIMPORT', 'FAILED', 'CONFLICT')),
    failure_code TEXT,
    failure_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_catalog_legacy_critic_import_attempts_source
    ON catalog_legacy_critic_import_attempts(source_id, started_at DESC);
