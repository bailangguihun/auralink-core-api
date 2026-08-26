-- ROUND 9B.1 creation persistence foundation.  This migration is deliberately
-- additive so it is safe for populated V2 databases and preserves V2 foreign
-- keys, table identities, and existing indexes.

ALTER TABLE creations ADD COLUMN error_code VARCHAR(128);
ALTER TABLE creations ADD COLUMN error_message TEXT;
-- SQLite requires a non-null literal default when adding a NOT NULL column to
-- an existing table.  Every existing row is deterministically backfilled below.
ALTER TABLE creations ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT '1970-01-01 00:00:00';
ALTER TABLE creations ADD COLUMN claim_token VARCHAR(64);
ALTER TABLE creations ADD COLUMN lease_expires_at TIMESTAMP;

UPDATE creations
SET updated_at = COALESCE(finished_at, started_at, created_at)
WHERE updated_at = '1970-01-01 00:00:00';

ALTER TABLE creation_steps ADD COLUMN provider_dispatch_state VARCHAR(32) NOT NULL DEFAULT 'NOT_SENT';
ALTER TABLE creation_steps ADD COLUMN provider_request_key VARCHAR(128);

CREATE INDEX idx_creations_status_created_id
    ON creations (status, created_at, id);
CREATE INDEX idx_creations_status_lease_id
    ON creations (status, lease_expires_at, id);
CREATE INDEX idx_creations_user_created_public
    ON creations (user_id, created_at, public_id);
CREATE INDEX idx_creation_favorites_user_created_public
    ON creation_favorites (user_id, created_at, public_id);
