-- Preserve the source logical location independently from the newly prepared
-- local managed storage key.  JPA does not read this audit-only column.
ALTER TABLE media_assets ADD COLUMN legacy_source_storage_key TEXT;
