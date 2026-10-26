import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { CoreClient } from '../src/core.mjs';
import { CommunityStore } from '../src/store.mjs';
import { createCommunityServer } from '../src/server.mjs';

export const paintingId = '23c2e7c7-3898-5ecd-9ca0-dc47fc7b2d39';
export async function listen(server, port = 0) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  return 'http://127.0.0.1:' + server.address().port;
}
export async function close(server) {
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}
export async function fixture({ native = false, directory, corePort = 0 } = {}) {
  let database, pg;
  if (native) {
    if (!process.env.COMMUNITY_TEST_DATABASE_URL) throw new Error('A dedicated disposable PostgreSQL database URL is required');
    const target = new URL(process.env.COMMUNITY_TEST_DATABASE_URL);
    if (!['postgres:', 'postgresql:'].includes(target.protocol) || target.hostname !== '127.0.0.1' ||
        target.pathname !== '/community_fixture') throw new Error('Native tests require loopback /community_fixture');
    const { postgresDatabase } = await import('../src/database.mjs');
    database = postgresDatabase(process.env.COMMUNITY_TEST_DATABASE_URL);
  } else {
    const { PGlite } = await import('@electric-sql/pglite');
    pg = new PGlite(directory);
    database = { query: (...args) => pg.query(...args), transaction: work => pg.transaction(work), close: () => pg.close() };
  }
  const migration = await readFile(new URL('../db/001_foundation.sql', import.meta.url), 'utf8');
  // A pre-existing schema rejects the fixture. Never DROP or overwrite a database.
  if (pg) await pg.exec(migration);
  else await database.query(migration);
  const state = { available: true, sourceReads: 0, profileReads: 0, coreFail: false, writes: 0 };
  const coreServer = http.createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.setHeader('access-control-allow-origin', '*');
    res.setHeader('access-control-allow-headers', 'authorization,content-type');
    res.setHeader('access-control-allow-methods', 'GET,OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    if (req.method !== 'GET') { state.writes++; res.writeHead(405); return res.end('{}'); }
    const token = req.headers.authorization;
    const id = token === 'Bearer FIXTURE_A' ? 101 : token === 'Bearer FIXTURE_B' ? 202 : null;
    if (!id) { res.writeHead(401); return res.end(JSON.stringify({ message: 'login required' })); }
    if (state.coreFail) { res.writeHead(503); return res.end('{}'); }
    if (req.url === '/api/user/profile') {
      state.profileReads++;
      return res.end(JSON.stringify({ success: true, data: { id, username: 'fixture' + id,
        fullName: id === 101 ? '山间来客' : '观画人', email: 'fixture@example.invalid', createdAt: null, updatedAt: null } }));
    }
    if (req.url === '/api/v1/paintings/' + paintingId) {
      state.sourceReads++;
      if (!state.available) { res.writeHead(404); return res.end('{}'); }
      return res.end(JSON.stringify({ paintingId, title: '山水清音', authorName: '隔离测试作者',
        creationDynastyNormalized: '当代', visibleInGallery: true, imageAvailable: true, status: 'ACTIVE',
        // Must never be fetched or leaked by Community.
        image: { contentUrl: 'http://untrusted.invalid/private.png' } }));
    }
    if (req.url.startsWith('/api/v1/paintings?')) return res.end(JSON.stringify({
      items: state.available ? [{ paintingId, title: '山水清音', authorName: '隔离测试作者', imageAvailable: true,
        image: null, creationDynastyNormalized: '当代', favorited: false }] : [],
      page: 0, size: 6, totalElements: state.available ? 1 : 0, totalPages: 1, first: true, last: true, hasNext: false,
    }));
    res.writeHead(404); res.end('{}');
  });
  const coreUrl = await listen(coreServer, corePort);
  const store = new CommunityStore(database);
  const logs = [];
  const server = createCommunityServer({ store, core: new CoreClient(coreUrl), allowedOrigin: 'http://127.0.0.1:3177',
    logger: { error: value => logs.push(value) } });
  const origin = await listen(server);
  async function request(path, { token = 'FIXTURE_A', method = 'GET', payload, headers = {} } = {}) {
    const response = await fetch(origin + '/api/v1/community' + path, {
      method, headers: { authorization: 'Bearer ' + token, ...headers,
        ...(payload ? { 'content-type': 'application/json' } : {}) },
      body: payload ? JSON.stringify(payload) : undefined,
    });
    return { status: response.status, headers: response.headers, data: await response.json() };
  }
  return { database, store, state, logs, origin, coreUrl, request,
    async close() { await close(server); await close(coreServer); await database.close(); } };
}
