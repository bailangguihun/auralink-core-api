#!/usr/bin/env python3
"""Actual executable JAR, full eager Spring context, native empty PostgreSQL.

Requires an externally created network namespace containing only UP loopback
and no routes. No ambient credentials, provider calls, mock beans, startup DDL,
or production data. Every fixture directory is new and retained as evidence.
"""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('fixture', 'jar', 'pg-bin', 'pg-lib'):
        parser.add_argument('--' + key, type=Path, required=True)
    parser.add_argument('--creation-first', action='store_true', help='Also register the actual Creation config before scanning Application')
    parser.add_argument('--port', type=int, default=55907)
    a = parser.parse_args()
    os.umask(0o077)
    assert os.geteuid() != 0, 'run as a non-root fixture owner'
    links = json.loads(subprocess.check_output(['ip', '-j', 'link']))
    assert len(links) == 1 and links[0]['ifname'] == 'lo' and 'UP' in links[0]['flags'], 'isolated loopback namespace required'
    assert json.loads(subprocess.check_output(['ip', '-j', 'route'])) == []
    assert json.loads(subprocess.check_output(['ip', '-6', '-j', 'route'])) == []
    a.fixture = a.fixture.resolve(); a.jar = a.jar.resolve()
    a.pg_bin = a.pg_bin.resolve(); a.pg_lib = a.pg_lib.resolve()
    a.fixture.mkdir(mode=0o700)  # Refuse any existing evidence/database.
    env = dict(PATH='/usr/bin:/bin', LANG='C.UTF-8', LC_ALL='C.UTF-8',
               LD_LIBRARY_PATH=str(a.pg_lib), HOME=str(a.fixture), PGHOST='127.0.0.1',
               PGPORT=str(a.port), PGUSER='startup_migrator', PGDATABASE='full_startup_fixture')
    steps = []; core = None; pg_started = False; httpd = None; result = {'status': 'FAIL'}
    def run(label, args, data=None, timeout=60):
        q = subprocess.run([str(v) for v in args], input=data, text=True, capture_output=True,
                           env=env, cwd=a.fixture, timeout=timeout)
        (a.fixture/(label+'.log')).write_text(q.stdout+'\n'+q.stderr)
        steps.append(dict(phase=label, exit=q.returncode))
        assert q.returncode == 0, label + ' failed; inspect fixture log'
        return q.stdout.strip()
    ctl = [a.pg_bin/'pg_ctl', '-D', a.fixture/'data']
    psql = [a.pg_bin/'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-w']
    def sql(label, query): return run(label, psql, query)
    def free_port():
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0)); return s.getsockname()[1]
    requests = []
    class DenyExternalCalls(http.server.BaseHTTPRequestHandler):
        def handle_request(self):
            requests.append({'method': self.command, 'path': self.path})
            self.send_response(503); self.end_headers(); self.wfile.write(b'isolated external service unavailable')
        do_GET = do_POST = do_PUT = do_DELETE = handle_request
        def log_message(self, *_): pass
    try:
        run('initdb', [a.pg_bin/'initdb', '-D', a.fixture/'data', '--username=startup_migrator',
                      '--auth=trust', '--encoding=UTF8', '--locale=C'])
        run('pg-start', ctl+['-w','-l',a.fixture/'postgres.log','-o',
                            f"-h 127.0.0.1 -p {a.port} -c unix_socket_directories=''",'start'])
        pg_started = True
        run('createdb', [a.pg_bin/'createdb','full_startup_fixture'])
        assert sql('empty-db', "SELECT count(*) FROM pg_tables WHERE schemaname='public';") == '0'
        manifest = json.loads((ROOT/'tests/fixtures/full_startup/SCHEMA_MANIFEST.json').read_text())
        sql('ledger', 'CREATE TABLE prototype_schema_migrations(version text PRIMARY KEY,checksum text NOT NULL);')
        migrations = list(sorted((ROOT/'tests/fixtures/full_startup').glob('V*.sql')))
        migrations += list(sorted((ROOT/'db/postgres/migrations').glob('V*.sql')))
        assert len(migrations) == 16
        for p in migrations:
            raw=p.read_bytes(); digest=hashlib.sha256(raw).hexdigest()
            if p.name in manifest: assert digest == manifest[p.name]
            sql(p.stem, raw.decode()+f"\nINSERT INTO prototype_schema_migrations VALUES('{p.name[:4]}','{digest}');")
        sql('fixture-readiness', """INSERT INTO catalog_unified_migration_runs(public_id,source_id,source_manifest_sha256,status)
          VALUES('00000000-0000-4000-8000-000000000001','full-startup-isolated',repeat('a',64),'SUCCEEDED');
          CREATE ROLE startup_runtime LOGIN PASSWORD 'isolated_runtime_only';
          REVOKE CREATE ON SCHEMA public FROM PUBLIC;
          GRANT CONNECT ON DATABASE full_startup_fixture TO startup_runtime;
          GRANT USAGE ON SCHEMA public TO startup_runtime;
          GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO startup_runtime;
          GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO startup_runtime;""")
        tables=json.loads(sql('tables', "SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public';"))
        def snapshot(label):
            statements=[f"SELECT '{t}:'||md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM {t} t;" for t in tables]
            return sql(label,'BEGIN READ ONLY;'+''.join(statements)+'ROLLBACK;')
        before=snapshot('before-start')
        httpd=http.server.ThreadingHTTPServer(('127.0.0.1',0),DenyExternalCalls)
        threading.Thread(target=httpd.serve_forever,daemon=True).start()
        origin='http://127.0.0.1:'+str(httpd.server_port); port=free_port()
        jdbc=f'jdbc:postgresql://127.0.0.1:{a.port}/full_startup_fixture'
        token=a.fixture/'private-token'; token.write_text('isolated_fixture_token_0123456789abcdef')
        cfg={'server.address':'127.0.0.1','server.port':port,'spring.main.lazy-initialization':'false',
             'spring.datasource.url':jdbc,'spring.datasource.username':'startup_runtime','spring.datasource.password':'isolated_runtime_only',
             'spring.datasource.driver-class-name':'org.postgresql.Driver','spring.jpa.hibernate.ddl-auto':'none',
             'spring.jpa.database-platform':'org.hibernate.dialect.PostgreSQLDialect','spring.flyway.enabled':'false',
             'auralink.jwt.secret':'isolated-full-startup-fixture-signing-key-2026-not-production',
             'auralink.paintings.import-enabled':'false','auralink.unified-import.require-succeeded':'true',
             'auralink.unified-import.expected-source-id':'full-startup-isolated',
             'auralink.assistant.history-only':'false','auralink.assistant.attachment-root':a.fixture/'assistant',
             'auralink.assistant.execution.ai-base-url':origin,'auralink.assistant.execution.allow-loopback-http':'true',
             'auralink.assistant.execution.internal-token-file':token,'auralink.assistant.execution.expected-config-sha256':'a'*64,
             'auralink.assistant.execution.automatic-summaries':'false',
             'auralink.assistant.storage.quota-bytes':536870912,'auralink.assistant.storage.minimum-free-bytes':0,
             'auralink.assistant.storage.draft-lifetime':'P7D','auralink.assistant.storage.cleanup-enabled':'false',
             'auralink.vmm-managed.token-file':token,'auralink.creation-providers.mock-adapters-enabled':'false',
             'auralink.guide.service-url':origin,'auralink.guide.service-host':'127.0.0.1',
             'auralink.guide.service-port':httpd.server_port,'auralink.critic.service-url':origin}
        for name in ('assistant','assistant.execution','creations','creation-providers','workflows','vmm-managed',
                     'catalog-read','catalog-auth','catalog-guide','catalog-critic','guide','critic'):
            cfg['auralink.'+name+'.enabled']='true'
        for name in ('catalog-read','catalog-auth','catalog-guide','catalog-critic'):
            for key,value in [('jdbc-url',jdbc),('username','startup_runtime'),('password','isolated_runtime_only')]:
                cfg[f'auralink.{name}.datasource.{key}']=value
        for key,folder in [('storage.upload-dir','uploads'),('storage.audio-dir','audio'),('storage.legacy-frontend-audio-dir','legacy'),
                           ('media-assets.managed-dir','media'),('paintings.picture-dir','paintings'),('catalog-critic.private-media-root','critic'),
                           ('creation-providers.staging-dir','staging')]:
            (a.fixture/folder).mkdir(mode=0o700);cfg['auralink.'+key]=a.fixture/folder
        cfg['auralink.paintings.metadata-csv-path']=a.fixture/'disabled.csv'
        for name in ('seedream','qwen','painting-music','guide','video'):
            cfg['auralink.providers.'+name+'.base-url']=origin
        cfg['auralink.providers.painting-music.output-root']=a.fixture/'audio'
        config=a.fixture/'application.properties'
        config.write_text(''.join(str(k)+'='+str(v)+'\n' for k,v in cfg.items()))
        unpack=a.fixture/'jar';unpack.mkdir();jarhash=hashlib.sha256(a.jar.read_bytes()).hexdigest()
        with zipfile.ZipFile(a.jar) as jar:
            for entry in jar.infolist():
                if entry.filename.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):
                    assert '..' not in Path(entry.filename).parts and not entry.filename.startswith('/')
                    jar.extract(entry,unpack)
        classpath=str(unpack/'BOOT-INF/classes')+':'+str(unpack/'BOOT-INF/lib/*')
        probe=a.fixture/'probe';probe.mkdir()
        run('compile-probe',['javac','-cp',classpath,'-d',probe,ROOT/'tests/fixtures/FullApplicationStartupProbe.java'])
        log=(a.fixture/'core.log').open('xb')
        core=subprocess.Popen(['java','-Dfixture.creation-first='+str(a.creation_first).lower(),'-Dloader.path='+str(probe),'-Dloader.main=com.auralink.ops.round51.FullApplicationStartupProbe',
                               '-cp',str(a.jar),'org.springframework.boot.loader.launch.PropertiesLauncher',
                               '--spring.profiles.active=catalog-postgres-production','--spring.config.additional-location=file:'+str(config)],
                              env=env,cwd=a.fixture,stdout=log,stderr=subprocess.STDOUT)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            assert core.poll() is None, 'actual Core exited; inspect core.log'
            if (a.fixture/'SPRING_READY.json').exists():
                with opener.open(f'http://127.0.0.1:{port}/api/health',timeout=2) as response:
                    assert response.status==200;break
            time.sleep(.1)
        else:raise AssertionError('full application readiness timeout')
        ready=json.loads((a.fixture/'SPRING_READY.json').read_text());assert ready['status']=='READY'
        time.sleep(2)  # Observe actual schedulers with empty work queues.
        assert core.poll() is None and before==snapshot('after-ready'), 'startup changed fixture records'
        core.terminate();code=core.wait(timeout=30);log.close()
        assert code in (0,143), 'unclean JVM shutdown'
        with socket.socket() as s:assert s.connect_ex(('127.0.0.1',port))!=0
        assert sql('sessions-after-shutdown', "SELECT count(*) FROM pg_stat_activity WHERE usename='startup_runtime';")=='0'
        assert before==snapshot('after-shutdown')
        assert not any(r['method']!='GET' for r in requests), 'unexpected provider/business request'
        assert 'ERROR' not in (a.fixture/'core.log').read_text(), 'inspect startup/runtime errors'
        result=dict(status='PASS',creationFirst=a.creation_first,jarSha256=jarhash,spring=ready,shutdownExit=code,tables=len(tables),
                    migrations=len(migrations),databaseUnchanged=True,providerCalls=0,externalObservations=requests,
                    productionConnections=0,network='isolated namespace: loopback only, no routes',
                    productionFeatureCombination='Assistant foundation/execution + Creation/workflows/providers + managed VMM CLOSED',
                    mockBeans=False,lazyInitialization=False,paidAcceptance='NOT_RUN')
    finally:
        if core is not None and core.poll() is None:
            core.terminate();core.wait(timeout=30)
        if httpd is not None:httpd.shutdown();httpd.server_close()
        if pg_started:run('pg-stop',ctl+['-m','fast','-w','stop'])
        result['steps']=steps;result['postgresStopped']=not (a.fixture/'data/postmaster.pid').exists()
        (a.fixture/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))


if __name__=='__main__':main()
