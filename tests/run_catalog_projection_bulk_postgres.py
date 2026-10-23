#!/usr/bin/env python3
"""New disposable PG16 only: bulk transaction failures and real-row rehearsal.

Optional manifest contains public catalog metadata only, and stays outside Git.
Never connects to production or starts an application/provider.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from uuid import UUID

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from catalog_projection_bulk import render_bulk, inspection_sql, PRESERVED_TABLES
from catalog_creation_projection_repair import literal
from test_catalog_creation_projection_repair import fixture


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('fixture','pg-bin','pg-lib'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--port',type=int,required=True);p.add_argument('--manifest',type=Path)
    p.add_argument('--jpa',action='store_true')
    a=p.parse_args();os.umask(0o077);a.fixture=a.fixture.resolve();a.fixture.mkdir(mode=0o700)
    rows=(json.loads(a.manifest.read_text())['records'] if a.manifest else
          [dict(fixture(),painting_id=str(UUID(int=i+1)),asset_id=str(UUID(int=i+1001)),
                title="Synthetic ' $inspection_guard$ ; --") for i in range(12)])
    rows=sorted(rows,key=lambda r:r['painting_id']);assert len(rows)>=2
    with socket.socket() as s:s.bind(('127.0.0.1',a.port))
    env={k:v for k,v in os.environ.items()if not k.startswith(('PG','ASSISTANT_TEST_'))}
    env['LD_LIBRARY_PATH']=str(a.pg_lib);results=[];started=False;counter=0
    def run(label,argv,data=None,ok=True,timeout=90):
        nonlocal counter
        counter+=1;at=time.monotonic();r=subprocess.run(argv,input=data,text=True,capture_output=True,env=env,timeout=timeout)
        (a.fixture/f'{counter:03}-{label}.log').write_text(r.stdout+'\n'+r.stderr)
        results.append(dict(label=label,exit=r.returncode,seconds=time.monotonic()-at))
        if ok:assert r.returncode==0,label
        return r
    ctl=[str(a.pg_bin/'pg_ctl'),'-D',str(a.fixture/'data')]
    run('initdb',[str(a.pg_bin/'initdb'),'-D',str(a.fixture/'data'),'--auth=trust','--encoding=UTF8','--locale=C'])
    try:
        run('start',ctl+['-w','-l',str(a.fixture/'postgres.log'),'-o',f"-h 127.0.0.1 -p {a.port} -c unix_socket_directories=''",'start']);started=True
        run('createdb',[str(a.pg_bin/'createdb'),'-h','127.0.0.1','-p',str(a.port),'catalog_projection_test'])
        psql=[str(a.pg_bin/'psql'),'-X','-qAt','-v','ON_ERROR_STOP=1','-h','127.0.0.1','-p',str(a.port),'-d','catalog_projection_test']
        def sql(label,data,ok=True):return run(label,psql,data,ok)
        schema=(ROOT/'tests/fixtures/catalog_projection_schema.sql').read_text()
        def seed():
            statements=['DROP SCHEMA public CASCADE;CREATE SCHEMA public;',schema]
            for table in sorted(PRESERVED_TABLES):
                if ('CREATE TABLE '+table+' ') not in schema and ('CREATE TABLE '+table+'(') not in schema:
                    statements.append(f"CREATE TABLE {table}(id int, evidence text); INSERT INTO {table} VALUES(1,'historical sentinel');")
            for bid,rev in sorted({(r['input_batch_id'],r['input_revision'])for r in rows}):
                statements += [f"INSERT INTO catalog_batch_revisions VALUES('{bid}',{rev});",
                               f"INSERT INTO catalog_batch_current_revisions VALUES('{bid}',{rev});"]
            for row in rows:
                q={k:literal(v)if isinstance(v,str)or v is None else str(v)for k,v in row.items()}
                statements += [f"INSERT INTO catalog_paintings VALUES({q['painting_id']},{q['input_batch_id']},{q['input_revision']},'ACTIVE','READY',{q['asset_id']},{q['title']},{q['author']},{q['category']});",
                    f"INSERT INTO catalog_media_assets VALUES({q['asset_id']},{q['asset_batch_id']},{q['asset_revision']},'ACTIVE',{q['relative_path']},{q['storage_uri']},{q['sha256']});",
                    f"INSERT INTO catalog_source_records VALUES({q['painting_id']},{q['input_batch_id']},{q['input_revision']});"]
            sql('seed','\n'.join(statements))
        def snapshot(label):
            return sql(label,"SELECT jsonb_build_array((SELECT coalesce(jsonb_agg(x ORDER BY id),'[]')FROM paintings x),(SELECT coalesce(jsonb_agg(x ORDER BY id),'[]')FROM media_assets x),(SELECT coalesce(jsonb_agg(x ORDER BY catalog_painting_id),'[]')FROM catalog_creation_painting_projection x))::text;").stdout
        def preservation():
            expr=[]
            tables=json.loads(sql('table-inventory',"SELECT jsonb_agg(tablename ORDER BY tablename)FROM pg_tables WHERE schemaname='public';").stdout)
            for table in tables:
                expr.append(f"'{table}',(SELECT jsonb_build_object('count',count(*),'sha256',encode(sha256(convert_to(COALESCE(string_agg(row_hash,'' ORDER BY row_hash),''),'UTF8')),'hex')) FROM(SELECT encode(sha256(convert_to(to_jsonb(x)::text,'UTF8')),'hex')AS row_hash FROM {table} x)h)")
            return json.loads(sql('preservation', 'SELECT jsonb_build_object('+','.join(expr)+')::text;').stdout)
        seed();sql('existing-valid',render_bulk(rows[:1]));before=snapshot('valid-before')
        sql('bulk-success',render_bulk(rows[1:]));after=json.loads(snapshot('after-success'))
        original=json.loads(before)
        for idx in range(3):
            assert len(after[idx])==len(rows)
            assert all(r in after[idx] for r in original[idx])
        saved=snapshot('before-replay');r=sql('replay',render_bulk(rows),False)
        assert 'BULK_ALREADY_EXISTS_PARTIAL_OR_CONFLICT' in r.stderr and saved==snapshot('after-replay')
        r0=rows[0];pid=literal(r0['painting_id']);aid=literal(r0['asset_id'])
        negative={
          'partial-asset':f"INSERT INTO media_assets(public_id,storage_key,original_filename,asset_type,semantic_type,source_type,visibility,status,created_at,updated_at) VALUES({aid},'partial','partial','IMAGE','IMAGE','CATALOG_REFERENCE','PUBLIC','ACTIVE',now(),now());",
          'conflicting-source-key':f"INSERT INTO paintings(public_id,source_key,image_storage_name,status,created_at,updated_at)VALUES('unrelated','catalog-projection:'||{pid},'unrelated','ACTIVE',now(),now());",
          'stale-canonical':f"UPDATE catalog_media_assets SET content_sha256=repeat('b',64)WHERE public_id={aid}::uuid;",
          'inactive':f"UPDATE catalog_paintings SET catalog_status='INACTIVE'WHERE public_id={pid}::uuid;",
          'lost-membership':f"DELETE FROM catalog_source_records WHERE painting_id={pid}::uuid;",
        }
        for name,change in negative.items():
            seed();sql(name+'-setup',change);before=snapshot(name+'-before')
            r=sql(name,render_bulk(rows),False);assert r.returncode!=0 and before==snapshot(name+'-after')
        seed()
        # Fail on a later ledger insert, after both entire bulk tables were filled.
        failpid=literal(rows[-1]['painting_id'])
        sql('failure-trigger',f"CREATE FUNCTION injected_failure()RETURNS trigger LANGUAGE plpgsql AS $$BEGIN IF NEW.catalog_painting_id={failpid}::uuid THEN RAISE EXCEPTION 'ISOLATED_LATE_LEDGER_FAILURE';END IF;RETURN NEW;END$$;CREATE TRIGGER inject BEFORE INSERT ON catalog_creation_painting_projection FOR EACH ROW EXECUTE FUNCTION injected_failure();")
        before=snapshot('atomic-before');r=sql('atomic-failure',render_bulk(rows),False)
        assert 'ISOLATED_LATE_LEDGER_FAILURE' in r.stderr and before==snapshot('atomic-after')
        seed();before=snapshot('lock-before')
        holder=subprocess.Popen(psql,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
        holder.stdin.write("BEGIN;LOCK TABLE media_assets IN ACCESS EXCLUSIVE MODE;SELECT 'HELD';SELECT pg_sleep(4);COMMIT;\n");holder.stdin.close()
        assert holder.stdout.readline().strip()=='HELD'
        r=sql('bounded-lock',render_bulk(rows),False)
        assert r.returncode!=0 and 'lock timeout' in r.stderr and holder.wait(timeout=10)==0
        assert before==snapshot('lock-after')
        seed();baseline=preservation();before=snapshot('scope-before')
        sql('scope-drift',"INSERT INTO creations VALUES(2,'new unrelated record');")
        r=sql('scope-drift-refused',render_bulk(rows,baseline),False)
        assert 'PRESERVATION_DRIFT_creations' in r.stderr and before==snapshot('scope-after')
        seed();baseline=preservation()
        sql('historical-mutation-trigger',"CREATE FUNCTION mutate_history()RETURNS trigger LANGUAGE plpgsql AS $$BEGIN UPDATE creations SET evidence='unexpected side effect';RETURN NEW;END$$;CREATE TRIGGER history_mutation AFTER INSERT ON paintings FOR EACH ROW EXECUTE FUNCTION mutate_history();")
        before=snapshot('history-before');r=sql('postimage-drift-refused',render_bulk(rows,baseline),False)
        assert 'PRESERVATION_DRIFT_creations' in r.stderr and before==snapshot('history-after') and preservation()==baseline
        # Final repaired DB lets an optional actual JPA consumer inspect every ID.
        seed();baseline=preservation();sql('read-only-preflight',inspection_sql(rows,baseline))
        sql('final-success',render_bulk(rows,baseline))
        sql('read-only-committed-reconciliation',inspection_sql(rows,baseline,after=True))
        r=sql('read-only-replay-refused',inspection_sql(rows,baseline),False)
        assert r.returncode!=0
        if a.jpa:
            env['CATALOG_REPAIR_TEST_JDBC_URL']=f'jdbc:postgresql://127.0.0.1:{a.port}/catalog_projection_test'
            env['CATALOG_REPAIR_TEST_EXPECTED_COUNT']=str(len(rows))
            run('actual-jpa',['mvn','-o','-B','-ntp','-Dtest=CatalogProjectionBulkJpaTest','test'],timeout=180)
        result=dict(result='PASS',records=len(rows),batches=len({r['input_batch_id']for r in rows}),
                    production_connections=0,provider_calls=0,assertions=['existing_valid_preserved','bulk_success','replay_refusal',*negative,'late_failure_atomic_rollback','controlled_lock_timeout','preimage_drift_refused','postimage_side_effect_rolled_back','preservation_bound_success'])
        results.append(result);print(json.dumps(result))
    finally:
        if started:run('stop',ctl+['-m','fast','-w','stop'])
        (a.fixture/'RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
