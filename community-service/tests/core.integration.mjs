// Actual built Spring/Core HTTP, separate Node process and native PostgreSQL.
// No mocked Core routes, PGlite, AI/provider/model calls, or production targets.
import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';
import pg from 'pg';

const source = fileURLToPath(new URL('../', import.meta.url));
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function until(check, deadline = 90000) {
  const end = Date.now() + deadline;
  while (Date.now() < end) { if (await check()) return; await wait(100); }
  throw new Error('Timed out waiting for isolated process; inspect fixture logs');
}
function launch(command, args, env, cwd, log) {
  const output = createWriteStream(log, { flags: 'wx', mode: 0o600 });
  const child = spawn(command, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(output, { end: false }); child.stderr.pipe(output, { end: false });
  child.completion = new Promise((resolve, reject) => {
    child.once('error', reject); child.once('close', (code, signal) => { output.end(); resolve({ code, signal }); });
  });
  return child;
}
async function stop(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  child.kill('SIGTERM');
  let timer;
  try {
    await Promise.race([child.completion, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Owned fixture did not drain')), 25000);
    })]);
  } finally { clearTimeout(timer); }
}

test('actual Core identity/gallery and native Community process interoperability', { timeout: 180000 }, async t => {
  const databaseUrl = new URL(process.env.COMMUNITY_CORE_TEST_DATABASE_URL || '');
  assert(['postgres:', 'postgresql:'].includes(databaseUrl.protocol));
  assert.equal(databaseUrl.hostname, '127.0.0.1'); assert.equal(databaseUrl.pathname, '/community_core_fixture');
  assert(!databaseUrl.search && !databaseUrl.hash);
  const jar = resolve(process.env.COMMUNITY_CORE_TEST_JAR || '../target/auralink-backend-0.0.1-SNAPSHOT.jar');
  const jarSha = createHash('sha256').update(await readFile(jar)).digest('hex');
  const dir = process.env.COMMUNITY_CORE_TEST_OUTPUT
    ? resolve(process.env.COMMUNITY_CORE_TEST_OUTPUT) : await mkdtemp(join(tmpdir(), 'community-core-'));
  await mkdir(dir, { recursive: true, mode: 0o700 });
  const admin = new pg.Pool({ connectionString: databaseUrl.href });
  let core, community;
  t.after(async () => {
    try { await stop(community); } finally { try { await stop(core); } finally { await admin.end(); } }
  });
  const existing = await admin.query("SELECT count(*)::int AS n FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')");
  assert.equal(existing.rows[0].n, 0, 'refuse existing database; never DROP or reuse platform data');
  await admin.query(await readFile(new URL('./fixtures/core_catalog.sql', import.meta.url), 'utf8'));
  await admin.query(await readFile(new URL('../db/001_foundation.sql', import.meta.url), 'utf8'));
  await admin.query("CREATE ROLE community_core_runtime LOGIN PASSWORD 'disposable_community_runtime_only'");
  await admin.query('GRANT USAGE ON SCHEMA community TO community_core_runtime');
  await admin.query('GRANT SELECT ON ALL TABLES IN SCHEMA community TO community_core_runtime');
  await admin.query('GRANT INSERT,UPDATE ON community.profiles,community.posts,community.operations TO community_core_runtime');
  await admin.query('GRANT INSERT ON community.comments TO community_core_runtime');
  await admin.query('GRANT INSERT,DELETE ON community.reactions TO community_core_runtime');
  const paintingId = randomUUID(), batch = randomUUID(), asset = randomUUID();
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+lm9sAAAAASUVORK5CYII=', 'base64');
  const media = join(dir, 'media'); await mkdir(media, { mode: 0o700 }); await writeFile(join(media, 'fixture.png'), png);
  await admin.query('INSERT INTO catalog_batch_current_revisions VALUES($1,1)', [batch]);
  await admin.query("INSERT INTO catalog_source_records VALUES($1,1,'fixture',1,$2,'community-fixture','VALID')", [batch, paintingId]);
  await admin.query("INSERT INTO catalog_media_assets VALUES($1,$2,1,'fixture.png','fixture.png',$3,'READY')", [asset,batch,createHash('sha256').update(png).digest('hex')]);
  await admin.query("INSERT INTO catalog_paintings VALUES($1,$2,1,'真实 Core 隔离山水','测试作者','山水','ACTIVE','READY',$3)", [paintingId,batch,asset]);
  await admin.query("INSERT INTO catalog_annotation_field_definitions VALUES($1,1,'fixture','source.original_image_reference','私有路径','text',NULL,'source',1,'hidden','none','private')", [batch]);
  await admin.query("INSERT INTO catalog_annotation_revisions VALUES($1,1,'fixture',1,'source.original_image_reference','DO_NOT_EXPOSE','DO_NOT_EXPOSE','PRESENT','UNSPECIFIED')", [batch]);
  const corePort = await freePort(), communityPort = await freePort();
  const coreOrigin = 'http://127.0.0.1:' + corePort, communityOrigin = 'http://127.0.0.1:' + communityPort;
  const secret = Buffer.from('isolated-community-core-native-fixture-signing-key-2026');
  const jdbc = 'jdbc:postgresql://127.0.0.1:' + databaseUrl.port + databaseUrl.pathname;
  const config = {
    'server.address': '127.0.0.1', 'server.port': corePort,
    'spring.datasource.url': jdbc, 'spring.datasource.username': decodeURIComponent(databaseUrl.username),
    'spring.datasource.password': decodeURIComponent(databaseUrl.password), 'spring.datasource.driver-class-name': 'org.postgresql.Driver',
    'spring.jpa.database-platform': 'org.hibernate.dialect.PostgreSQLDialect', 'spring.jpa.hibernate.ddl-auto': 'none',
    'spring.flyway.enabled': 'false', 'auralink.jwt.secret': secret.toString('base64'), 'auralink.jwt.expiration': 300000,
    'auralink.catalog-read.enabled': 'true', 'auralink.catalog-auth.enabled': 'true',
    'auralink.catalog-read.datasource.jdbc-url': jdbc, 'auralink.catalog-auth.datasource.jdbc-url': jdbc,
    'auralink.catalog-read.datasource.username': decodeURIComponent(databaseUrl.username), 'auralink.catalog-auth.datasource.username': decodeURIComponent(databaseUrl.username),
    'auralink.catalog-read.datasource.password': decodeURIComponent(databaseUrl.password), 'auralink.catalog-auth.datasource.password': decodeURIComponent(databaseUrl.password),
    'auralink.catalog-read.batch-media-roots[0].batch-id': batch, 'auralink.catalog-read.batch-media-roots[0].revision': 1,
    'auralink.catalog-read.batch-media-roots[0].root': media,
    'auralink.storage.upload-dir': join(dir,'uploads'), 'auralink.storage.audio-dir': join(dir,'audios'),
    'auralink.storage.legacy-frontend-audio-dir': join(dir,'legacy-audios'), 'auralink.media-assets.managed-dir': media,
    'auralink.paintings.import-enabled': 'false', 'auralink.unified-import.require-succeeded': 'false',
    'auralink.paintings.metadata-csv-path': join(dir,'disabled.csv'), 'auralink.paintings.picture-dir': media,
    ...Object.fromEntries(['guide','critic','catalog-guide','catalog-critic','workflows','creations','creation-providers','vmm-managed','assistant','assistant-execution'].map(key=>['auralink.'+key+'.enabled','false'])),
  };
  const configFile = join(dir,'core.properties');
  await writeFile(configFile, Object.entries(config).map(([k,v])=>k+'='+String(v).replaceAll('\\','\\\\')).join('\n')+'\n', { flag:'wx',mode:0o600 });
  const env = Object.fromEntries(['PATH','JAVA_HOME','LANG','TZ'].filter(k=>process.env[k]).map(k=>[k,process.env[k]]));
  // Use the existing private-rehearsal launcher. It retains the maintenance
  // fence in this fixture's private directory rather than probing /root on CI.
  const fence = join(dir, 'maintenance'); await mkdir(fence, { mode: 0o700 });
  core = launch(process.env.JAVA_BIN || 'java', [
    '-Dloader.main=com.auralink.ops.round51.LocalPrivateRehearsalLauncher',
    '-Dauralink.local-private-rehearsal.state-dir='+fence,
    '-cp',jar,'org.springframework.boot.loader.launch.PropertiesLauncher',
    '--spring.profiles.active=catalog-postgres-production','--spring.config.additional-location=file:'+configFile,
  ], env,dir,join(dir,'core.log'));
  await until(async () => { assert.equal(core.exitCode,null,'Core exited'); try { return (await fetch(coreOrigin+'/api/health')).ok; } catch { return false; } });
  async function coreRequest(path, payload, token) {
    const r = await fetch(coreOrigin+path,{method:payload?'POST':'GET',headers:{...(token?{authorization:'Bearer '+token}:{}),...(payload?{'content-type':'application/json'}:{})},body:payload?JSON.stringify(payload):undefined});
    return { status:r.status, data:await r.json() };
  }
  const accounts = [];
  for (const suffix of ['a','b']) {
    const credentials = { username:'community_fixture_'+suffix,password:'Isolated-fixture-only-2026' };
    const created = await coreRequest('/api/auth/register',{...credentials,fullName:'隔离用户 '+suffix,email:'community-'+suffix+'@example.invalid'});
    assert.equal(created.status,200,'Actual Core fixture registration failed; inspect private logs');
    const signedIn = await coreRequest('/api/auth/login',credentials); assert.equal(signedIn.status,200);
    accounts.push(signedIn.data.data);
  }
  assert.equal(accounts[0].userId,101); assert.equal(accounts[1].userId,102);
  const [a,b] = accounts;
  const original = await coreRequest('/api/v1/paintings/'+paintingId,null,a.token);
  assert.equal(original.status,200); assert(!JSON.stringify(original.data).includes('DO_NOT_EXPOSE'));
  const runtimeUrl = new URL(databaseUrl); runtimeUrl.username='community_core_runtime';runtimeUrl.password='disposable_community_runtime_only';
  let launchCount = 0;
  async function startCommunity() {
    community = launch(process.execPath,[join(source,'src/main.mjs')],{...env,COMMUNITY_DATABASE_URL:runtimeUrl.href,
      COMMUNITY_CORE_ORIGIN:coreOrigin,COMMUNITY_WEB_ORIGIN:'http://127.0.0.1:3177',COMMUNITY_PORT:String(communityPort)},source,join(dir,'community-'+(++launchCount)+'.log'));
    await until(async()=>{assert.equal(community.exitCode,null,'Community exited');try{return(await fetch(communityOrigin+'/health')).ok;}catch{return false;}},15000);
  }
  async function request(path,{token=a.token,method='GET',body}={}) {
    const r = await fetch(communityOrigin+'/api/v1/community'+path,{method,headers:{authorization:'Bearer '+token,...(body?{'content-type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});
    return { status:r.status,data:await r.json() };
  }
  await startCommunity();
  await t.test('canonical platform accounts and current restrictions',async()=>{
    for(const account of accounts) assert.equal((await request('/capabilities',{token:account.token})).data.actor.id,String(account.userId));
    for(const field of ['enabled','account_non_locked','account_non_expired','credentials_non_expired']) {
      await admin.query('UPDATE catalog_users SET '+field+'=false WHERE id=$1',[a.userId]);
      assert.equal((await request('/capabilities')).status,401);
      await admin.query('UPDATE catalog_users SET '+field+'=true WHERE id=$1',[a.userId]);
    }
    const header=Buffer.from(JSON.stringify({alg:'HS256'})).toString('base64url');
    const payload=Buffer.from(JSON.stringify({sub:a.username,exp:Math.floor(Date.now()/1000)-60})).toString('base64url');
    const expired=header+'.'+payload+'.'+createHmac('sha256',secret).update(header+'.'+payload).digest('base64url');
    assert.equal((await request('/capabilities',{token:expired})).status,401);
    assert.equal((await request('/capabilities',{token:'invalid'})).status,401);
  });
  let postId, requestId;
  await t.test('real gallery references, writes, duplicate requests and owner isolation',async()=>{
    requestId=randomUUID();
    const command={requestId,content:'真实 Core 与社区联调',galleryArtworkId:paintingId};
    const replies=await Promise.all(Array.from({length:4},()=>request('/posts',{method:'POST',body:command})));
    assert(replies.every(r=>r.status===200),JSON.stringify(replies));postId=replies[0].data.result.postId;
    assert.equal(new Set(replies.map(r=>r.data.result.postId)).size,1);
    assert.equal((await request('/operations/'+requestId,{token:b.token})).data.state,'NOT_OBSERVED');
    assert.equal((await request('/posts/'+postId,{token:b.token,method:'DELETE',body:{requestId:randomUUID()}})).status,403);
    assert.equal((await request('/posts/'+postId+'/comments',{token:b.token,method:'POST',body:{requestId:randomUUID(),content:'第二个实际账号的评论'}})).status,200);
    assert.equal((await request('/posts/'+postId+'/like',{token:b.token,method:'PUT',body:{requestId:randomUUID(),enabled:true}})).status,200);
    assert.equal((await request('/posts/'+postId+'/favorite',{method:'PUT',body:{requestId:randomUUID(),enabled:true}})).status,200);
    const got=await request('/posts/'+postId);assert.equal(got.data.commentCount,1);assert.equal(got.data.likeCount,1);
    assert.equal(got.data.galleryReference.state,'AVAILABLE');assert(!JSON.stringify(got).includes('DO_NOT_EXPOSE'));
  });
  await t.test('source withdrawal and missing images prevent new publication',async()=>{
    for(const [field,value,restore] of [['catalog_status','INACTIVE','ACTIVE'],['image_display_status','MISSING','READY']]) {
      await admin.query('UPDATE catalog_paintings SET '+field+'=$1 WHERE public_id=$2',[value,paintingId]);
      assert.equal((await request('/posts/'+postId)).data.galleryReference.state,'UNAVAILABLE');
      assert.equal((await request('/posts',{method:'POST',body:{requestId:randomUUID(),content:'不可引用',galleryArtworkId:paintingId}})).status,404);
      await admin.query('UPDATE catalog_paintings SET '+field+'=$1 WHERE public_id=$2',[restore,paintingId]);
    }
  });
  await t.test('lost response and SIGTERM drain preserve a committed journal across process restart',async()=>{
    await admin.query("CREATE FUNCTION community.fixture_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(2); RETURN NEW; END $$");
    await admin.query('CREATE TRIGGER fixture_pause BEFORE INSERT ON community.posts FOR EACH ROW EXECUTE FUNCTION community.fixture_pause()');
    const pending=randomUUID(), abort=new AbortController();
    const lost=fetch(communityOrigin+'/api/v1/community/posts',{method:'POST',headers:{authorization:'Bearer '+a.token,'content-type':'application/json'},
      body:JSON.stringify({requestId:pending,content:'断开响应也要完成排空'}),signal:abort.signal}).catch(()=>null);
    await until(async()=>(await admin.query("SELECT count(*)::int AS n FROM pg_stat_activity WHERE application_name='artlive-community' AND wait_event='PgSleep'")).rows[0].n>0,8000);
    abort.abort(); await lost; await stop(community); assert.equal(community.exitCode,0);
    await admin.query('DROP TRIGGER fixture_pause ON community.posts');
    await startCommunity();
    const outcome=await request('/operations/'+pending);assert.equal(outcome.data.state,'COMMITTED');
    assert.equal((await admin.query('SELECT count(*)::int AS n FROM community.operations WHERE request_id=$1',[pending])).rows[0].n,1);
    assert.equal((await request('/operations/'+requestId)).data.result.postId,postId);
    assert.equal((await request('/posts/'+postId)).data.commentCount,1);
  });
  await t.test('read requests do not change existing platform or community data',async()=>{
    async function snapshot() {
      const tables=(await admin.query("SELECT schemaname,tablename FROM pg_tables WHERE schemaname IN ('public','community') ORDER BY 1,2")).rows;
      return Promise.all(tables.map(async r=>[r.schemaname,r.tablename,(await admin.query('SELECT row_to_json(t)::text AS row FROM '+r.schemaname+'.'+r.tablename+' t ORDER BY row_to_json(t)::text')).rows]));
    }
    const before=await snapshot();
    await request('/posts');await request('/posts/'+postId);await request('/posts/'+postId+'/comments');await request('/operations/'+requestId);await request('/capabilities');
    assert.deepEqual(await snapshot(),before);
    assert.deepEqual((await admin.query('SELECT * FROM users')).rows,[{id:'1',username:'community_fixture_a'}]);
  });
  await stop(community);await stop(core);
  await writeFile(join(dir,'RESULT.json'),JSON.stringify({status:'PASS',coreJarSha256:jarSha,core:'actual Spring Boot JAR',database:'native PostgreSQL',
    community:'separate actual Node process',subtests:5,accountIds:accounts.map(a=>a.userId),communityStarts:launchCount,providerCalls:0,productionConnections:0},null,2));
});
