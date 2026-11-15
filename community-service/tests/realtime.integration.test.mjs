import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import {PGlite} from '@electric-sql/pglite';
import WebSocket from 'ws';
import {CommunityStore} from '../src/learning-store.mjs';
import {createCommunityServer} from '../src/server.mjs';
import {createReferenceGateway} from '../src/v2/gateway.mjs';
import {rtcConfiguration} from '../src/v2/rooms.mjs';
import {listen} from './support.mjs';

test('real sockets distinguish transient identity outage from revoked credentials', {timeout:30000},async t=>{
  const pg=new PGlite();
  for(const name of ['001_foundation','002_learning_community','003_reference_community'])await pg.exec(await readFile(new URL('../db/'+name+'.sql',import.meta.url),'utf8'));
  const database={query:(...args)=>pg.query(...args),transaction:work=>pg.transaction(work)};
  const store=new CommunityStore(database),dir=await mkdtemp(join(tmpdir(),'community-realtime-'));
  let failure=0;
  const core={async communityIdentity(){if(failure)throw Object.assign(new Error('injected identity failure'),{status:failure});return {id:101,username:'fixture',displayName:'Fixture',email:'fixture@example.invalid',admin:false};},async authenticate(){return {id:'101',displayName:'Fixture'};}};
  const secret='fixture-only-'.repeat(4),origin='http://localhost:3000';
  const reference=await createReferenceGateway({database,core,mediaRoot:dir,allowedOrigin:origin,credentialIntervalMs:50,rtcConfig:rtcConfiguration({urls:'["turn:relay.example:3478"]',secret})});
  const server=createCommunityServer({store,core,reference,allowedOrigin:origin}),base=await listen(server);
  t.after(async()=>{server.closeAllConnections();await server.drain();await pg.close();await rm(dir,{recursive:true,force:true});});
  async function request(path,body){
    const response=await fetch(base+'/api/v2/community'+path,{method:body?'POST':'GET',headers:{authorization:'Bearer fixture','x-request-id':randomUUID(),'content-type':'application/json'},body:body?JSON.stringify(body):undefined});
    assert([200,201].includes(response.status));return (await response.json()).data;
  }
  await request('/api/user/profile');
  const room=await request('/api/v2/rooms',{name:'Fixture room',visibility:'public',memberLimit:10});
  const config=await request('/api/rtc-config');assert.equal(config.relayAvailable,true);assert(!JSON.stringify(config).includes(secret));
  assert.equal((await fetch(base+'/api/v2/community/api/rtc-config')).status,401);
  async function connect(){
    const {ticket}=await request('/api/ws-ticket',{});
    const socket=new WebSocket(base.replace(/^http/,'ws')+'/ws?ticket='+ticket,{origin});
    await new Promise((resolve,reject)=>{socket.once('open',resolve);socket.once('error',reject);});
    const subscribed=new Promise(resolve=>socket.on('message',raw=>{if(JSON.parse(raw).eventType==='room.subscribed')resolve();}));
    socket.send(JSON.stringify({type:'room.subscribe',roomId:room.id}));await subscribed;return socket;
  }
  const handshakeTicket=await request('/api/ws-ticket',{});failure=503;
  const handshake=new WebSocket(base.replace(/^http/,'ws')+'/ws?ticket='+handshakeTicket.ticket,{origin});
  assert.equal(await new Promise((resolve,reject)=>{handshake.once('close',resolve);handshake.once('error',reject);}),1013);
  failure=0;
  let socket=await connect();let closed=new Promise(resolve=>socket.once('close',resolve));failure=503;
  assert.equal(await closed,1013);
  failure=0;socket=await connect();closed=new Promise(resolve=>socket.once('close',resolve));failure=401;
  assert.equal(await closed,4401);
});
