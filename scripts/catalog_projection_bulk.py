#!/usr/bin/env python3
"""Read-only coverage gate and offline, insert-only bulk SQL preparation.

No database connection or production apply command. Never repairs partial or
conflicting records. The reviewed coordinated writer is a separate boundary.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path, PurePosixPath

from catalog_creation_projection_repair import render, literal, require

FIELDS = ('painting_id', 'asset_id', 'input_batch_id', 'input_revision',
          'asset_batch_id', 'asset_revision', 'relative_path', 'storage_uri',
          'sha256', 'file_size', 'mime_type', 'title', 'author', 'category')
PRESERVED_TABLES = frozenset(('paintings', 'media_assets', 'catalog_creation_painting_projection',
    'creations', 'creation_steps', 'creation_execution_attempts', 'creation_step_dispatch_attempts',
    'creation_catalog_input_snapshots', 'catalog_paintings', 'catalog_media_assets',
    'catalog_source_records', 'catalog_batch_current_revisions', 'catalog_batch_revisions',
    'catalog_identity_reservations', 'catalog_import_claims', 'catalog_import_attempts'))


def preservation_guard(expected, *, after=False):
    import re
    require(PRESERVED_TABLES.issubset(expected) and all(re.fullmatch('[a-z_][a-z0-9_]*',x) for x in expected),
            'COMPLETE_PRESERVATION_SCOPE_REQUIRED')
    clauses=[f"IF (SELECT jsonb_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public') IS DISTINCT FROM {literal(json.dumps(sorted(expected)))}::jsonb THEN RAISE EXCEPTION 'PUBLIC_TABLE_INVENTORY_DRIFT'; END IF;"]
    for table, value in sorted(expected.items()):
        require(set(value)=={'count','sha256'} and type(value['count']) is int and value['count']>=0,
                'PRESERVATION_SCHEMA')
        require(re.fullmatch('[0-9a-f]{64}',value['sha256']) is not None,'PRESERVATION_SHA256')
        where=''
        if after and table in ('paintings','media_assets'):
            key='painting_id' if table=='paintings' else 'asset_id'
            where=f' WHERE public_id NOT IN (SELECT {key}::text FROM expected_projection)'
        if after and table=='catalog_creation_painting_projection':
            where=' WHERE catalog_painting_id NOT IN (SELECT painting_id FROM expected_projection)'
        expr=f"(SELECT jsonb_build_object('count',count(*),'sha256',encode(sha256(convert_to(COALESCE(string_agg(row_hash,'' ORDER BY row_hash),''),'UTF8')),'hex')) FROM(SELECT encode(sha256(convert_to(to_jsonb(x)::text,'UTF8')),'hex')AS row_hash FROM {table} x{where})h)"
        clauses.append(f"IF {expr} IS DISTINCT FROM {literal(json.dumps(value))}::jsonb THEN RAISE EXCEPTION 'PRESERVATION_DRIFT_{table}'; END IF;")
    return '\n'.join(clauses)


def indexed(rows, key):
    result = defaultdict(list)
    for row in rows:
        result[row[key]].append(row)
    return result


def classify(snapshot):
    """Classify every current displayable record; return identities and reasons.

    Existing valid projections need not use the new planner's timestamps or
    metadata defaults. They must agree on immutable IDs, actual media and all
    predicates used by the installed source resolver and input materializer.
    """
    ps = snapshot['paintings']; assets = snapshot['assets']; ledgers = snapshot['projections']
    pids = indexed(ps, 'public_id'); pkeys = indexed(ps, 'source_key')
    aids = indexed(assets, 'public_id'); akeys = indexed(assets, 'storage_key')
    lp = indexed(ledgers, 'catalog_painting_id'); la = indexed(ledgers, 'catalog_asset_id')
    ljp = indexed(ledgers, 'jpa_painting_id'); lja = indexed(ledgers, 'jpa_asset_id')
    cp = Counter(r['painting_id'] for r in snapshot['catalog'])
    ca = Counter(r['asset_id'] for r in snapshot['catalog'])
    result = []
    for c in sorted(snapshot['catalog'], key=lambda x: x['painting_id']):
        pid, aid = c['painting_id'], c['asset_id']
        storage = 'catalog/' + str(aid) + '/' + PurePosixPath(c['relative_path'] or '').name
        p = pids[pid]; a = aids[aid]; l = lp[pid]; reasons = []
        if cp[pid] != 1 or ca[aid] != 1:
            reasons.append('AMBIGUOUS_CANONICAL_IDENTITY')
        if c['catalog_status'] != 'ACTIVE' or c['image_display_status'] != 'READY' or c['media_status'] != 'ACTIVE':
            reasons.append('CANONICAL_INELIGIBLE')
        if len(p) > 1 or len(a) > 1 or len(l) > 1:
            reasons.append('DUPLICATE_PROJECTION_IDENTITY')
        if any(x['public_id'] != pid for x in pkeys['catalog-projection:' + pid]):
            reasons.append('PAINTING_SOURCE_KEY_COLLISION')
        if any(x['public_id'] != aid for x in akeys[storage]):
            reasons.append('ASSET_STORAGE_KEY_COLLISION')
        if any(x['catalog_painting_id'] != pid for x in la[aid]):
            reasons.append('LEDGER_ASSET_COLLISION')
        if p:
            q = p[0]
            expected = dict(source_key='catalog-projection:' + pid, status='ACTIVE', image_available=True,
                            visible_in_gallery=True, image_storage_name=c['relative_path'])
            reasons += ['PAINTING_' + k.upper() for k, v in expected.items() if q[k] != v]
            if q['image_asset_id'] is not None and (not a or q['image_asset_id'] != a[0]['id']):
                reasons.append('PAINTING_IMAGE_LINK_CONFLICT')
            if a and q['image_asset_id'] is None:
                reasons.append('PAINTING_IMAGE_LINK_MISSING')
            if any(x['catalog_painting_id'] != pid for x in ljp[q['id']]):
                reasons.append('LEDGER_PAINTING_ID_COLLISION')
        if a:
            q = a[0]
            expected = dict(owner_user_id=None, storage_key=storage, sha256=c['sha256'],
                            asset_type='IMAGE', semantic_type='IMAGE', source_type='CATALOG_REFERENCE',
                            visibility='PUBLIC', status='ACTIVE')
            reasons += ['ASSET_' + k.upper() for k, v in expected.items() if q[k] != v]
            if any(x['catalog_painting_id'] != pid for x in lja[q['id']]):
                reasons.append('LEDGER_ASSET_ID_COLLISION')
        if l:
            q = l[0]
            expected = dict(catalog_asset_id=aid, input_batch_id=c['input_batch_id'], input_revision=c['input_revision'])
            if p: expected['jpa_painting_id'] = p[0]['id']
            if a: expected['jpa_asset_id'] = a[0]['id']
            reasons += ['LEDGER_' + k.upper() for k, v in expected.items() if q[k] != v]
            if not p or not a:
                reasons.append('LEDGER_LINK_WITHOUT_MATCHING_IDENTITY')
        state = ('CONFLICT' if reasons else 'VALID' if p and a and l else
                 'FULLY_MISSING' if not p and not a and not l else 'PARTIAL')
        result.append(dict(painting_id=pid, asset_id=aid, input_batch_id=c['input_batch_id'],
                           state=state, present=dict(painting=bool(p), asset=bool(a), ledger=bool(l)),
                           reasons=sorted(set(reasons))))
    return dict(counts=dict(Counter(x['state'] for x in result)), records=result,
                creation_ready=all(x['state'] == 'VALID' for x in result))


def render_bulk(rows, preservation=None):
    require(type(rows) is list and 0 < len(rows) <= 10000, 'BOUNDED_NONEMPTY_SCOPE_REQUIRED')
    # Reuse the reviewed single-record validator, including exact fields and
    # quoting rules. No single-record SQL is executed.
    for row in rows:
        render(row)
        require(len(row['relative_path'])<=512 and len(PurePosixPath(row['relative_path']).name)<=512,
                'JPA_PATH_LENGTH')
    require(rows == sorted(rows, key=lambda r: r['painting_id']), 'SORTED_SCOPE_REQUIRED')
    for name in ('painting_id', 'asset_id'):
        require(len({r[name] for r in rows}) == len(rows), 'DUPLICATE_SCOPE_IDENTITY')
    payload = literal(json.dumps(rows, ensure_ascii=False, separators=(',', ':')))
    n = len(rows)
    before = preservation_guard(preservation) if preservation is not None else ''
    after = preservation_guard(preservation, after=True) if preservation is not None else ''
    return f"""-- Offline review candidate. Requires separately approved coordinated writer.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='30s';
SET LOCAL idle_in_transaction_session_timeout='30s';
SET LOCAL standard_conforming_strings=on;
LOCK TABLE catalog_batch_current_revisions, catalog_source_records,
 catalog_paintings, catalog_media_assets IN SHARE MODE;
LOCK TABLE paintings, media_assets, catalog_creation_painting_projection IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE expected_projection ON COMMIT DROP AS
 SELECT x.*, 'catalog-projection:'||painting_id::text AS source_key,
 'catalog/'||asset_id::text||'/'||regexp_replace(relative_path,'^.*/','') AS storage_key,
 regexp_replace(relative_path,'^.*/','') AS filename
 FROM jsonb_to_recordset({payload}::jsonb) AS x(
 painting_id uuid,asset_id uuid,input_batch_id uuid,input_revision int,
 asset_batch_id uuid,asset_revision int,relative_path text,storage_uri text,
 sha256 text,file_size bigint,mime_type text,title text,author text,category text);
DO $bulk_guard$
BEGIN
 {before}
 IF (SELECT count(*) FROM expected_projection) <> {n} OR EXISTS (
  SELECT 1 FROM expected_projection e WHERE (SELECT count(*) FROM catalog_paintings p
   JOIN catalog_batch_current_revisions cr ON cr.batch_id=p.current_batch_id AND cr.revision=p.current_revision
   JOIN catalog_source_records sr ON sr.painting_id=p.public_id AND sr.batch_id=p.current_batch_id AND sr.revision=p.current_revision
   JOIN catalog_media_assets m ON m.public_id=p.image_asset_id
   WHERE p.public_id=e.painting_id AND p.current_batch_id=e.input_batch_id AND p.current_revision=e.input_revision
    AND p.catalog_status='ACTIVE' AND p.image_display_status='READY' AND m.media_status='ACTIVE'
    AND m.public_id=e.asset_id AND m.source_batch_id=e.asset_batch_id AND m.source_revision=e.asset_revision
    AND m.relative_path=e.relative_path AND m.storage_uri=e.storage_uri AND m.content_sha256=e.sha256
    AND p.title IS NOT DISTINCT FROM e.title AND p.author_name IS NOT DISTINCT FROM e.author
    AND p.category IS NOT DISTINCT FROM e.category) <> 1
 ) THEN RAISE EXCEPTION 'BULK_CANONICAL_CHANGED_OR_INELIGIBLE'; END IF;
 IF EXISTS (SELECT 1 FROM expected_projection e JOIN paintings p ON p.public_id=e.painting_id::text OR p.source_key=e.source_key)
 OR EXISTS (SELECT 1 FROM expected_projection e JOIN media_assets a ON a.public_id=e.asset_id::text OR a.storage_key=e.storage_key)
 OR EXISTS (SELECT 1 FROM expected_projection e JOIN catalog_creation_painting_projection l
   ON l.catalog_painting_id=e.painting_id OR l.catalog_asset_id=e.asset_id)
 THEN RAISE EXCEPTION 'BULK_ALREADY_EXISTS_PARTIAL_OR_CONFLICT'; END IF;
END $bulk_guard$;
INSERT INTO media_assets(public_id,storage_key,original_filename,mime_type,file_size,sha256,
 asset_type,semantic_type,source_type,visibility,status,created_at,updated_at)
 SELECT asset_id::text,storage_key,filename,mime_type,file_size,sha256,
 'IMAGE','IMAGE','CATALOG_REFERENCE','PUBLIC','ACTIVE',clock_timestamp(),clock_timestamp()
 FROM expected_projection ORDER BY painting_id;
INSERT INTO paintings(public_id,source_key,image_storage_name,title,author_name,category,
 image_asset_id,image_available,visible_in_gallery,status,created_at,updated_at)
 SELECT e.painting_id::text,e.source_key,e.relative_path,e.title,e.author,e.category,
 a.id,TRUE,TRUE,'ACTIVE',clock_timestamp(),clock_timestamp()
 FROM expected_projection e JOIN media_assets a ON a.public_id=e.asset_id::text ORDER BY e.painting_id;
INSERT INTO catalog_creation_painting_projection(catalog_painting_id,jpa_painting_id,catalog_asset_id,
 jpa_asset_id,input_batch_id,input_revision)
 SELECT e.painting_id,p.id,e.asset_id,a.id,e.input_batch_id,e.input_revision
 FROM expected_projection e JOIN paintings p ON p.public_id=e.painting_id::text
 JOIN media_assets a ON a.id=p.image_asset_id ORDER BY e.painting_id;
DO $bulk_guard$
BEGIN
 {after}
 IF (SELECT count(*) FROM expected_projection e JOIN catalog_creation_painting_projection l
   ON l.catalog_painting_id=e.painting_id AND l.catalog_asset_id=e.asset_id
   AND l.input_batch_id=e.input_batch_id AND l.input_revision=e.input_revision
   JOIN paintings p ON p.id=l.jpa_painting_id AND p.public_id=e.painting_id::text
     AND p.source_key=e.source_key AND p.image_storage_name=e.relative_path
     AND p.title IS NOT DISTINCT FROM e.title AND p.author_name IS NOT DISTINCT FROM e.author
     AND p.category IS NOT DISTINCT FROM e.category AND p.status='ACTIVE'
     AND p.image_available AND p.visible_in_gallery
   JOIN media_assets a ON a.id=l.jpa_asset_id AND a.id=p.image_asset_id AND a.public_id=e.asset_id::text
     AND a.storage_key=e.storage_key AND a.sha256=e.sha256 AND a.file_size=e.file_size
     AND a.mime_type=e.mime_type AND a.original_filename=e.filename AND a.owner_user_id IS NULL
     AND a.asset_type='IMAGE' AND a.semantic_type='IMAGE' AND a.source_type='CATALOG_REFERENCE'
     AND a.visibility='PUBLIC' AND a.status='ACTIVE') <> {n}
 THEN RAISE EXCEPTION 'BULK_POSTIMAGE_INCOMPLETE'; END IF;
END $bulk_guard$;
COMMIT;
"""


def inspection_sql(rows, preservation, *, after=False):
    """Read-only preflight/reconciliation; no write locks, providers or inserts."""
    import re
    sql=render_bulk(rows,preservation)
    prefix,select=sql.split('CREATE TEMP TABLE expected_projection ON COMMIT DROP AS\n',1)
    select=select.split(';\nDO $bulk_guard$',1)[0]
    prefix=prefix.replace('BEGIN;', 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;',1)
    prefix=re.sub(r'LOCK TABLE [^;]+;\n','',prefix)
    body=(sql.rsplit('DO $bulk_guard$',1)[1] if after else
          sql.split('DO $bulk_guard$',1)[1].split('INSERT INTO media_assets',1)[0])
    if not after: body+='COMMIT;\n'
    tag='$inspection_guard$'
    while tag in select:tag=tag[:-1]+'x$'
    body=body.replace('$bulk_guard$',tag).replace('expected_projection','('+select+')')
    # PG16 permits derived SELECTs without aliases. No temp table creation is
    # attempted in a READ ONLY transaction, and no permanent object is touched.
    return prefix+'DO '+tag+body


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    for name in ('coverage', 'prepare'):
        s = sub.add_parser(name); s.add_argument('input', type=Path); s.add_argument('output', type=Path)
    a = p.parse_args(); value = json.loads(a.input.read_text())
    if a.command == 'coverage':
        out = classify(value.get('data', value)); raw = (json.dumps(out, ensure_ascii=False, indent=2)+'\n').encode()
    else:
        require(value['schema'] == 'catalog-creation-bulk-v1', 'MANIFEST_SCHEMA')
        raw = render_bulk(value['records'],value.get('preservation')).encode(); out = {}
    with a.output.open('xb') as stream: stream.write(raw)
    print(json.dumps({'command': a.command, 'sha256': hashlib.sha256(raw).hexdigest(),
                      'counts': out.get('counts'), 'production_applied': False}))
    if a.command == 'coverage' and not out['creation_ready']:
        raise SystemExit(2)  # Mandatory gate for claiming Creation-ready publication.


if __name__ == '__main__': main()
