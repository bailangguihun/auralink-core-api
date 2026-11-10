-- SQLite development schema. PostgreSQL uses db/postgres/migrations/V017.
ALTER TABLE creations ADD COLUMN history_deleted_at TIMESTAMP;
CREATE INDEX idx_creations_visible_history
    ON creations (user_id, created_at DESC, public_id DESC)
    WHERE history_deleted_at IS NULL;
