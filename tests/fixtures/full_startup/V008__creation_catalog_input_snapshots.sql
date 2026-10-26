-- Immutable semantic context for a Creation that consumed a catalog painting.
-- This is intentionally a side table: the R24 Creation JPA contract remains
-- compatible while historical inputs do not drift when catalog current revision changes.
CREATE TABLE creation_catalog_input_snapshots (
    creation_id BIGINT PRIMARY KEY REFERENCES creations(id) ON DELETE RESTRICT,
    catalog_painting_id UUID NOT NULL REFERENCES catalog_paintings(public_id) ON DELETE RESTRICT,
    catalog_asset_id UUID REFERENCES catalog_media_assets(public_id) ON DELETE RESTRICT,
    input_batch_id UUID NOT NULL,
    input_revision INTEGER NOT NULL CHECK (input_revision > 0),
    projected_painting_id BIGINT NOT NULL REFERENCES paintings(id) ON DELETE RESTRICT,
    snapshot_json JSONB NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (input_batch_id, input_revision)
      REFERENCES catalog_batch_revisions(batch_id, revision) ON DELETE RESTRICT
);

CREATE INDEX idx_creation_catalog_input_snapshots_catalog_revision
  ON creation_catalog_input_snapshots(catalog_painting_id, input_batch_id, input_revision);
