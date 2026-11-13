import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import http from 'node:http';
import { PGlite } from '@electric-sql/pglite';
import sharp from 'sharp';
import WebSocket from 'ws';
import { CommunityStore } from '../src/learning-store.mjs';
import { createReferenceGateway } from '../src/v2/gateway.mjs';
import { createCommunityServer } from '../src/server.mjs';
import { listen } from './support.mjs';
import { createRoomFeature } from '../src/v2/rooms.mjs';
import { referenceDatabase } from '../src/v2/database.mjs';

test('reference room assets retain uploader ownership and historical visitor permissions', { timeout: 60000 }, async t => {
  const pg = new PGlite(), directory = await mkdtemp(join(tmpdir(), 'artlive-reference-acl-'));
  for (const name of ['001_foundation', '002_learning_community', '003_reference_community']) {
    await pg.exec(await readFile(new URL('../db/' + name + '.sql', import.meta.url), 'utf8'));
  }
  const database = { query: (...args) => pg.query(...args), transaction: work => pg.transaction(work) };
  const core = {
    async communityIdentity(token) {
      const id = Number(token);
      if (![101, 202, 303].includes(id)) throw Object.assign(new Error('Invalid fixture identity'), { status: 401 });
      return { id, username: 'acl_' + id, displayName: '隔离账号 ' + id, email: id + '@example.invalid', admin: false };
    },
    async authenticate(token) { return { id: token, displayName: '隔离账号 ' + token }; },
  };
  const store = new CommunityStore(database); assert(await store.ready());
  const gateway = await createReferenceGateway({ database, core, mediaRoot: directory, allowedOrigin: 'http://localhost:3000',
    providerFetch: () => { throw new Error('This isolated test must not call a model'); } });
  const server = createCommunityServer({ store, core, reference: gateway, allowedOrigin: 'http://localhost:3000' });
  const base = await listen(server);
  t.after(async () => { server.closeAllConnections(); await server.drain(); await pg.close(); await rm(directory, { recursive: true, force: true }); });
  async function request(actor, path, method = 'GET', body) {
    const binary = Buffer.isBuffer(body);
    const response = await fetch(base + '/api/v2/community' + path, { method,
      headers: { authorization: 'Bearer ' + actor, 'x-request-id': randomUUID(),
        ...(body === undefined ? {} : { 'content-type': binary ? 'image/png' : 'application/json' }) },
      body: body === undefined ? undefined : binary ? body : JSON.stringify(body) });
    const json = response.headers.get('content-type')?.includes('application/json');
    return { status: response.status, data: json ? await response.json() : Buffer.from(await response.arrayBuffer()) };
  }
  async function ok(...args) {
    const result = await request(...args); assert(result.status < 400, JSON.stringify(result)); return result.data.data;
  }
  const sockets = [];
  async function event(socket, type) {
    const deadline = Date.now() + 4000;
    while (Date.now() < deadline) {
      const index = socket.events.findIndex(value => value.eventType === type);
      if (index >= 0) return socket.events.splice(index, 1)[0];
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    throw new Error('Missing websocket event ' + type);
  }
  async function connect(origin, ticket, roomId) {
    const socket = new WebSocket(origin.replace(/^http/, 'ws') + '/ws?ticket=' + ticket, { origin: 'http://localhost:3000' });
    socket.events = [];
    socket.on('message', raw => socket.events.push(JSON.parse(raw)));
    await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
    if (roomId) { socket.send(JSON.stringify({ type: 'room.subscribe', roomId })); await event(socket, 'room.subscribed'); }
    return socket;
  }
  async function accountSocket(actor, roomId) {
    const ticket = (await ok(actor, '/api/ws-ticket', 'POST', {})).ticket;
    const socket = await connect(base, ticket, roomId); sockets.push(socket); return socket;
  }
  for (const actor of [101, 202, 303]) await ok(actor, '/api/user/profile');
  const png = await sharp({ create: { width: 24, height: 32, channels: 3, background: '#d0c6b6' } }).png().toBuffer();
  const upload = () => ok(101, '/api/v2/artwork-assets', 'POST', png);
  const room = await ok(101, '/api/v2/rooms', 'POST', { name: '隔离权限房间', visibility: 'public' });
  const asset = await upload();
  const first = await ok(101, `/api/v2/rooms/${room.id}/artwork-sessions`, 'POST', { assetId: asset.id, title: '最初的作品', visitorVisible: false });
  await ok(202, `/api/v2/rooms/${room.id}/join`, 'POST', {});

  await t.test('avatar and cover inputs reject arrays before reference ownership checks or string coercion', async () => {
    const draft = await upload();
    const profile = avatar => ({ nickname: '类型边界隔离账号', email: '303@example.invalid', avatar });
    const cover = coverUrl => ({ name: '类型边界隔离封面', visibility: 'public', coverUrl });
    assert.equal((await request(303, draft.originalUrl)).status, 404);
    assert.equal((await request(303, '/api/user/profile', 'PUT', profile(draft.originalUrl))).status, 403);
    assert.equal((await request(303, '/api/v2/rooms', 'POST', cover(draft.originalUrl))).status, 403);
    for (const invalid of [[draft.originalUrl], [], ['/api/community/v2' + draft.originalUrl], {}, 1, true]) {
      assert.equal((await request(303, '/api/user/profile', 'PUT', profile(invalid))).status, 400);
      assert.equal((await request(303, '/api/v2/rooms', 'POST', cover(invalid))).status, 400);
      assert.equal((await request(303, draft.originalUrl)).status, 404);
    }
    assert.equal((await pg.query('SELECT avatar FROM community.v2_users WHERE id=303')).rows[0].avatar, null);
    assert.equal((await pg.query("SELECT count(*)::int AS n FROM community.v2_rooms WHERE owner_id=303 AND name='类型边界隔离封面'")).rows[0].n, 0);
    await ok(101, '/api/user/profile', 'PUT', { nickname: '本人主动发布头像', email: '101@example.invalid', avatar: draft.originalUrl });
    assert.equal((await request(303, draft.originalUrl)).status, 200);
    await ok(101, '/api/user/profile', 'PUT', { nickname: '本人清除头像', email: '101@example.invalid', avatar: null });
    assert.equal((await request(303, draft.originalUrl)).status, 404);
    assert.equal((await request(303, '/api/v2/rooms', 'POST', { name: '空封面有效值', visibility: 'private', coverUrl: null })).status, 201);
  });

  await t.test('asset metadata requires uploader ownership or a currently visible joined session', async () => {
    const draft = await upload();
    const metadata = () => `/api/v2/artwork-assets/${draft.id}`;
    assert.equal((await ok(101, metadata())).filename, draft.filename);
    for (const actor of [202, 303]) {
      const denied = await request(actor, metadata());
      assert.equal(denied.status, 404);
      assert.equal(denied.data.data, undefined);
      for (const field of ['filename', 'originalUrl', 'previewUrl', 'width', 'height', 'sha256']) assert(!Object.hasOwn(denied.data, field));
    }
    const privateRoom = await ok(101, '/api/v2/rooms', 'POST', { name: '私有资源隔离房间', visibility: 'private' });
    const session = await ok(101, `/api/v2/rooms/${privateRoom.id}/artwork-sessions`, 'POST', { assetId: draft.id, visitorVisible: false });
    assert.equal((await request(202, metadata())).status, 404);
    const invitation = await ok(101, `/api/v2/rooms/${privateRoom.id}/invitation`);
    await ok(202, `/api/v2/room-invitations/${invitation.code}/join`, 'POST', {});
    assert.equal((await request(202, metadata())).status, 200);
    assert.equal((await request(303, metadata())).status, 404);
    const next = await upload();
    await ok(101, `/api/v2/rooms/${privateRoom.id}/artwork-sessions`, 'POST', { assetId: next.id, visitorVisible: false });
    assert.equal((await request(202, metadata())).status, 404);
    assert.equal((await request(101, metadata())).status, 200);
    await ok(101, `/api/v2/rooms/${privateRoom.id}/members/202/role`, 'PATCH', { role: 'member' });
    assert.equal((await request(202, metadata())).status, 200);
    await ok(101, `/api/v2/rooms/${privateRoom.id}/members/202/role`, 'PATCH', { role: 'visitor' });
    assert.equal((await request(202, metadata())).status, 404);
    await ok(101, `/api/v2/rooms/${privateRoom.id}/artwork-sessions/${session.id}/visibility`, 'PATCH', { visitorVisible: true });
    assert.equal((await request(202, metadata())).status, 200);
    await ok(101, `/api/v2/rooms/${privateRoom.id}/members/202`, 'DELETE', {});
    assert.equal((await request(202, metadata())).status, 404);
  });

  await t.test('another room owner cannot acquire an unreadable asset by attaching its ID', async () => {
    assert.equal((await request(303, asset.originalUrl)).status, 404);
    const other = await ok(303, '/api/v2/rooms', 'POST', { name: '另一个隔离房间', visibility: 'private' });
    const refused = await request(303, `/api/v2/rooms/${other.id}/artwork-sessions`, 'POST', { assetId: asset.id });
    assert.equal(refused.status, 403);
    assert.equal(refused.data.error.code, 'ARTWORK_ACCESS_DENIED');
    assert.equal((await request(303, asset.originalUrl)).status, 404);
    assert.equal((await pg.query('SELECT count(*)::int AS n FROM community.v2_artwork_sessions WHERE room_id=$1', [other.id])).rows[0].n, 0);
  });

  await t.test('visitor can read the active artwork, but every variant of private history is denied', async () => {
    const file = variant => `/api/v2/rooms/${room.id}/artwork-assets/${asset.id}/file?variant=${variant}`;
    assert.equal((await request(202, `/api/v2/rooms/${room.id}/artwork-sessions/${first.id}`)).status, 200);
    assert.equal((await request(202, file('original'))).status, 200);
    const next = await upload();
    await ok(101, `/api/v2/rooms/${room.id}/artwork-sessions`, 'POST', { assetId: next.id, title: '新的当前作品', visitorVisible: false });
    assert.equal((await request(202, `/api/v2/rooms/${room.id}/artwork-sessions/${first.id}`)).status, 403);
    for (const variant of ['original', 'display', 'preview', 'thumbnail']) assert.equal((await request(202, file(variant))).status, 404, variant);
    await ok(101, `/api/v2/rooms/${room.id}/artwork-sessions/${first.id}/visibility`, 'PATCH', { visitorVisible: true });
    assert.equal((await request(202, `/api/v2/rooms/${room.id}/artwork-sessions/${first.id}`)).status, 200);
    assert.equal((await request(202, file('original'))).status, 200);
  });

  await t.test('a successor owner can reuse an asset already belonging to this room', async () => {
    await ok(101, `/api/v2/rooms/${room.id}/members/202/role`, 'PATCH', { role: 'member' });
    const transfer = await ok(101, `/api/v2/rooms/${room.id}/ownership-transfers`, 'POST', { userId: 202 });
    await ok(202, `/api/v2/rooms/${room.id}/ownership-transfers/${transfer.id}/accept`, 'POST', {});
    const reused = await ok(202, `/api/v2/rooms/${room.id}/artwork-sessions`, 'POST', { assetId: asset.id, title: '移交后继续讨论原作品' });
    assert.equal(reused.asset.id, asset.id);
    const freshOtherAsset = await upload();
    assert.equal((await request(202, `/api/v2/rooms/${room.id}/artwork-sessions`, 'POST', { assetId: freshOtherAsset.id })).status, 403);
  });

  await t.test('annotation and drawing events enforce hidden and historical content permissions per recipient', async () => {
    await ok(303, `/api/v2/rooms/${room.id}/join`, 'POST', {});
    const author = await accountSocket(101, room.id), owner = await accountSocket(202, room.id), visitor = await accountSocket(303, room.id);
    const sessionId = (await pg.query('SELECT current_session_id FROM community.v2_rooms WHERE id=$1', [room.id])).rows[0].current_session_id;
    const annotation = await ok(101, `/api/v2/rooms/${room.id}/artwork-sessions/${sessionId}/annotations`, 'POST', {
      shapeType: 'rectangle', geometry: { x: .1, y: .1, width: .2, height: .2 }, content: '公开可见的原始点评' });
    for (const socket of [author, owner, visitor]) assert.equal((await event(socket, 'annotation.created')).data.content, annotation.content);
    const strokes = [{ brush: 'ink', color: '#112233', width: 4, opacity: .8, points: [{ x: .1, y: .1 }, { x: .2, y: .2 }] }];
    const drawing = await ok(101, `/api/v2/rooms/${room.id}/artwork-sessions/${sessionId}/drawings`, 'POST', { strokes });
    for (const socket of [author, owner, visitor]) assert.deepEqual((await event(socket, 'drawing.created')).data.strokes, strokes);
    const hidden = await ok(101, `/api/v2/annotations/${annotation.id}/hide`, 'POST', {});
    for (const socket of [author, owner]) assert.equal((await event(socket, 'annotation.hidden')).data.content, annotation.content);
    assert.deepEqual((await event(visitor, 'annotation.deleted')).data, { id: annotation.id });
    const edited = await ok(101, `/api/v2/annotations/${annotation.id}`, 'PATCH', { version: hidden.version, content: '隐藏后编辑的私有点评' });
    for (const socket of [author, owner]) assert.equal((await event(socket, 'annotation.updated')).data.content, edited.content);
    assert.deepEqual((await event(visitor, 'annotation.deleted')).data, { id: annotation.id });
    await ok(101, `/api/v2/annotations/${annotation.id}/restore`, 'POST', {});
    for (const socket of [author, owner, visitor]) assert.equal((await event(socket, 'annotation.restored')).data.content, edited.content);
    await ok(101, `/api/v2/drawings/${drawing.id}/hide`, 'POST', {});
    for (const socket of [author, owner]) assert.deepEqual((await event(socket, 'drawing.hidden')).data.strokes, strokes);
    assert.deepEqual((await event(visitor, 'drawing.deleted')).data, { id: drawing.id });
    await ok(101, `/api/v2/drawings/${drawing.id}/restore`, 'POST', {});
    for (const socket of [author, owner, visitor]) assert.deepEqual((await event(socket, 'drawing.restored')).data.strokes, strokes);
    await ok(202, `/api/v2/rooms/${room.id}/artwork-sessions`, 'POST', { assetId: asset.id, title: '切换后的当前作品' });
    assert.equal((await request(303, `/api/v2/rooms/${room.id}/artwork-sessions/${sessionId}`)).status, 403);
    const privateEdit = await ok(101, `/api/v2/annotations/${annotation.id}`, 'PATCH', { version: edited.version + 1, content: '访客无权查看的历史点评' });
    for (const socket of [author, owner]) assert.equal((await event(socket, 'annotation.updated')).data.content, privateEdit.content);
    assert.deepEqual((await event(visitor, 'annotation.deleted')).data, { id: annotation.id });
    for (const action of ['hide', 'restore']) {
      await ok(101, `/api/v2/annotations/${annotation.id}/${action}`, 'POST', {});
      for (const socket of [author, owner]) await event(socket, action === 'hide' ? 'annotation.hidden' : 'annotation.restored');
      assert.deepEqual((await event(visitor, 'annotation.deleted')).data, { id: annotation.id });
      await ok(101, `/api/v2/drawings/${drawing.id}/${action}`, 'POST', {});
      for (const socket of [author, owner]) await event(socket, action === 'hide' ? 'drawing.hidden' : 'drawing.restored');
      assert.deepEqual((await event(visitor, 'drawing.deleted')).data, { id: drawing.id });
    }
    await ok(101, `/api/v2/annotations/${annotation.id}`, 'DELETE', {});
    await ok(101, `/api/v2/drawings/${drawing.id}`, 'DELETE', {});
    for (const socket of [author, owner, visitor]) {
      assert.deepEqual((await event(socket, 'annotation.deleted')).data, { id: annotation.id });
      assert.deepEqual((await event(socket, 'drawing.deleted')).data, { id: drawing.id });
    }
    for (const socket of sockets) socket.terminate();
  });

  await t.test('direct account revocation saves shared strokes, stops voice and closes every socket', async child => {
    const db = referenceDatabase(database), pending = new Set();
    const socketWork = work => {
      const promise = db.unit(work).finally(() => pending.delete(promise)); pending.add(promise); return promise;
    };
    const unexpected = () => { throw new Error('Unexpected provider, upload or wallet operation'); };
    const feature = await db.unit(() => createRoomFeature({ db, uploadDir: directory, readBody: unexpected, receiveUpload: unexpected,
      currentUser: req => db.prepare('SELECT * FROM users WHERE id=?').get(Number(req.headers.authorization.replace(/^Bearer /, ''))),
      currentMediaUser: unexpected, ensureCoinWallet: unexpected, roomSpendableCoins: unexpected, applyCoinChange: unexpected,
      generateArtworkCritique: unexpected, consumeTicket: async ticket => ticket, socketWork }));
    const isolatedServer = http.createServer((_req, res) => res.end('isolated websocket fixture'));
    const wss = feature.attachWebSocket(isolatedServer), isolatedOrigin = await listen(isolatedServer), connected = [];
    child.after(async () => {
      for (const socket of connected) socket.terminate();
      for (const socket of wss.clients) socket.terminate();
      await new Promise(resolve => wss.close(resolve));
      await new Promise(resolve => isolatedServer.close(resolve));
      while (pending.size) await Promise.all([...pending]);
      await db.unit(() => feature.shutdown());
    });
    const target = await connect(isolatedOrigin, '202', room.id), observer = await connect(isolatedOrigin, '101', room.id);
    const idle = await connect(isolatedOrigin, '202'); connected.push(target, observer, idle);
    const deadline = Date.now() + 4000;
    while ([...wss.clients].filter(socket => socket.authToken === '202').length < 2 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal([...wss.clients].filter(socket => socket.authToken === '202').length, 2);
    target.send(JSON.stringify({ type: 'voice.join' })); await event(target, 'voice.roster');
    target.send(JSON.stringify({ type: 'voice.start' })); await event(target, 'voice.updated');
    const sessionId = (await pg.query('SELECT current_session_id FROM community.v2_rooms WHERE id=$1', [room.id])).rows[0].current_session_id;
    const strokes = [{ brush: 'ink', color: '#334455', width: 3, opacity: 1, points: [{ x: .2, y: .2 }, { x: .3, y: .3 }] }];
    await db.unit(() => db.prepare("INSERT INTO room_view_shares(room_id,presenter_id,session_id,status,viewport,strokes,started_at,updated_at) VALUES (?,?,?,'active','{}',?,?,?)").run(room.id, 202, sessionId, JSON.stringify(strokes), new Date().toISOString(), new Date().toISOString()));
    const closed = Promise.all([target, idle].map(socket => new Promise(resolve => socket.once('close', resolve))));
    const result = await db.unit(() => feature.revokeUser(202));
    await closed;
    assert.deepEqual(result, { stoppedShares: 1, stoppedVoice: 1, closedConnections: 2 });
    assert.equal(observer.readyState, WebSocket.OPEN);
    assert.equal((await pg.query('SELECT status FROM community.v2_room_view_shares WHERE room_id=$1', [room.id])).rows[0].status, 'ended');
    const saved = await pg.query('SELECT strokes FROM community.v2_drawing_groups WHERE author_id=202 AND session_id=$1', [sessionId]);
    assert.equal(saved.rows.length, 1); assert.deepEqual(JSON.parse(saved.rows[0].strokes), strokes);
    assert.deepEqual((await event(observer, 'drawing.created')).data.strokes, strokes);
  });
});
