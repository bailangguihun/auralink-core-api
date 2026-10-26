import test from 'node:test';
import assert from 'node:assert/strict';
import { createCommunityServer } from '../src/server.mjs';
import { listen } from './support.mjs';

test('drain tracks an unfinished handler after its HTTP client disconnects', async t => {
  let release, entered;
  const running = new Promise(resolve => { entered = resolve; });
  const finish = new Promise(resolve => { release = resolve; });
  const server = createCommunityServer({
    core: { authenticate: async () => { entered(); await finish; return { id: '101' }; } },
    store: {}, allowedOrigin: 'http://127.0.0.1:3177',
  });
  const origin = await listen(server);
  t.after(async () => { release(); if (server.listening) await server.drain(); });
  const abort = new AbortController();
  const request = fetch(origin + '/api/v1/community/capabilities', {
    headers: { authorization: 'Bearer isolated-fixture' }, signal: abort.signal,
  }).catch(() => null);
  await running;
  abort.abort(); await request;
  const draining = server.drain();
  assert.equal(server.drain(), draining, 'duplicate shutdown joins the same drain');
  let complete = false;
  draining.then(() => { complete = true; });
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(complete, false, 'closed sockets do not imply a finished transaction');
  release(); await draining;
  assert.equal(complete, true);
});
