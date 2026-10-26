-- R24's captured long-form annotations can exceed PostgreSQL's B-tree tuple
-- limit. V001's exact-value composite index was an optimization, not a data
-- constraint, so retain all values and index a bounded equality discriminator
-- instead. Queries still compare normalized_value for correctness after an
-- optional digest prefilter; no annotation is truncated or rewritten.
DROP INDEX IF EXISTS idx_catalog_annotation_revisions_key_value;

CREATE INDEX idx_catalog_annotation_revisions_key_hash
    ON catalog_annotation_revisions(field_key, md5(normalized_value))
    WHERE value_status = 'PRESENT' AND normalized_value IS NOT NULL;

CREATE INDEX idx_catalog_annotation_revisions_key_present
    ON catalog_annotation_revisions(field_key)
    WHERE value_status = 'PRESENT';
