import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture, paintingId, paintingImage } from './support.mjs';

test('HTTP contract with isolated PostgreSQL engine and a contract-only Core fixture', async t => {
  const f = await fixture(); t.after(() => f.close());
  let postId;
  const key = randomUUID();
  const payload = { requestId: key, content: '观山听水', galleryArtworkId: paintingId };
  await t.test('anonymous and invalid identities are rejected without creating profiles', async () => {
    assert.equal((await f.request('/capabilities', { token: 'invalid' })).status, 401);
    assert.equal((await f.database.query('SELECT count(*)::int AS count FROM community.profiles')).rows[0].count, 0);
    const result = await f.request('/capabilities');
    assert.equal(result.data.actor.id, '101');
    assert.equal(result.data.rooms, false);
    assert.equal(result.data.coins, false);
  });
  await t.test('post creation and duplicate request return one persistent identity', async () => {
    const one = await f.request('/posts', { method: 'POST', payload });
    assert.equal(one.status, 200); postId = one.data.result.postId;
    const two = await f.request('/posts', { method: 'POST', payload });
    assert.deepEqual(two.data, one.data);
    assert.equal(f.state.sourceReads, 1);
    assert.equal((await f.database.query('SELECT count(*)::int AS count FROM community.posts')).rows[0].count, 1);
  });
  await t.test('request key cannot be rebound and supplied actor identity is rejected', async () => {
    assert.equal((await f.request('/posts', { method: 'POST', payload: { ...payload, content: '改写' } })).status, 409);
    assert.equal((await f.request('/posts', { method: 'POST', payload: { ...payload, authorId: 202 } })).status, 400);
  });
  await t.test('operation lookup is owner scoped; reads do not create another account', async () => {
    assert.equal((await f.request('/operations/' + key)).data.state, 'COMMITTED');
    assert.equal((await f.request('/operations/' + key, { token: 'FIXTURE_B' })).data.state, 'NOT_OBSERVED');
    assert.equal((await f.database.query('SELECT count(*)::int AS count FROM community.profiles')).rows[0].count, 1);
  });
  await t.test('references are current; restricted artwork no longer exposes metadata', async () => {
    assert.equal((await f.request('/posts/' + postId)).data.galleryReference.title, '山水清音');
    assert.deepEqual((await f.request('/posts/' + postId)).data.galleryReference.image, paintingImage);
    assert.deepEqual((await f.request('/gallery-references/' + paintingId)).data.image, paintingImage);
    assert.deepEqual((await f.request('/posts')).data.items[0].galleryReference.image, paintingImage);
    f.state.available = false;
    assert.deepEqual((await f.request('/posts/' + postId)).data.galleryReference, { state: 'UNAVAILABLE', paintingId });
    assert.equal((await f.request('/posts', { method: 'POST', payload: { ...payload, requestId: randomUUID() } })).status, 404);
    assert.equal((await f.request('/posts', { method: 'POST', payload })).data.result.postId, postId);
    f.state.available = true;
  });
  await t.test('image references never expose remote URLs, private paths or mismatched asset identities', async () => {
    for (const image of [null, { ...paintingImage, contentUrl: 'https://untrusted.invalid/private.png' },
      { ...paintingImage, contentUrl: '/root/private.png' }, { ...paintingImage, assetId: paintingId },
      { ...paintingImage, contentUrl: paintingImage.contentUrl + '?token=private' }]) {
      f.state.image = image;
      const result = await f.request('/posts/' + postId);
      assert.equal(result.status, 200); assert.equal(result.data.galleryReference.image, null);
      assert(!JSON.stringify(result.data).includes('private'));
    }
    f.state.image = { ...paintingImage, width: -1, height: '960', internalPath: '/root/private.png', downloadUrl: 'https://untrusted.invalid' };
    assert.deepEqual((await f.request('/gallery-references/' + paintingId)).data.image,
      { ...paintingImage, width: null, height: null });
    f.state.image = paintingImage;
  });
  await t.test('comments and reactions persist; explicit set operations do not toggle twice', async () => {
    const comment = { requestId: randomUUID(), content: '留白很有意境。' };
    const first = await f.request('/posts/' + postId + '/comments', { method: 'POST', token: 'FIXTURE_B', payload: comment });
    assert.equal(first.status, 200);
    assert.deepEqual((await f.request('/posts/' + postId + '/comments', { method: 'POST', token: 'FIXTURE_B', payload: comment })).data, first.data);
    assert.equal((await f.request('/posts/' + postId + '/comments')).data.items.length, 1);
    for (const kind of ['like', 'favorite']) {
      const operation = { requestId: randomUUID(), enabled: true };
      assert.equal((await f.request('/posts/' + postId + '/' + kind, { method: 'PUT', payload: operation })).status, 200);
      assert.equal((await f.request('/posts/' + postId + '/' + kind, { method: 'PUT', payload: operation })).status, 200);
    }
    const post = (await f.request('/posts/' + postId)).data;
    assert.equal(post.likeCount, 1); assert.equal(post.commentCount, 1); assert.equal(post.favorited, true);
    assert.equal((await f.request('/posts?view=favorites')).data.items.length, 1);
    assert.equal((await f.request('/posts?view=favorites', { token: 'FIXTURE_B' })).data.items.length, 0);
  });
  await t.test('lost acknowledgement is reconciled by read, without repeating a submission', async () => {
    const original = f.store.createPost.bind(f.store);
    f.store.createPost = async (...args) => { await original(...args); throw new Error('simulated acknowledgement loss'); };
    const requestId = randomUUID();
    const response = await f.request('/posts', { method: 'POST', payload: { requestId, content: '只提交一次' } });
    assert.equal(response.status, 503);
    assert.equal(response.data.code, 'COMMUNITY_RESULT_UNCERTAIN');
    assert.equal((await f.request('/operations/' + requestId)).data.state, 'COMMITTED');
    f.store.createPost = original;
    assert.equal(f.logs.length, 1);
    assert(!f.logs[0].includes('FIXTURE_A'));
  });
  await t.test('origin, malformed inputs and unavailable Core fail closed', async () => {
    assert.equal((await f.request('/posts', { headers: { origin: 'https://elsewhere.invalid' } })).status, 403);
    assert.equal((await f.request('/posts?page=-1')).status, 400);
    assert.equal((await f.request('/posts?page=0&page=1')).status, 400);
    assert.equal((await f.request('/posts/not-a-uuid')).status, 400);
    assert.equal((await f.request('/coins/wallet')).status, 404);
    f.state.coreFail = true;
    assert.equal((await f.request('/posts')).status, 503);
    f.state.coreFail = false;
  });
  await t.test('only owner deletes; historical operation and source identity remain', async () => {
    assert.equal((await f.request('/posts/' + postId, { method: 'DELETE', token: 'FIXTURE_B', payload: { requestId: randomUUID() } })).status, 403);
    assert.equal((await f.request('/posts/' + postId, { method: 'DELETE', payload: { requestId: randomUUID() } })).status, 200);
    assert.equal((await f.request('/posts/' + postId)).status, 404);
    assert.equal((await f.request('/posts/' + postId + '/comments')).status, 404);
    assert.equal((await f.request('/operations/' + key)).data.result.postId, postId);
    assert.equal((await f.database.query('SELECT gallery_artwork_id FROM community.posts WHERE id=$1', [postId])).rows[0].gallery_artwork_id, paintingId);
    assert.equal(f.state.writes, 0, 'Community never writes Core or calls a provider');
  });
});
