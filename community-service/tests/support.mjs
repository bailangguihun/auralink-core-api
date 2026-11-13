import http from 'node:http';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CoreClient } from '../src/core.mjs';
import { CommunityStore } from '../src/learning-store.mjs';
import { CommunityMedia } from '../src/learning-media.mjs';
import { createCommunityServer } from '../src/server.mjs';

export const paintingId = '23c2e7c7-3898-5ecd-9ca0-dc47fc7b2d39';
export const paintingImage = {
  assetId: 'b6b65b8d-50bf-45fb-b445-14d4f31fa4a2', mimeType: 'image/svg+xml', fileSize: null,
  width: 640, height: 960,
  contentUrl: '/api/v1/assets/b6b65b8d-50bf-45fb-b445-14d4f31fa4a2/content',
  downloadUrl: '/api/v1/assets/b6b65b8d-50bf-45fb-b445-14d4f31fa4a2/download',
};
export async function listen(server, port = 0) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  return 'http://127.0.0.1:' + server.address().port;
}
export async function close(server) {
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}
export async function fixture({ native = false, directory, corePort = 0, learning = false } = {}) {
  let database, pg;
  if (native) {
    if (!process.env.COMMUNITY_TEST_DATABASE_URL) throw new Error('A dedicated disposable PostgreSQL database URL is required');
    const target = new URL(process.env.COMMUNITY_TEST_DATABASE_URL);
    const expectedDatabase = learning ? '/community_growth_fixture' : '/community_fixture';
    if (!['postgres:', 'postgresql:'].includes(target.protocol) || target.hostname !== '127.0.0.1' ||
        target.pathname !== expectedDatabase) throw new Error('Native tests require loopback ' + expectedDatabase);
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
  if (learning) {
    const upgrade = await readFile(new URL('../db/002_learning_community.sql', import.meta.url), 'utf8');
    if (pg) await pg.exec(upgrade); else await database.query(upgrade);
  }
  const state = { available: true, sourceReads: 0, profileReads: 0, coreFail: false, writes: 0, image: paintingImage };
  const coreServer = http.createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.setHeader('access-control-allow-origin', '*');
    res.setHeader('access-control-allow-headers', 'authorization,content-type');
    res.setHeader('access-control-allow-methods', 'GET,OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    if (req.method !== 'GET') { state.writes++; res.writeHead(405); return res.end('{}'); }
    if (req.url === paintingImage.contentUrl) {
      res.setHeader('content-type', 'image/svg+xml');
      return res.end('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="960"><rect width="640" height="960" fill="#ede5d7"/><path d="M40 880L180 270L310 650L470 100L600 880Z" fill="#8b9685"/><rect x="8" y="8" width="624" height="944" fill="none" stroke="#775943" stroke-width="16"/></svg>');
    }
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
        image: state.image, favorited: false }));
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
  await store.ready();
  const mediaRoot = learning ? await mkdtemp(join(tmpdir(), 'artlive-community-media-')) : null;
  const media = mediaRoot ? new CommunityMedia(mediaRoot, store) : null;
  const logs = [];
  const server = createCommunityServer({ store, media, core: new CoreClient(coreUrl), allowedOrigin: 'http://127.0.0.1:3177',
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
    async close() { await close(server); await close(coreServer); await database.close(); if (mediaRoot) await rm(mediaRoot, { recursive: true }); } };
}
