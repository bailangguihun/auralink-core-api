#!/usr/bin/env python3
"""CPU-only disposable-cluster regression. No production access or provider calls.

Requires an explicit NEW fixture directory and a PostgreSQL binary directory.
Always stops the cluster it starts. Keeps raw test evidence for inspection.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('preparation_tests', ROOT/'tests/test_catalog_creation_projection_repair.py')
fixture_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_module)
row = fixture_module.fixture()
render = fixture_module.module.render
literal = fixture_module.module.literal


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--fixture', type=Path, required=True)
    ap.add_argument('--pg-bin', type=Path, required=True)
    ap.add_argument('--pg-lib', type=Path, required=True)
    ap.add_argument('--port', type=int, required=True)
    a = ap.parse_args()
    os.umask(0o077)
    a.fixture = a.fixture.resolve()
    a.fixture.mkdir(mode=0o700)  # Existing evidence is never cleared/reused.
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', a.port))
    env = {k:v for k,v in os.environ.items() if not k.startswith(('PG', 'ASSISTANT_TEST_'))}
    env['LD_LIBRARY_PATH'] = str(a.pg_lib)
    results = []
    def run(label, argv, data=None, ok=True, timeout=30, extra=None):
        started = time.monotonic()
        p = subprocess.run(argv, input=data, text=True, capture_output=True,
                           env=dict(env, **(extra or {})), timeout=timeout)
        (a.fixture/(label+'.log')).write_text(p.stdout+'\n'+p.stderr)
        results.append(dict(label=label, exit=p.returncode, seconds=time.monotonic()-started))
        if ok: assert p.returncode == 0, label+' failed; see private fixture log'
        return p
    ctl = [str(a.pg_bin/'pg_ctl'), '-D', str(a.fixture/'data')]
    run('initdb', [str(a.pg_bin/'initdb'), '-D', str(a.fixture/'data'), '--auth=trust', '--encoding=UTF8', '--locale=C'])
    started = False
    try:
        run('start', ctl+['-w','-l',str(a.fixture/'postgres.log'),'-o',f"-h 127.0.0.1 -p {a.port} -c unix_socket_directories=''",'start'])
        started = True
        run('createdb', [str(a.pg_bin/'createdb'), '-h','127.0.0.1','-p',str(a.port),'catalog_projection_test'])
        psql = [str(a.pg_bin/'psql'), '-X','-qAt','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',str(a.port),'-d','catalog_projection_test']
        def sql(label, text, ok=True): return run(label, psql, text, ok)
        schema = (ROOT/'tests/fixtures/catalog_projection_schema.sql').read_text()
        def seed():
            sql('fixture-reset', 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;\n'+schema)
            q={k:literal(v) if isinstance(v,str) else str(v) for k,v in row.items()}
            sql('fixture-seed', f"""
INSERT INTO catalog_batch_revisions VALUES ({q['input_batch_id']},1);
INSERT INTO catalog_batch_current_revisions VALUES ({q['input_batch_id']},1);
INSERT INTO catalog_paintings VALUES ({q['painting_id']},{q['input_batch_id']},1,'ACTIVE','READY',{q['asset_id']},{q['title']},{q['author']},{q['category']});
INSERT INTO catalog_media_assets VALUES ({q['asset_id']},{q['asset_batch_id']},1,'ACTIVE',{q['relative_path']},{q['storage_uri']},{q['sha256']});
INSERT INTO catalog_source_records VALUES ({q['painting_id']},{q['input_batch_id']},1);
INSERT INTO media_assets(public_id,storage_key,original_filename,asset_type,semantic_type,source_type,visibility,status,created_at,updated_at)
VALUES('historical-sentinel','unchanged-sentinel','sentinel','IMAGE','IMAGE','UPLOAD','PRIVATE','ACTIVE','2020-01-01','2020-01-01');
""")
        snapshot_sql="SELECT json_build_array((SELECT coalesce(json_agg(t ORDER BY id),'[]') FROM paintings t),(SELECT coalesce(json_agg(t ORDER BY id),'[]') FROM media_assets t),(SELECT coalesce(json_agg(t ORDER BY catalog_painting_id),'[]') FROM catalog_creation_painting_projection t))::text;"
        def snapshot(label): return sql(label,snapshot_sql).stdout.strip()
        def jpa(label, expected):
            p=run(label,['mvn','-o','-B','-ntp','-Dtest=CatalogProjectionJpaTest','test'],timeout=180,
                  extra={'CATALOG_REPAIR_TEST_JDBC_URL':f'jdbc:postgresql://127.0.0.1:{a.port}/catalog_projection_test',
                         'CATALOG_REPAIR_TEST_EXPECTED':expected})
            assert 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' in p.stdout
        seed()
        jpa('JPA_BEFORE', 'missing')
        sql('REPAIR_SUCCESS', render(row))
        jpa('JPA_AFTER', 'repaired')
        assert sql('SENTINEL_PRESERVED', "SELECT count(*) FROM media_assets WHERE public_id='historical-sentinel' AND created_at='2020-01-01' AND updated_at='2020-01-01' AND visibility='PRIVATE';").stdout.strip()=='1'
        before=snapshot('before-replay')
        p=sql('REPLAY_REJECTED',render(row),False)
        assert p.returncode!=0 and 'PROJECTION_ALREADY_EXISTS_OR_PARTIAL' in p.stderr
        assert before==snapshot('after-replay')
        negative={
            'inactive-painting':"UPDATE catalog_paintings SET catalog_status='INACTIVE'",
            'unready-image':"UPDATE catalog_paintings SET image_display_status='MISSING'",
            'inactive-media':"UPDATE catalog_media_assets SET media_status='INACTIVE'",
            'changed-digest':"UPDATE catalog_media_assets SET content_sha256=repeat('b',64)",
            'changed-metadata':"UPDATE catalog_paintings SET title='changed'",
            'stale-revision':"UPDATE catalog_batch_current_revisions SET revision=2",
            'missing-membership':"DELETE FROM catalog_source_records",
            'partial-projection':"UPDATE media_assets SET public_id='43dce8c3-bd8d-5837-893c-735b2d379987' WHERE public_id='historical-sentinel'",
        }
        for label, change in negative.items():
            seed(); sql(label+'-setup',change); before=snapshot(label+'-before')
            p=sql(label,render(row),False); assert p.returncode!=0
            assert before==snapshot(label+'-after')
        seed()
        sql('atomic-trigger',"CREATE FUNCTION reject_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'ISOLATED_INJECTED_LEDGER_FAILURE'; END $$; CREATE TRIGGER reject_projection BEFORE INSERT ON catalog_creation_painting_projection FOR EACH ROW EXECUTE FUNCTION reject_projection();")
        before=snapshot('atomic-before');p=sql('ATOMIC_FAILURE',render(row),False)
        assert 'ISOLATED_INJECTED_LEDGER_FAILURE' in p.stderr and before==snapshot('atomic-after')
        seed()
        # Holder explicitly signals after acquiring the lock. No incidental race.
        holder=subprocess.Popen(psql,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
        holder.stdin.write("BEGIN; LOCK TABLE media_assets IN ACCESS EXCLUSIVE MODE; SELECT 'LOCK_HELD'; SELECT pg_sleep(4); COMMIT;\n");holder.stdin.close()
        assert holder.stdout.readline().strip()=='LOCK_HELD'
        p=sql('BOUNDED_LOCK_REFUSAL',render(row),False)
        assert p.returncode!=0 and 'lock timeout' in p.stderr
        assert holder.wait(timeout=10)==0
        assert sql('NO_PARTIAL_ON_CONTENTION','SELECT count(*) FROM paintings;').stdout.strip()=='0'
        results.append({'label':'ALL_TARGETED_ASSERTIONS','result':'PASS','provider_calls':0,'production_connections':0})
    finally:
        if started: run('stop',ctl+['-m','fast','-w','stop'])
        (a.fixture/'RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps({'result':'PASS','fixture':str(a.fixture),'production_connections':0,'provider_calls':0}))


if __name__=='__main__': main()
