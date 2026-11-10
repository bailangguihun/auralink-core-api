#!/usr/bin/env python3
"""Disposable loopback PostgreSQL: full migration chain plus actual history deletion ORM/HTTP tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
for name in ('fixture', 'pg-bin', 'pg-lib'):
    p.add_argument('--' + name, type=Path, required=True)
p.add_argument('--port', type=int, required=True)
a = p.parse_args()
os.umask(0o077)
a.fixture = a.fixture.resolve()
a.fixture.mkdir(mode=0o700)
with socket.socket() as s:
    s.bind(('127.0.0.1', a.port))
env = {k: v for k, v in os.environ.items() if not k.startswith(('PG', 'ASSISTANT_TEST_', 'CATALOG_REPAIR_'))}
env.update(LD_LIBRARY_PATH=str(a.pg_lib), PGHOST='127.0.0.1', PGPORT=str(a.port))
steps = []

def run(label, argv, data=None, timeout=60):
    result = subprocess.run([str(x) for x in argv], input=data, capture_output=True, text=True,
                            env=env, cwd=ROOT, timeout=timeout)
    (a.fixture / (label + '.log')).write_text(result.stdout + '\n' + result.stderr)
    steps.append({'phase': label, 'exit': result.returncode})
    assert result.returncode == 0, label + ' failed; inspect fixture log'
    return result.stdout.strip()

ctl = [a.pg_bin/'pg_ctl', '-D', a.fixture/'data']
psql = [a.pg_bin/'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-w', '-d', 'studio_migration_test']
started = False
try:
    run('initdb', [a.pg_bin/'initdb', '-D', a.fixture/'data', '-L', a.pg_bin.parents[3]/'share/postgresql/16',
                   '--auth=trust', '--encoding=UTF8', '--locale=C'])
    run('start', ctl + ['-w', '-l', a.fixture/'postgres.log', '-o',
                        f"-h 127.0.0.1 -p {a.port} -c unix_socket_directories=''", 'start'])
    started = True
    run('create-migration-db', [a.pg_bin/'createdb', 'studio_migration_test'])
    run('create-orm-db', [a.pg_bin/'createdb', 'studio_history_test'])
    migrations = sorted((ROOT/'tests/fixtures/full_startup').glob('V*.sql'))
    migrations += sorted((ROOT/'db/postgres/migrations').glob('V*.sql'))
    assert len(migrations) == 17
    for path in migrations:
        run(path.stem, psql, 'BEGIN;\n' + path.read_text() + '\nCOMMIT;')
    column = run('verify-column', psql, "SELECT data_type FROM information_schema.columns WHERE table_name='creations' AND column_name='history_deleted_at';")
    assert column == 'timestamp without time zone'
    index = run('verify-index', psql, "SELECT indexdef FROM pg_indexes WHERE indexname='idx_creations_visible_history';")
    assert 'WHERE (history_deleted_at IS NULL)' in index
    run('orm-http', ['mvn', '-B', '-ntp', '-Dtest=CreationHistoryDeletionTest',
        f'-Dstudio.history.jdbc=jdbc:postgresql://127.0.0.1:{a.port}/studio_history_test', 'test'], timeout=180)
    result = {'status': 'PASS', 'migrations': len(migrations), 'providerCalls': 0, 'productionConnections': 0,
              'migrationSha256': hashlib.sha256(migrations[-1].read_bytes()).hexdigest()}
finally:
    if started:
        run('stop', ctl + ['-m', 'fast', '-w', 'stop'])
    (a.fixture/'RESULT.json').write_text(json.dumps({**locals().get('result', {'status': 'FAIL'}), 'steps': steps}, indent=2))
print(json.dumps(result))
