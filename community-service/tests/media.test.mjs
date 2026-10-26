import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { serveAuthorizedFile } from '../src/media.mjs';
import { listen, close } from './support.mjs';

test('authorized media has exactly one response, including Range and HEAD', async t => {
  const root = await mkdtemp(join(tmpdir(), 'community-media-'));
  await writeFile(join(root, 'fixture.wav'), '0123456789');
  const server = http.createServer(async (req, res) => {
    try { await serveAuthorizedFile(req, res, { root, filename: 'fixture.wav', mimeType: 'audio/wav',
      authorize: () => req.headers.authorization === 'Bearer member' }); }
    catch (error) { if (!res.headersSent) res.writeHead(error.status || 500); res.end(); }
  });
  const origin = await listen(server);
  t.after(async () => { await close(server); await rm(root, { recursive: true }); });
  assert.equal((await fetch(origin)).status, 404);
  const headers = { authorization: 'Bearer member' };
  const whole = await fetch(origin, { headers });
  assert.equal(whole.status, 200); assert.equal(await whole.text(), '0123456789');
  assert.equal(whole.headers.get('cache-control'), 'private, no-store');
  for (const [range, expected] of [['bytes=2-5', '2345'], ['bytes=7-', '789'], ['bytes=-3', '789']]) {
    const partial = await fetch(origin, { headers: { ...headers, range } });
    assert.equal(partial.status, 206); assert.equal(await partial.text(), expected);
  }
  for (const range of ['bytes=20-30', 'bytes=4-2', 'bytes=-0', 'bytes=0-1,4-5']) {
    const bad = await fetch(origin, { headers: { ...headers, range } });
    assert.equal(bad.status, 416); assert.equal(await bad.text(), '');
  }
  const head = await fetch(origin, { method: 'HEAD', headers });
  assert.equal(head.status, 200); assert.equal(head.headers.get('content-length'), '10'); assert.equal(await head.text(), '');
});
