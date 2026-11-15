import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {createHmac} from 'node:crypto';
import {heartbeat,credentialCloseCode,rtcConfiguration} from '../src/v2/rooms.mjs';

test('temporary identity failure remains reconnectable; invalid identity is terminal',()=>{
  for(const status of [401,403])assert.equal(credentialCloseCode({status}),4401);
  for(const error of [{status:503},{status:502},new Error('timeout'),undefined])assert.equal(credentialCloseCode(error),1013);
});

test('protocol heartbeat tolerates a missed interval, observes pong and releases its timer',()=>{
  const server=new EventEmitter();server.clients=new Set();let time=0,tick,cancelled=0;
  const stop=heartbeat(server,{now:()=>time,repeat:fn=>{tick=fn;return 17;},cancel:id=>{assert.equal(id,17);cancelled++;}});
  const socket=new EventEmitter();Object.assign(socket,{readyState:1,pings:0,ends:0,ping(){this.pings++;},terminate(){this.ends++;}});
  server.clients.add(socket);server.emit('connection',socket);
  time=25000;tick();assert.equal(socket.pings,1);assert.equal(socket.ends,0);
  time=50000;socket.emit('pong');tick();assert.equal(socket.ends,0);
  time=85000;tick();assert.equal(socket.ends,0);
  time=110000;tick();assert.equal(socket.ends,1);
  server.emit('close');assert.equal(cancelled,1);stop();
});

test('TURN shared secret never reaches clients; credentials are authenticated-user-specific and expire',()=>{
  const secret='fixture-secret-'.repeat(4);
  const config=rtcConfiguration({urls:JSON.stringify(['turn:relay.example:3478?transport=udp','turns:relay.example:5349?transport=tcp']),secret,now:()=>1000000});
  const a=config('42'),b=config('43');
  assert.equal(a.expiresAt,1600);assert.equal(a.relayAvailable,true);
  assert.equal(a.iceServers[1].username,'1600:42');assert.notEqual(a.iceServers[1].credential,b.iceServers[1].credential);
  assert.equal(a.iceServers[1].credential,createHmac('sha1',secret).update('1600:42').digest('base64'));
  assert(!JSON.stringify(a).includes(secret));
  assert.equal(rtcConfiguration()('42').relayAvailable,false);
  for(const options of [{urls:'["https://bad.example"]',secret},{urls:'["turn:relay.example"]'},{secret},{urls:'["turn:user:pass@relay.example"]',secret}])assert.throws(()=>rtcConfiguration(options));
});
