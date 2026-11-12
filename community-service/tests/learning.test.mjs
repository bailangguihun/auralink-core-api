import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import sharp from 'sharp';
import { fixture, paintingId } from './support.mjs';

test('real image publishing, two identities, feedback, revisions and case publication', async t => {
  const f = await fixture({ learning: true }); t.after(() => f.close());
  const request = (path, method, payload, token = 'FIXTURE_A') => f.request(path, { method, payload: { requestId: randomUUID(), ...payload }, token });
  const image = await sharp({ create: { width: 100, height: 160, channels: 3, background: '#cfc3a7' } }).png().toBuffer();
  let imageId, postId, critiqueId, revisionId;
  async function raw(path, token = 'FIXTURE_A', options = {}) {
    return fetch(f.origin + '/api/v1/community' + path, { ...options, headers: { authorization: 'Bearer ' + token, ...options.headers } });
  }
  await t.test('draft image is owner-only, real bytes are validated, repeated upload has one receipt', async () => {
    const id = randomUUID();
    const options = { method: 'POST', body: image, headers: { 'content-type': 'image/png', 'x-request-id': id, 'x-file-name': encodeURIComponent('我的作品.png') } };
    const first = await raw('/uploads', 'FIXTURE_A', options), result = await first.json();
    assert.equal(first.status, 200, JSON.stringify(result)); imageId = result.result.assetId;
    assert.equal(result.result.image.width, 100);
    assert.equal((await (await raw('/uploads', 'FIXTURE_A', options)).json()).result.assetId, imageId);
    assert.equal((await raw('/media/' + imageId, 'FIXTURE_B')).status, 404);
    const metadata = (await f.request('/media/' + imageId + '/metadata')).data;
    assert.equal(metadata.sha256, createHash('sha256').update(image).digest('hex'));
    assert(!JSON.stringify(metadata).includes('original_key'));
    const invalid = await raw('/uploads', 'FIXTURE_A', { method: 'POST', body: 'not a PNG', headers: { 'content-type': 'image/png', 'x-request-id': randomUUID() } });
    assert.equal(invalid.status, 400);
    assert.equal((await f.database.query('SELECT count(*)::int AS n FROM community.media_assets')).rows[0].n, 1);
  });
  await t.test('artwork publication links owned assets and validates gallery references', async () => {
    const result = await request('/posts', 'POST', { content: '练习山水构图，希望改善留白。', title: '山水练习', kind: 'artwork',
      creationStage: '临摹', creationGoal: '改善主体与留白', helpTopics: ['构图', '笔墨'], tags: ['山水', '学习'], imageIds: [imageId], galleryArtworkId: paintingId });
    assert.equal(result.status, 200, JSON.stringify(result.data)); postId = result.data.result.postId;
    const post = await f.request('/posts/' + postId);
    assert.equal(post.data.workflowStatus, 'seeking_feedback');
    assert.equal(post.data.images[0].id, imageId); assert.equal(post.data.canEdit, true);
    const published = await raw('/media/' + imageId, 'FIXTURE_B');
    assert.equal(published.status, 200); assert.deepEqual(Buffer.from(await published.arrayBuffer()), image);
    const preview = await raw('/media/' + imageId + '?variant=preview', 'FIXTURE_B');
    assert.equal(preview.headers.get('content-type'), 'image/webp'); assert.equal(preview.headers.get('cache-control'), 'private, no-store');
    assert.equal((await request('/posts', 'POST', { content: '冒用图片', imageIds: [imageId], kind: 'discussion' }, 'FIXTURE_B')).status, 404);
    assert.equal((await f.request('/posts?q=山水&kind=artwork&tag=学习&workflow=seeking_feedback')).data.items.length, 1);
    assert.equal((await f.request('/posts?q=山水&kind=discussion')).data.items.length, 0);
    assert.equal((await f.request('/posts?sort=untrusted')).status, 400);
    assert.equal((await request('/posts/' + postId, 'PUT', { content: '仍是作品', title: '', tags: [], creationGoal: '' })).status, 400);
    assert.equal((await f.request('/posts/' + postId)).data.title, '山水练习');
  });
  await t.test('structured critique, same-post reply and notifications use actual author identities', async () => {
    assert.equal((await request('/posts/' + postId + '/comments', 'POST', { content: '观察', dimension: '构图', suggestion: '' }, 'FIXTURE_B')).status, 400);
    const result = await request('/posts/' + postId + '/comments', 'POST', { content: '右侧留白偏紧。', dimension: '构图', suggestion: '减弱近景墨色，打开水面。' }, 'FIXTURE_B');
    assert.equal(result.status, 200, JSON.stringify(result.data)); critiqueId = result.data.result.commentId;
    assert.equal((await request('/comments/' + critiqueId + '/adopt', 'PUT', { enabled: true }, 'FIXTURE_B')).status, 403);
    assert.equal((await request('/comments/' + critiqueId + '/adopt', 'PUT', { enabled: true })).status, 200);
    const comments = (await f.request('/posts/' + postId + '/comments')).data.items;
    assert.equal(comments[0].adopted, true); assert.equal(comments[0].suggestion, '减弱近景墨色，打开水面。');
    assert.equal((await f.request('/comments/' + critiqueId)).data.content, '右侧留白偏紧。');
    assert.equal((await request('/posts/' + postId + '/comments', 'POST', { content: '谢谢，我会试试。', parentId: critiqueId })).status, 200);
    assert.equal((await request('/posts/' + postId + '/comments', 'POST', { content: '越界回复', parentId: randomUUID() })).status, 400);
    const notifications = await f.request('/notifications'); assert.equal(notifications.data.unread, 1);
    const notification = notifications.data.items[0];
    assert.equal((await request('/notifications/' + notification.id + '/read', 'PUT', {}, 'FIXTURE_B')).status, 404);
    assert.equal((await request('/notifications/' + notification.id + '/read', 'PUT', {})).status, 200);
    assert.equal((await f.request('/notifications')).data.unread, 0);
  });
  await t.test('tasks deduplicate across different request IDs and enforce post ownership', async () => {
    for (let i = 0; i < 2; i++) assert.equal((await request('/posts/' + postId + '/tasks', 'POST', {})).status, 200);
    let growth = (await f.request('/posts/' + postId + '/growth')).data;
    assert.equal(growth.tasks.length, 1);
    assert.equal((await request('/tasks/' + growth.tasks[0].id, 'PUT', { status: 'done' }, 'FIXTURE_B')).status, 403);
    assert.equal((await request('/tasks/' + growth.tasks[0].id, 'PUT', { status: 'done' })).status, 200);
    growth = (await f.request('/posts/' + postId + '/growth')).data; assert.equal(growth.tasks[0].status, 'done');
  });
  await t.test('version stays attached to its own post; original reviewers receive one invitation', async () => {
    const result = await request('/posts/' + postId + '/revisions', 'POST', { imageIds: [imageId], note: '减弱近景墨色。', reflection: '主体更集中。' });
    assert.equal(result.status, 200, JSON.stringify(result.data)); revisionId = result.data.result.revisionId;
    assert.equal((await request('/posts/' + postId + '/comments', 'POST', { content: '普通版本交流', revisionId }, 'FIXTURE_B')).status, 200);
    assert.equal((await f.request('/posts/' + postId + '/growth')).data.revisions[0].reviewStatus, 'not_requested');
    for (let i = 0; i < 2; i++) assert.equal((await request('/posts/' + postId + '/revisions/' + revisionId + '/review', 'POST', {})).status, 200);
    const notices = (await f.request('/notifications', { token: 'FIXTURE_B' })).data.items;
    assert.equal(notices.filter(row => row.kind === 'review_request').length, 1);
    assert.equal((await request('/posts/' + postId + '/comments', 'POST', { content: '留白打开了。', dimension: '构图', suggestion: '继续观察墨色节奏。', revisionId }, 'FIXTURE_B')).status, 200);
    const growth = (await f.request('/posts/' + postId + '/growth')).data;
    assert.equal(growth.revisions[0].versionNumber, 2); assert.equal(growth.revisions[0].reviewStatus, 'completed');
  });
  await t.test('case drafts are private, publication is explicit, source deletion revokes access', async () => {
    assert.equal((await request('/posts/' + postId + '/workflow', 'PUT', { status: 'completed' })).status, 200);
    const made = await request('/posts/' + postId + '/case', 'POST', {}); assert.equal(made.status, 200, JSON.stringify(made.data));
    const caseId = made.data.result.caseId;
    assert.equal((await f.request('/posts/' + postId + '/growth', { token: 'FIXTURE_B' })).data.caseCard, null);
    assert.equal((await f.request('/cases')).data.items.length, 0);
    assert.equal((await request('/cases/' + caseId, 'PUT', { title: '留白的调整', summary: '根据真实点评减弱近景墨色。', status: 'published' })).status, 200);
    assert.equal((await f.request('/cases?dimension=构图&q=留白')).data.items.length, 1);
    assert.equal((await request('/posts/' + postId, 'DELETE', {})).status, 200);
    assert.equal((await f.request('/comments/' + critiqueId)).status, 404);
    assert.equal((await f.request('/cases')).data.items.length, 0);
    assert.equal((await raw('/media/' + imageId, 'FIXTURE_B')).status, 404);
    assert.equal((await raw('/media/' + imageId, 'FIXTURE_A')).status, 200);
    assert.equal(f.state.writes, 0, 'No model calls, Core writes or fabricated accounts');
  });
});
