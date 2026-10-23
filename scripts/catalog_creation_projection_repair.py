#!/usr/bin/env python3
"""Prepare an insert-only compatibility repair for review; never connect to a DB.

This is NOT a production execution entry point. A reviewed deployment transaction,
fresh approval, the shared deployment lock and an idle/CLOSED baseline are required
before applying the emitted SQL. Existing rows (including partial projections) are
refused rather than overwritten. No files, approvals or accounting are changed.
"""
import argparse
import hashlib
import json
import re
from pathlib import PurePosixPath, Path
from uuid import UUID


def require(ok, message):
    if not ok:
        raise ValueError(message)


def literal(value):
    if value is None:
        return 'NULL'
    require(isinstance(value, str) and '\x00' not in value, 'INVALID_SQL_STRING')
    return "'" + value.replace("'", "''") + "'"


def render(row):
    fields = {'painting_id', 'asset_id', 'input_batch_id', 'input_revision',
              'asset_batch_id', 'asset_revision', 'relative_path', 'storage_uri',
              'sha256', 'file_size', 'mime_type', 'title', 'author', 'category'}
    require(set(row) == fields, 'EXACT_REPAIR_FIELDS_REQUIRED')
    for key in ('painting_id', 'asset_id', 'input_batch_id', 'asset_batch_id'):
        require(str(UUID(row[key])) == row[key], 'CANONICAL_UUID_REQUIRED')
    for key in ('input_revision', 'asset_revision', 'file_size'):
        require(type(row[key]) is int and row[key] > 0, 'POSITIVE_INTEGER_REQUIRED')
    require(row['file_size'] <= 10 * 1024 * 1024, 'IMAGE_SIZE_LIMIT')
    require(re.fullmatch('[0-9a-f]{64}', row['sha256']) is not None, 'SHA256_REQUIRED')
    rel = PurePosixPath(row['relative_path'])
    require(not rel.is_absolute() and row['relative_path'] == str(rel)
            and all(x not in ('.', '..') for x in rel.parts) and '\\' not in str(rel)
            and bool(rel.name), 'SAFE_RELATIVE_IMAGE_PATH_REQUIRED')
    mime = {'.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png'}.get(rel.suffix.lower())
    require(mime is not None and row['mime_type'] == mime, 'IMAGE_MIME_EXTENSION_MISMATCH')
    for key, limit in [('title', 512), ('author', 255), ('category', 255)]:
        require(row[key] is None or isinstance(row[key], str) and len(row[key]) <= limit,
                'METADATA_LENGTH_OR_TYPE')
    q = {k: literal(v) if isinstance(v, str) or v is None else str(v) for k, v in row.items()}
    q['storage_key'] = literal('catalog/' + row['asset_id'] + '/' + rel.name)
    q['source_key'] = literal('catalog-projection:' + row['painting_id'])
    q['filename'] = literal(rel.name)
    tag = '$repair_guard$'
    while any(tag in value for value in row.values() if isinstance(value, str)):
        tag = tag[:-1] + 'x$'
    # All canonical predicates and the absence checks are evaluated in the SAME
    # transaction under bounded table locks. No stale preflight-only authorization.
    return f"""-- REVIEW CANDIDATE ONLY: requires approved coordinated production entry point.
BEGIN;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '10s';
SET LOCAL standard_conforming_strings = on;
LOCK TABLE catalog_batch_current_revisions, catalog_source_records,
  catalog_paintings, catalog_media_assets IN SHARE MODE;
LOCK TABLE paintings, media_assets, catalog_creation_painting_projection IN SHARE ROW EXCLUSIVE MODE;
DO {tag}
BEGIN
  IF (SELECT count(*) FROM catalog_paintings p
      JOIN catalog_batch_current_revisions cr ON cr.batch_id=p.current_batch_id AND cr.revision=p.current_revision
      JOIN catalog_source_records sr ON sr.painting_id=p.public_id AND sr.batch_id=p.current_batch_id AND sr.revision=p.current_revision
      JOIN catalog_media_assets m ON m.public_id=p.image_asset_id
      WHERE p.public_id={q['painting_id']}::uuid AND p.catalog_status='ACTIVE'
        AND p.image_display_status='READY' AND p.current_batch_id={q['input_batch_id']}::uuid
        AND p.title IS NOT DISTINCT FROM {q['title']} AND p.author_name IS NOT DISTINCT FROM {q['author']}
        AND p.category IS NOT DISTINCT FROM {q['category']}
        AND p.current_revision={q['input_revision']} AND m.public_id={q['asset_id']}::uuid
        AND m.media_status='ACTIVE' AND m.source_batch_id={q['asset_batch_id']}::uuid
        AND m.source_revision={q['asset_revision']} AND m.relative_path={q['relative_path']}
        AND m.storage_uri={q['storage_uri']} AND m.content_sha256={q['sha256']}) <> 1 THEN
    RAISE EXCEPTION 'CATALOG_SOURCE_CHANGED_OR_INELIGIBLE';
  END IF;
  IF EXISTS (SELECT 1 FROM paintings WHERE public_id={q['painting_id']} OR source_key={q['source_key']})
     OR EXISTS (SELECT 1 FROM media_assets WHERE public_id={q['asset_id']} OR storage_key={q['storage_key']})
     OR EXISTS (SELECT 1 FROM catalog_creation_painting_projection
                WHERE catalog_painting_id={q['painting_id']}::uuid OR catalog_asset_id={q['asset_id']}::uuid) THEN
    RAISE EXCEPTION 'PROJECTION_ALREADY_EXISTS_OR_PARTIAL_DO_NOT_OVERWRITE';
  END IF;
END {tag};
INSERT INTO media_assets(public_id, storage_key, original_filename, mime_type, file_size, sha256,
  asset_type, semantic_type, source_type, visibility, status, created_at, updated_at)
VALUES ({q['asset_id']}, {q['storage_key']}, {q['filename']}, {q['mime_type']}, {q['file_size']}, {q['sha256']},
  'IMAGE', 'IMAGE', 'CATALOG_REFERENCE', 'PUBLIC', 'ACTIVE', clock_timestamp(), clock_timestamp());
INSERT INTO paintings(public_id, source_key, image_storage_name, title, author_name, category,
  image_asset_id, image_available, visible_in_gallery, status, created_at, updated_at)
SELECT {q['painting_id']}, {q['source_key']}, {q['relative_path']}, {q['title']}, {q['author']}, {q['category']},
  id, TRUE, TRUE, 'ACTIVE', clock_timestamp(), clock_timestamp()
FROM media_assets WHERE public_id={q['asset_id']};
INSERT INTO catalog_creation_painting_projection(catalog_painting_id, jpa_painting_id, catalog_asset_id,
  jpa_asset_id, input_batch_id, input_revision)
SELECT {q['painting_id']}::uuid, p.id, {q['asset_id']}::uuid, a.id, {q['input_batch_id']}::uuid, {q['input_revision']}
FROM paintings p JOIN media_assets a ON a.id=p.image_asset_id WHERE p.public_id={q['painting_id']};
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    row = json.loads(args.snapshot.read_text())
    sql = render(row).encode()
    # Refuse accidental overwrite; output contains no connection or approval.
    with args.output.open('xb') as out:
        out.write(sql)
    print(json.dumps({'outcome': 'OFFLINE_REVIEW_CANDIDATE', 'production_approved': False,
                      'sql_sha256': hashlib.sha256(sql).hexdigest(), 'rows_to_insert': 3}))


if __name__ == '__main__':
    main()
