#!/usr/bin/env python3
"""Disposable loopback PostgreSQL for owned-upload source/creation persistence and managed HTTP boundary."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess

p=argparse.ArgumentParser(description=__doc__)
for name in ('fixture','pg-bin','pg-lib','vmm-fixture-source'):p.add_argument('--'+name,type=Path,required=True)
p.add_argument('--port',type=int,required=True)
a=p.parse_args()
os.umask(0o077)
a.fixture=a.fixture.resolve();a.fixture.mkdir(mode=0o700)
with socket.socket() as s:s.bind(('127.0.0.1',a.port))
env={k:v for k,v in os.environ.items() if not k.startswith(('PG','ASSISTANT_TEST_','CATALOG_REPAIR_'))}
env['LD_LIBRARY_PATH']=str(a.pg_lib)
results=[]
def run(label,argv,timeout=45):
    r=subprocess.run(argv,capture_output=True,text=True,env=env,timeout=timeout)
    (a.fixture/(label+'.log')).write_text(r.stdout+'\n'+r.stderr)
    results.append({'label':label,'exit':r.returncode})
    assert r.returncode==0,label+' failed; see fixture log'
ctl=[str(a.pg_bin/'pg_ctl'),'-D',str(a.fixture/'data')]
run('initdb',[str(a.pg_bin/'initdb'),'-D',str(a.fixture/'data'),'--auth=trust','--encoding=UTF8','--locale=C'])
started=False
try:
    run('start',ctl+['-w','-l',str(a.fixture/'postgres.log'),'-o',f"-h 127.0.0.1 -p {a.port} -c unix_socket_directories=''",'start']);started=True
    run('createdb',[str(a.pg_bin/'createdb'),'-h','127.0.0.1','-p',str(a.port),'owned_upload_test'])
    run('jpa-and-managed-http',['mvn','-B','-ntp','-Dtest=OwnedUploadMusicSourceTest,VmmOwnedUploadBoundaryTest',
        f'-Downed.upload.jdbc=jdbc:postgresql://127.0.0.1:{a.port}/owned_upload_test',
        '-Dvmm.fixture.source='+str(a.vmm_fixture_source.resolve()),'test'],180)
finally:
    if started:run('stop',ctl+['-m','fast','-w','stop'])
    (a.fixture/'RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps({'result':'PASS','provider_calls':0,'production_connections':0,'fixture':str(a.fixture)}))
