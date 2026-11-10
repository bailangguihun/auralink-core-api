-- Apply through the managed PostgreSQL migration path before installing this Core version.
-- No rows or media are removed; old application versions can still read this schema.
ALTER TABLE creations ADD COLUMN history_deleted_at TIMESTAMP;
CREATE INDEX idx_creations_visible_history
    ON creations (user_id, created_at DESC, public_id DESC)
    WHERE history_deleted_at IS NULL;
